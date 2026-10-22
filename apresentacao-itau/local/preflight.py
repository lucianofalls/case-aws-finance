#!/usr/bin/env python3
"""Pré-condições locais antes de build/apply; sem chamadas à AWS."""
import json
import os
import re
from pathlib import Path
import subprocess
import sys


def main():
    # Não aceitar variáveis que redirecionem provider/state/CLI silenciosamente.
    proibidas = [k for k in os.environ if k.startswith('TF_CLI_ARGS') or k in ('TF_DATA_DIR', 'TF_WORKSPACE')]
    if proibidas:
        raise ValueError('Remova variáveis Terraform antes da demo: ' + ', '.join(sorted(proibidas)))
    for executavel, args, minimo in [('mvn', ['-v'], (3, 9)),
                                      ('docker', ['compose', 'version', '--short'], (2, 20))]:
        saida = subprocess.check_output([executavel, *args], text=True, stderr=subprocess.STDOUT)
        match = re.search(r'(\d+)\.(\d+)\.(\d+)', saida)
        if not match or tuple(map(int, match.groups()[:2])) < minimo:
            raise ValueError(f'{executavel} requer versão >= {minimo[0]}.{minimo[1]}')
    if sys.version_info < (3, 9):
        raise ValueError('Python >=3.9 é obrigatório')
    versao = json.loads(subprocess.check_output(['terraform', 'version', '-json'], text=True))['terraform_version']
    if tuple(map(int, versao.split('.')[:2])) < (1, 9):
        raise ValueError('Terraform >=1.9 é obrigatório')
    dados = json.loads(subprocess.check_output(['docker', 'compose', '-f',
                       str(Path(__file__).with_name('docker-compose.yml')), 'config', '--format', 'json'], text=True))
    servicos = dados['services']
    url = servicos['autorizacao']['environment']['AWS_ENDPOINT_URL']
    if url != 'http://localstack:4566':
        raise ValueError('Compose deve usar somente o endpoint interno do LocalStack')
    validade = servicos['autorizacao']['environment']['VALIDADE_RESERVA']
    if not re.fullmatch(r'PT(?:[1-4]M|[3-9][0-9]S|1[0-9][0-9]S|2[0-3][0-9]S)', validade):
        raise ValueError('Use VALIDADE_RESERVA_LOCAL entre PT30S e PT4M; padrão PT2M')
    print('OK: Maven >=3.9, Compose >=2.20, Python >=3.9, Terraform >=1.9, endpoint e validade')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, subprocess.SubprocessError, OSError) as erro:
        print('Pré-requisito inválido:', erro, file=sys.stderr)
        sys.exit(1)
