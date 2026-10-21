-- Conciliação diária (BIAN Account Reconciliation; ADR 034): o Aurora exporta os evento_id contabilizados para o
-- S3 com aws_s3.query_export_to_s3, e o Athena confronta com a trilha e com a exportação da tabela autorizacoes.
-- A extensão só existe no Aurora/RDS; no Postgres comum do ambiente local a migração não faz nada.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'aws_s3') THEN
        CREATE EXTENSION IF NOT EXISTS aws_s3 CASCADE;
    END IF;
END
$$;

-- A exportação filtra por processado_em; sem índice, cada conciliação varreria o inbox inteiro.
CREATE INDEX IF NOT EXISTS ix_evento_processado_em ON evento_processado (processado_em);
