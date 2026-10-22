#!/usr/bin/env python3
"""Aceite local real: HTTP -> DynamoDB/Streams/Lambda/Kinesis -> ledger PostgreSQL.

Somente biblioteca padrão. O seed administrativo e a entrega manual de SQS são
exclusivos da demonstração; nenhuma chamada usa endpoints AWS reais.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from uuid import UUID, uuid4

BASE = Path(__file__).resolve().parent
COMPOSE = ['docker', 'compose', '-f', str(BASE / 'docker-compose.yml')]
API = os.environ.get('API', 'http://localhost:8080').rstrip('/')
LIMITES = os.environ.get('LIMITES', 'http://localhost:8082').rstrip('/')
CONTA = '0001-123456'
CLIENTE = 'canal-demo'
TOKEN = None


def exigir(condicao, mensagem):
    if not condicao:
        raise AssertionError(mensagem)


def executar(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=120).stdout.strip()


def aws(servico, operacao, *args):
    return json.loads(executar(*COMPOSE, 'exec', '-T', 'localstack', 'awslocal',
                              '--region', 'sa-east-1', servico, operacao, *args, '--output', 'json') or '{}')


def http(url, corpo=None, headers=None):
    dados = None if corpo is None else json.dumps(corpo).encode()
    req = Request(url, data=dados, headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        with urlopen(req, timeout=35) as resposta:
            return resposta.status, json.load(resposta), resposta.headers
    except HTTPError as erro:
        bruto = erro.read().decode()
        try:
            resultado = json.loads(bruto)
        except json.JSONDecodeError:
            resultado = {'resposta': bruto[:500]}
        return erro.code, resultado, erro.headers


def aguardar(descricao, funcao, segundos=180):
    prazo = time.monotonic() + segundos
    while True:
        try:
            pronto = funcao()
        except (URLError, TimeoutError, ConnectionError):
            pronto = False
        if pronto:
            print('OK:', descricao, flush=True)
            return
        if time.monotonic() >= prazo:
            raise AssertionError('Timeout: ' + descricao)
        time.sleep(2)


def permissao(contrato, ativo=True):
    item = {'id_cliente': {'S': CLIENTE}, 'id_contrato': {'S': contrato},
            'id_conta': {'S': CONTA}, 'ativo': {'BOOL': ativo},
            'valido_ate': {'N': str(int(time.time()) + 3600)}}
    aws('dynamodb', 'put-item', '--table-name', 'permissoes-contratos', '--item', json.dumps(item))


def contrato_novo():
    # Formato legado é fixo: contrato=20, conta=12. UUID curto evita overflow de $RANDOM.
    contrato = 'CTR-' + uuid4().hex[:12]
    registro = f'{contrato:<20}{CONTA:<12}CP{100000:015d}BRL{1:010d}{datetime.now(timezone.utc):%Y%m%d%H%M%S}'
    exigir(len(registro) == 76, 'Registro legado precisa de 76 caracteres')
    aws('sqs', 'send-message', '--queue-url', fila('corebanking-contratos'), '--message-body', registro)
    aguardar('core -> ACL -> EventBridge -> SQS -> Limites: ' + contrato,
             lambda: http(f'{LIMITES}/v1/contratos/{contrato}/posicao')[0] == 200)
    permissao(contrato)
    saldo(contrato, '1000.00', '0.00', '0.00')
    return contrato


def fila(nome):
    return aws('sqs', 'get-queue-url', '--queue-name', nome)['QueueUrl']


def corpo(tipo, valor, original=None):
    pedido = {'id_conta': CONTA, 'valor': valor, 'moeda': 'BRL', 'tipo_operacao': tipo}
    if original:
        pedido['metadata'] = {'id_autorizacao_original': original}
    return pedido


def postar(contrato, pedido, esperado, chave=None, token=None, extras=None):
    status, resultado, headers = chamar(contrato, pedido, chave, token, extras)
    exigir(status == esperado, f'HTTP {status}, esperado {esperado}: {resultado}')
    if esperado >= 400:
        exigir(resultado.get('status') == esperado and 'codigo' in resultado,
               f'Erro fora de Problem Details: {resultado}')
    print(f'OK: {pedido["tipo_operacao"]} HTTP {status}', flush=True)
    return resultado, headers


def chamar(contrato, pedido, chave=None, token=None, extras=None):
    headers = {'Idempotency-Key': chave or str(uuid4())}
    identidade = TOKEN if token is None else token
    if identidade:
        headers['Authorization'] = 'Bearer ' + identidade
    headers.update(extras or {})
    return http(f'{API}/v1/contratos/{contrato}/autorizacoes', pedido, headers)


def saldo(contrato, disponivel, bloqueado, utilizado):
    status, atual, _ = http(f'{LIMITES}/v1/contratos/{contrato}/posicao')
    exigir(status == 200, f'Não consultou posição: {atual}')
    valores = [Decimal(atual[k]) for k in ('limite_disponivel', 'saldo_bloqueado', 'saldo_reservado', 'limite_concedido')]
    d, b, comprometido, total = valores
    exigir((d, b, comprometido - b) == tuple(map(Decimal, (disponivel, bloqueado, utilizado))),
           f'Posição divergente: {atual}; esperado disponível/bloqueado/utilizado={disponivel}/{bloqueado}/{utilizado}')
    exigir(d + comprometido == total, f'Invariante financeira violada: {atual}')
    return atual


def sql(consulta):
    return executar(*COMPOSE, 'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1',
                    '-U', 'contabilizacao', '-d', 'contabilizacao', '-tAc', consulta)


def ledger(contrato, ids):
    # IDs são exclusivamente UUIDs retornados pelo próprio serviço e o contrato é gerado localmente.
    ids = sorted({str(UUID(i)) for i in ids})
    esperado = len(ids)
    query = f"SELECT count(DISTINCT id_autorizacao) FROM registro_contabil WHERE id_contrato='{contrato}'"
    aguardar(f'ledger recebeu os {esperado} fatos financeiros de {contrato}',
             lambda: int(sql(query)) == esperado, 240)
    encontrados = set(sql(f"SELECT DISTINCT id_autorizacao FROM registro_contabil WHERE id_contrato='{contrato}'").splitlines())
    exigir(encontrados == set(ids), f'IDs contábeis divergentes: {encontrados}, esperado {set(ids)}')
    incorretos = sql(f"""SELECT count(*) FROM (
        SELECT r.fato_id FROM registro_contabil r LEFT JOIN lancamento l USING(fato_id)
        WHERE r.id_contrato='{contrato}' GROUP BY r.fato_id
        HAVING count(l.id)<2 OR sum(CASE l.natureza WHEN 'D' THEN l.valor ELSE -l.valor END)<>0
        ) t""")
    exigir(incorretos == '0', 'Partidas dobradas ausentes/desbalanceadas')
    duplicados = sql(f"""SELECT count(*) FROM (
        SELECT id_autorizacao FROM registro_contabil WHERE id_contrato='{contrato}'
        GROUP BY id_autorizacao HAVING count(*)<>1) t""")
    exigir(duplicados == '0', 'Replay produziu registros contábeis duplicados')
    print('OK: Inbox/ledger sem duplicidade e partidas dobradas balanceadas', flush=True)


def ciclo_reserva():
    contrato = contrato_novo()
    reserva, _ = postar(contrato, corpo('RESERVA', 100), 201)
    rid = reserva['id_autorizacao']
    ids = [rid]
    for tipo, valor in [('CAPTURA', 30), ('LIBERACAO', 20)]:
        resultado, _ = postar(contrato, corpo(tipo, valor, rid), 201)
        ids.append(resultado['id_autorizacao'])
    saldo(contrato, '920', '50', '30')
    outra, _ = postar(contrato, corpo('RESERVA', 40), 201)
    ids.append(outra['id_autorizacao'])
    postar(contrato, corpo('CAPTURA', 60, rid), 422)  # total bloqueado=90, saldo individual=50
    resultado, _ = postar(contrato, corpo('LIBERACAO', 40, outra['id_autorizacao']), 201)
    ids.append(resultado['id_autorizacao'])
    saldo(contrato, '920', '50', '30')
    item = aws('dynamodb', 'get-item', '--table-name', 'autorizacoes', '--consistent-read',
               '--key', json.dumps({'id_autorizacao': {'S': rid}}))['Item']
    vencimento = datetime.fromisoformat(item['reserva_expira_em']['S'].replace('Z', '+00:00')).timestamp()
    espera = max(0, vencimento - time.time() + 2)
    exigir(espera <= 300, 'Reserva local >5 minutos: recrie autorização com VALIDADE_RESERVA_LOCAL=PT2M em subir.sh')
    print(f'Aguardando vencimento REAL da reserva ({espera:.0f}s); regra de negócio permanece ativa.', flush=True)
    while time.time() < vencimento + 2:
        time.sleep(min(5, max(0.1, vencimento + 2 - time.time())))
    mensagem = json.dumps({'id_autorizacao': rid, 'id_contrato': contrato})
    aws('sqs', 'send-message', '--queue-url', fila('reservas-a-expirar'), '--message-body', mensagem)
    aguardar('expiração liberou somente os 50.00 restantes',
             lambda: http(f'{LIMITES}/v1/contratos/{contrato}/posicao')[1].get('saldo_bloqueado') == '0.00')
    saldo(contrato, '970', '0', '30')
    def autorizacoes_do_ciclo():
        return aws('dynamodb', 'scan', '--table-name', 'autorizacoes', '--consistent-read',
                   '--filter-expression', 'id_contrato = :c',
                   '--expression-attribute-values', json.dumps({':c': {'S': contrato}}))['Items']
    aguardar('liberação por expiração persistida na Autorização',
             lambda: len(autorizacoes_do_ciclo()) == len(ids) + 1)
    itens = autorizacoes_do_ciclo()
    eventos = [str(UUID(i['evento_id']['S'])) for i in itens]
    lista = ','.join("'" + e + "'" for e in eventos)
    aguardar('Inbox recebeu inclusive reservas e liberações sem efeito contábil',
             lambda: int(sql(f"SELECT count(*) FROM evento_processado WHERE evento_id IN ({lista})")) == len(eventos), 240)
    # RESERVA/LIBERACAO entram no Inbox, mas apenas CAPTURA gera partidas dobradas.
    ledger(contrato, [ids[1]])
    aws('sqs', 'send-message', '--queue-url', fila('reservas-a-expirar'), '--message-body', mensagem)
    vazias = 0
    def fila_drenada():
        nonlocal vazias
        atributos = aws('sqs', 'get-queue-attributes', '--queue-url', fila('reservas-a-expirar'),
                        '--attribute-names', 'ApproximateNumberOfMessages',
                        'ApproximateNumberOfMessagesNotVisible')['Attributes']
        vazias = vazias + 1 if all(int(v) == 0 for v in atributos.values()) else 0
        return vazias >= 3
    aguardar('reentrega de expiração consumida', fila_drenada)
    exigir(len(autorizacoes_do_ciclo()) == len(itens), 'Reentrega criou outra autorização de liberação')
    saldo(contrato, '970', '0', '30')
    ledger(contrato, [ids[1]])


def basico():
    contrato = contrato_novo()
    pedido = corpo('DEBITO', 100)
    postar(contrato, pedido, 401, token='', extras={'X-Client-Id': CLIENTE})
    postar(contrato, pedido, 401, token='invalido')
    outro = executar(sys.executable, str(BASE / 'token-demo.py'), 'canal-demo-ciclo')
    postar(contrato, pedido, 403, token=outro)
    postar(contrato, {**pedido, 'id_conta': 'outra-conta'}, 403)
    saldo(contrato, '1000', '0', '0')
    chave = str(uuid4())
    debito, _ = postar(contrato, pedido, 201, chave=chave)
    replay, _ = postar(contrato, pedido, 200, chave=chave)
    exigir(replay == debito, 'Replay não retornou a decisão original')
    postar(contrato, corpo('DEBITO', 101), 422, chave=chave)
    postar(contrato, corpo('DEBITO', 5000), 402)
    postar(contrato, {**pedido, 'campo_desconhecido': True}, 422)
    postar(contrato, corpo('DEBITO', 0.001), 422)
    postar(contrato, corpo('ESTORNO', 1), 422)
    credito, _ = postar(contrato, corpo('CREDITO', 20), 201)
    estorno, _ = postar(contrato, corpo('ESTORNO', 30, debito['id_autorizacao']), 201)
    saldo(contrato, '950', '0', '50')
    permissao(contrato, False)
    postar(contrato, pedido, 403, chave=chave)  # revogação vale inclusive para o replay
    saldo(contrato, '950', '0', '50')
    ledger(contrato, [r['id_autorizacao'] for r in (debito, credito, estorno)])


def concorrencia():
    contrato = contrato_novo()
    def enviar(_):
        chave = str(uuid4())
        for tentativa in range(10):
            status, resultado, _ = chamar(contrato, corpo('DEBITO', 600), chave)
            if status not in (409, 503):
                return 201 if status == 200 else status
            time.sleep(0.1 * (tentativa + 1))
        raise AssertionError(f'Concorrência sem convergência: {status} {resultado}')
    with ThreadPoolExecutor(max_workers=2) as pool:
        codigos = list(pool.map(enviar, range(2)))
    exigir(sorted(codigos) == [201, 402], f'Dois débitos de 600/limite 1000: {codigos}')
    saldo(contrato, '400', '0', '600')
    print('OK: concorrência não ultrapassou limite', flush=True)


def indisponibilidade():
    contrato = contrato_novo()
    try:
        executar(*COMPOSE, 'stop', 'limites')
        _, headers = postar(contrato, corpo('DEBITO', 1), 503)
        exigir(headers.get('Retry-After'), '503 sem Retry-After')
    finally:
        executar(*COMPOSE, 'start', 'limites')
    aguardar('Limites restaurado após falha', lambda: http(LIMITES + '/actuator/health')[0] == 200)


def main():
    global TOKEN
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--somente-reserva', action='store_true')
    args = parser.parse_args()
    for url in (API, LIMITES):
        exigir(urlsplit(url).hostname in ('localhost', '127.0.0.1', '::1'),
               'Aceite destrutivo restrito a endpoints loopback locais')
    TOKEN = executar(sys.executable, str(BASE / 'token-demo.py'), CLIENTE)
    if not args.somente_reserva:
        basico()
        concorrencia()
        indisponibilidade()
    ciclo_reserva()
    print('ACEITE LOCAL APROVADO. EKS/Pod Identity/Cognito/Scheduler e RDS Multi-AZ reais não foram testados.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, subprocess.SubprocessError, OSError, ValueError, KeyError) as erro:
        print('FALHA NO ACEITE LOCAL:', erro, file=sys.stderr)
        if isinstance(erro, subprocess.CalledProcessError):
            print(erro.stderr, file=sys.stderr)
        sys.exit(1)
