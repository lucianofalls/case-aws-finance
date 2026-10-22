# Módulo ECS arquivado (não utilizado em prod)

O código original ECS Fargate foi preservado apenas para auditoria/histórico. A stack ativa em
`infra/terraform/ambientes/prod/main.tf` referencia `../../modulos/eks` em vez deste módulo.
Não execute este módulo nem presuma que o plano ECS corresponde ao design final.
Consulte ADR-042.
