#!/usr/bin/env python3
"""Replay MANUAL e idempotente da trilha CloudEvents WORM após TTL do DynamoDB.

Este utilitário lê arquivos locais .json, .jsonl, .json.gz ou .jsonl.gz
baixados previamente do S3. Nunca baixa S3, nunca publica sem --enviar,
e exige confirmação via --evento-id, --stream e --endpoint-url.

Exemplo (LocalStack):
  python3 scripts/recuperar-evento-trilha.py --arquivo trilha.jsonl.gz \\
      --evento-id <uuid> --stream transacoes --endpoint-url http://localhost:4566

  # Após validar a prévia, usar --enviar para efetuar o PutRecord (at-least-once).
"""
import argparse
import gzip
import json
from pathlib import Path


def iterar_linhas(path: Path):
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rt', encoding='utf-8') as arquivo:
        for numero, linha in enumerate(arquivo, 1):
            if not linha.strip():
                continue
            try:
                evento = json.loads(linha)
            except json.JSONDecodeError as e:
                raise ValueError(f'Linha {numero} não é JSON válido: {path}') from e
            yield evento


def validar_evento(evento, id_esperado):
    if evento.get('id') != id_esperado:
        return False
    if evento.get('specversion') != '1.0' or not isinstance(evento.get('type'), str):
        raise ValueError('O evento não atende CloudEvents 1.0')
    dados = evento.get('data')
    if not isinstance(dados, dict) or not dados.get('id_contrato'):
        raise ValueError('Evento não contém data.id_contrato para a chave Kinesis')
    if not isinstance(evento.get('source'), str) or not isinstance(evento.get('time'), str):
        raise ValueError('Evento sem source/time original; não é seguro republicar')
    return True


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--arquivo', type=Path, required=True, help='JSON Lines original baixado da trilha WORM')
    p.add_argument('--evento-id', required=True, help='CloudEvent id original (nunca criar outro UUID)')
    p.add_argument('--stream', required=True)
    p.add_argument('--endpoint-url', required=True, help='Endpoint AWS ou LocalStack EXPLÍCITO')
    p.add_argument('--regiao', default='sa-east-1')
    p.add_argument('--enviar', action='store_true', help='EFETUA PutRecord; sem esta flag é dry-run')
    args = p.parse_args()

    correspondentes = [ev for ev in iterar_linhas(args.arquivo) if validar_evento(ev, args.evento_id)]
    if len(correspondentes) != 1:
        raise SystemExit(f'Esperado exatamente 1 CloudEvent com id={args.evento_id}; obtidos {len(correspondentes)}. Não publicar.')
    evento = correspondentes[0]
    print(json.dumps({'id': evento['id'], 'type': evento['type'],
                      'id_contrato': evento['data']['id_contrato'],
                      'stream': args.stream, 'endpoint': args.endpoint_url,
                      'modo': 'ENVIAR' if args.enviar else 'DRY-RUN'}, indent=2, ensure_ascii=False))
    if not args.enviar:
        print('Nenhum evento foi publicado. Inclua --enviar somente após revisão.')
        return
    try:
        import boto3
    except ImportError as e:
        raise SystemExit('Para --enviar, instale boto3 (python3 -m pip install boto3)') from e
    client = boto3.client('kinesis', region_name=args.regiao, endpoint_url=args.endpoint_url)
    resposta = client.put_record(StreamName=args.stream,
                                PartitionKey=str(evento['data']['id_contrato']),
                                Data=json.dumps(evento, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    print('Publicado com mesmo event_id; SequenceNumber:', resposta['SequenceNumber'])


if __name__ == '__main__':
    main()
