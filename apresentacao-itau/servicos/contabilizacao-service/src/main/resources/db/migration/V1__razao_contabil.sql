-- Inbox: deduplicação de eventos (entrega at-least-once do Kinesis).
CREATE TABLE evento_processado (
    evento_id     UUID        PRIMARY KEY,
    processado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Financial Booking Log (BIAN): um registro por fato contábil, imutável.
CREATE TABLE registro_contabil (
    fato_id        UUID        PRIMARY KEY REFERENCES evento_processado (evento_id),
    id_autorizacao VARCHAR(64) NOT NULL,
    id_contrato    VARCHAR(64) NOT NULL,
    data_contabil  DATE        NOT NULL,
    registrado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ledger Posting (BIAN): pernas da partida dobrada. Append-only, sem saldo em linha quente (C9).
CREATE TABLE lancamento (
    id           BIGSERIAL     PRIMARY KEY,
    fato_id      UUID          NOT NULL REFERENCES registro_contabil (fato_id),
    conta_codigo VARCHAR(32)   NOT NULL,
    conta_nome   VARCHAR(128)  NOT NULL,
    natureza     CHAR(1)       NOT NULL CHECK (natureza IN ('D', 'C')),
    valor        NUMERIC(18,2) NOT NULL CHECK (valor > 0),
    moeda        CHAR(3)       NOT NULL
);

CREATE INDEX ix_lancamento_fato ON lancamento (fato_id);
CREATE INDEX ix_registro_data ON registro_contabil (data_contabil);

-- Defesa em profundidade: a partida dobrada é validada no domínio e conferida no commit.
CREATE FUNCTION verificar_partidas_dobradas() RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT COALESCE(SUM(CASE natureza WHEN 'D' THEN valor ELSE -valor END), 0)
          FROM lancamento WHERE fato_id = NEW.fato_id) <> 0 THEN
        RAISE EXCEPTION 'partidas dobradas desbalanceadas no fato %', NEW.fato_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tg_partidas_dobradas
    AFTER INSERT ON lancamento
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verificar_partidas_dobradas();
