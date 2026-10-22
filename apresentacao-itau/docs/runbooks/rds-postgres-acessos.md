# Runbook — RDS PostgreSQL: privilégio mínimo, segredos e migrações

**Estado:** preparação para deploy autorizado, **não executado**. Nunca usar senha em comando versionado ou Terraform state.

## Responsabilidades

- `contabilizacao_admin` (RDS master): usado somente por DBA/bootstrap e migração inicial; **não** em Deployment.
- `contabilizacao_app`: role de execução com `CONNECT`, `USAGE` e DML nas tabelas de Inbox/razão; **sem privilégios DDL**.
- `contabilizacao_export` (futuro): separar leitura/exportação do reconciliador da credencial admin. Sem ela, produção permanece com risco pendente.

## Bootstrap (DBA autorizado via psql seguro na VPC)

Primeiro rode as migrações da pasta `servicos/contabilizacao-service/src/main/resources/db/migration` usando uma conta DDL temporária/administrativa por processo controlado, preservando o histórico `flyway_schema_history`.

Em uma sessão `psql` **interativa** como administrador (não salve credenciais no shell histórico):

```sql
CREATE ROLE contabilizacao_app LOGIN;
GRANT CONNECT ON DATABASE contabilizacao TO contabilizacao_app;
GRANT USAGE ON SCHEMA public TO contabilizacao_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO contabilizacao_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO contabilizacao_app;
-- Para objetos criados por futuras migrações conduzidas pelo administrador:
ALTER DEFAULT PRIVILEGES FOR ROLE contabilizacao_admin IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO contabilizacao_app;
ALTER DEFAULT PRIVILEGES FOR ROLE contabilizacao_admin IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO contabilizacao_app;
```

Na mesma sessão `psql`, estabeleça a senha **sem exibi-la nem comitá-la**:

```text
\password contabilizacao_app
```

Cadastre a credencial de aplicativo em um **segredo distinto** no Secrets Manager sob governança da conta AWS. O mecanismo de sincronização com Kubernetes deve projetar exclusivamente esse segredo para `rds-contabilizacao/password` no namespace `financas`. O segredo do administrador RDS não deve ser montado no Pod.

## Migrações seguintes

Mantenha Flyway desativado nos Pods de runtime; cada mudança de esquema deve ser executada por job controlado de migração na rede privada e verificada antes do rollout. Esse job/controle de permissões **ainda não está automatizado** neste repositório.

## Validações obrigatórias antes de produção

- A role `contabilizacao_app` consegue fazer Inbox + partidas dobradas e não consegue `CREATE TABLE` ou `DROP TABLE`.
- Nenhum Pod contém senha de administrador e nenhum secret está em Git/Terraform state.
- O pool JDBC tem tamanho total compatível com `max_connections` e número máximo de Pods.
- `sslmode=verify-full`, CA RDS, certificado/SAN e rotação de certificado validados.
- Role separada `contabilizacao_export` para a Lambda de conciliação ainda pendente.

**Não ativar GitHub Actions; não executar `terraform apply` AWS sem autorização.**
