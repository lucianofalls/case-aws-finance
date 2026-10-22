#!/usr/bin/env python3
"""Auditoria estática da migração POD99 para EKS/RDS PostgreSQL.

Somente leitura de arquivos. Não necessita AWS, Terraform, Docker, kubectl ou Maven.
Não substitui terraform validate, mvn verify ou testes E2E.
"""
from pathlib import Path
import sys
import re

BASE = Path(__file__).resolve().parents[1]


def ler(relativo):
    caminho = BASE / relativo
    if not caminho.is_file():
        raise AssertionError(f'Arquivo ausente: {relativo}')
    return caminho.read_text(encoding='utf-8')


def verificar(nome, condicao, detalhe):
    print(f"{'OK' if condicao else 'FALHA'} | {nome}" + (f' — {detalhe}' if not condicao else ''))
    return bool(condicao)


def atributo(texto, nome, valor):
    """Checagem textual independente do alinhamento aplicado por terraform fmt."""
    return re.search(rf"^\s*{re.escape(nome)}\s*=\s*{re.escape(valor)}\s*(?:#.*)?$",
                     texto, re.MULTILINE) is not None


def main():
    prod = ler('infra/terraform/ambientes/prod/main.tf')
    vars_prod = ler('infra/terraform/ambientes/prod/variables.tf')
    dados = ler('infra/terraform/modulos/dados/main.tf')
    eks = ler('infra/terraform/modulos/eks/main.tf')
    conciliacao = ler('infra/terraform/modulos/conciliacao/maquina-de-estados.asl.json')
    funcoes = ler('infra/terraform/modulos/funcoes/main.tf')
    kube = ler('k8s/templates/stack.yaml.tpl')
    request = ler('servicos/autorizacao-service/src/main/java/br/com/itau/financas/autorizacao/adapters/in/web/AutorizacaoRequest.java')
    source = ler('contracts/openapi/schemas/autorizacao-request.json')
    importer = ler('funcoes/reconciliador/src/main/java/br/com/itau/financas/reconciliador/conciliacao/ExportarRazaoJdbcHandler.java')

    tests = [
        ('EKS em produção, ECS inativo', 'module "eks"' in prod and 'module "compute"' not in prod, 'Fargate ainda referenciado como módulo ativo'),
        ('RDS PostgreSQL, sem Aurora', 'resource "aws_db_instance" "contabilizacao"' in dados and 'resource "aws_rds_cluster" "contabilizacao"' not in dados, 'Ainda existe um cluster Aurora'),
        ('RDS Multi-AZ e privado', atributo(dados, 'multi_az', 'true') and atributo(dados, 'publicly_accessible', 'false'), 'RDS precisa de standby e rede privada'),
        ('EKS privado e Auto Mode', atributo(eks, 'endpoint_public_access', 'false') and 'compute_config {' in eks, 'Cluster não tem as duas restrições'),
        ('Pod Identity por serviço', 'aws_eks_pod_identity_association' in prod and 'pods.eks.amazonaws.com' in prod, 'Associação IAM ausente'),
        ('Contabilidade com senha independente da master', 'var.segredo_exportacao_rds_arn' in prod and 'variable "segredo_exportacao_rds_arn"' in vars_prod, 'Lambda de exportação deve ter segredo próprio'),
        ('Reconciliação sem Aurora Data API', 'aws-sdk:rdsdata' not in conciliacao and 'exportar_razao_arn' in conciliacao, 'Machine state ainda depende da Data API'),
        ('Lambda JDBC substituta', 'ExportarRazaoJdbcHandler' in funcoes and 'jdbc:postgresql://' in importer, 'Reconciliador não usa JDBC'),
        ('Manifests do EKS', all(x in kube for x in ('kind: Deployment','kind: TargetGroupBinding','serviceAccountName: contabilizacao')), 'Faltam manifests Kubernetes'),
        ('Contrato de seis operações', all(x in request and x in source for x in ('CAPTURA','LIBERACAO')), 'Contrato/DTO divergentes'),
        ('Proibição de GitHub Actions', not (BASE.parent / '.github' / 'workflows').exists(), 'Workflows não autorizados'),
    ]
    failed = [name for name, expected, reason in tests if not verificar(name, expected, reason)]
    if failed:
        print(f'REPROVADO: {len(failed)} controles estáticos: ' + ', '.join(failed))
        return 1
    print('APROVADO: verificações estáticas de consistência EKS/RDS. NÃO valida nuvem ou código executável.')
    return 0


if __name__ == '__main__':
    sys.exit(main())