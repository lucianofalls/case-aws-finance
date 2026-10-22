# Instruções do Codex — Itaú POD99

Este repositório implementa o case de arquitetura do Itaú POD99. A **arquitetura vigente** é **Amazon EKS Auto Mode + Amazon RDS PostgreSQL convencional Multi-AZ**, com DynamoDB por bounded context Autorização/Limites, Kinesis para alto volume, EventBridge/SQS/SNS para integração e conciliação de operações financeiras.

## Primeira ação obrigatória

1. Leia integralmente [PENDENCIAS-CODEX.md](PENDENCIAS-CODEX.md), [MIGRACAO-EKS-RDS.md](MIGRACAO-EKS-RDS.md), [STATUS-EKS-RDS](apresentacao-itau/docs/STATUS-EKS-RDS.md), [ADR-042](apresentacao-itau/docs/adr/042-eks-automode-rds-postgres.md), [ADR-043](apresentacao-itau/docs/adr/043-entitlement-cliente-contrato.md) e [ADR-044](apresentacao-itau/docs/adr/044-posicao-bloqueio-por-reserva.md).
2. Inspecione o estado real do código antes de iniciar cada item e consulte o PDF original do desafio quando disponível.
3. Trabalhe **em fases pequenas**, uma por vez, seguindo a ordem P0 → P1 → P2 e os critérios de aceite.
4. Preserve o escopo bancário: BIAN/DDD, idempotência, reserva por ID, consistência, CloudEvents, compensação, IAM mínimo, Well-Architected e FSI Lens.

## Regras inegociáveis

- **NÃO** criar, ativar ou executar GitHub Actions; nenhum workflow em `.github/workflows`.
- **NÃO** executar `terraform apply` em AWS, publicar imagens ou criar serviços com custo, nem fazer deploy `kubectl` em EKS sem autorização explícita.
- **NÃO** migrar para ECS, Fargate ou Aurora; são decisões históricas substituídas por EKS Auto Mode e RDS PostgreSQL padrão.
- **NÃO** salvar segredos, senhas, tokens, credenciais reais ou `.tfstate` no Git.
- Não declarar Maven/LocalStack/EKS/Terraform aprovado sem log ou evidência efetiva. O usuário fará a execução E2E no Mac.
- Não descartar recursos existentes úteis: corrigir de forma incremental, documentar trade-offs em ADRs e manter compatibilidade dos contratos.

## Saída exigida ao concluir cada fase

Registrar no [backlog](PENDENCIAS-CODEX.md) e em [STATUS-EKS-RDS](apresentacao-itau/docs/STATUS-EKS-RDS.md): itens concluídos, arquivos alterados, validações realmente executadas, pendências para o usuário testar localmente, riscos em aberto e SHA dos commits. Só marcar como concluído o que estiver de fato implementado e revisado.
