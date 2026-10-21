package br.com.itau.financas.autorizacao.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain Event gerado pelo agregado, no passado e imutável.
 * Viaja no próprio item da autorização (outbox embutido, ADR 026): a escrita do agregado e do evento
 * é uma única escrita atômica, e o relay publica a partir do DynamoDB Streams.
 */
public record FatoDeAutorizacao(UUID id, Tipo tipo, Instant ocorridoEm) {

    public enum Tipo {
        TRANSACAO_AUTORIZADA,
        TRANSACAO_RECUSADA,
        ESTORNO_REALIZADO
    }

    public FatoDeAutorizacao {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(ocorridoEm);
    }

    static FatoDeAutorizacao novo(Tipo tipo, Instant agora) {
        return new FatoDeAutorizacao(UUID.randomUUID(), tipo, agora);
    }
}
