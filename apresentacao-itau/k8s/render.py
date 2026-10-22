#!/usr/bin/env python3
"""Renders workloads offline. Does not run AWS, kubectl or terraform apply.

Inputs (all required): REGION, IMAGE_AUTORIZACAO, IMAGE_LIMITES, IMAGE_CONTABILIZACAO,
TABELA_AUTORIZACOES, TABELA_LIMITES, FILA_EXPIRACAO, FILA_LIMITES,
RDS_HOST, STREAM_TRANSACOES, KCL_APLICACAO, FILA_QUARENTENA,
TG_AUTORIZACAO, TG_LIMITES, VPC_CIDR.
DB password is NEVER an environment input to this renderer. Create Kubernetes Secret separately.
"""
import os
import sys
from pathlib import Path
from string import Template

ROOT=Path(__file__).resolve().parent
REQUIRED=('REGION','IMAGE_AUTORIZACAO','IMAGE_LIMITES','IMAGE_CONTABILIZACAO',
'TABELA_AUTORIZACOES','TABELA_LIMITES','FILA_EXPIRACAO','FILA_LIMITES','RDS_HOST',
'STREAM_TRANSACOES','KCL_APLICACAO','FILA_QUARENTENA','TG_AUTORIZACAO','TG_LIMITES','VPC_CIDR')
missing=[name for name in REQUIRED if not os.getenv(name)]
if missing:
    sys.exit('Faltam variáveis: '+', '.join(missing)+' — consulte k8s/README.md')
source=(ROOT/'templates/stack.yaml.tpl').read_text()
rendered=Template(source).substitute({name:os.environ[name] for name in REQUIRED})
if 'password:' in rendered and 'value:' in rendered.split('name: DB_SENHA')[-1].split('---')[0]:
    sys.exit('Senha de DB não pode entrar em ConfigMap/manifests')
out=ROOT/'rendered-stack.yaml'
out.write_text(rendered)
print('Gerado:',out,'(não fez kubectl apply).')
