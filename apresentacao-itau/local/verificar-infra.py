#!/usr/bin/env python3
"""Espera infraestrutura LocalStack operacional antes de iniciar o E2E."""
import json
from pathlib import Path
import subprocess
import sys
import time

COMPOSE = ['docker', 'compose', '-f', str(Path(__file__).with_name('docker-compose.yml'))]
FUNCOES = {'relay-autorizacoes': 1, 'relay-limites': 1, 'agendador-verificacao': 1,
           'reconciliador': 1, 'corebanking-acl': 1, 'antifraude-stub': 1, 'notificacoes-stub': 1}


def comando(*args):
    return subprocess.check_output([*COMPOSE, 'exec', '-T', 'localstack', *args],
                                   text=True, stderr=subprocess.STDOUT, timeout=25)


def aws(*args):
    return json.loads(comando('awslocal', '--region', 'sa-east-1', *args, '--output', 'json'))


def conferir():
    comando('test', '-S', '/var/run/docker.sock')
    for nome in ('autorizacoes', 'limites', 'permissoes-contratos'):
        tabela = aws('dynamodb', 'describe-table', '--table-name', nome)['Table']
        if tabela['TableStatus'] != 'ACTIVE':
            raise ValueError('Tabela ainda não ativa: ' + nome)
        if nome != 'permissoes-contratos' and not tabela.get('LatestStreamArn'):
            raise ValueError('Stream ausente: ' + nome)
    if aws('kinesis', 'describe-stream-summary', '--stream-name', 'transacoes')['StreamDescriptionSummary']['StreamStatus'] != 'ACTIVE':
        raise ValueError('Kinesis ainda não ativo')
    for nome in ('corebanking-contratos', 'limites-concedidos', 'reservas-a-expirar',
                 'autorizacoes-verificacao', 'contabilizacao-quarentena'):
        aws('sqs', 'get-queue-url', '--queue-name', nome)
    for nome, quantidade in FUNCOES.items():
        config = aws('lambda', 'get-function-configuration', '--function-name', nome)
        if config.get('State') == 'Failed' or config.get('LastUpdateStatus') == 'Failed':
            raise RuntimeError('Lambda falhou: ' + nome + ': ' + config.get('StateReason', config.get('LastUpdateStatusReason', '')))
        if config.get('State') != 'Active':
            raise ValueError('Lambda ainda não ativa: ' + nome)
        mappings = aws('lambda', 'list-event-source-mappings', '--function-name', nome + ':atual')['EventSourceMappings']
        if len(mappings) < quantidade or any(m['State'] != 'Enabled' for m in mappings):
            raise ValueError('Event source mapping ainda não habilitado: ' + nome)
    regra = aws('events', 'describe-rule', '--event-bus-name', 'financas', '--name', 'limite-concedido')
    if regra['State'] != 'ENABLED':
        raise ValueError('Regra limite-concedido desabilitada')
    if not aws('events', 'list-targets-by-rule', '--event-bus-name', 'financas', '--rule', 'limite-concedido')['Targets']:
        raise ValueError('EventBridge sem destino para LimiteConcedido')


def main():
    prazo = time.monotonic() + 180
    while True:
        try:
            conferir()
            print('OK: socket Docker, tabelas/Streams, Kinesis, filas, 7 Lambdas, mappings e regra EventBridge')
            return
        except (ValueError, KeyError, subprocess.SubprocessError) as erro:
            if time.monotonic() >= prazo:
                raise RuntimeError('Infraestrutura não ficou pronta: ' + str(erro)) from erro
            print('Aguardando infraestrutura:', str(erro)[-600:], flush=True)
            time.sleep(3)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError) as erro:
        print(erro, file=sys.stderr)
        sys.exit(1)
