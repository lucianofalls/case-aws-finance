#!/usr/bin/env python3
"""Emissor APENAS local: chave efêmera ignorada pelo Git, nunca aceita por prod."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import time

BASE = Path(__file__).resolve().parent / '.segredos-demo'
BASE.mkdir(mode=0o700, exist_ok=True)
PRIVADA = BASE / 'jwt-private.pem'
PUBLICA = BASE / 'jwt-public.pem'
if not PRIVADA.exists():
    fd = os.open(PRIVADA, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as destino:
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'],
                       stdout=destino, stderr=subprocess.DEVNULL, check=True)
if not PUBLICA.exists():
    subprocess.run(['openssl', 'pkey', '-in', str(PRIVADA), '-pubout', '-out', str(PUBLICA)], check=True)
if len(sys.argv) > 1 and sys.argv[1] == '--preparar':
    sys.exit(0)
cliente = sys.argv[1] if len(sys.argv) > 1 else 'canal-demo'
def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=')
agora = int(time.time())
cabecalho = {'alg': 'RS256', 'typ': 'JWT'}
payload = {'iss': 'https://emissor.local.test', 'sub': cliente, 'client_id': cliente,
           'token_use': 'access', 'scope': 'financas/autorizacoes.write', 'iat': agora, 'exp': agora + 900}
mensagem = b'.'.join(b64(json.dumps(x).encode()) for x in (cabecalho, payload))
assinatura = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(PRIVADA)],
                           input=mensagem, stdout=subprocess.PIPE, check=True).stdout
print((mensagem + b'.' + b64(assinatura)).decode())
