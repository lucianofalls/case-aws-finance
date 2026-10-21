package br.com.itau.financas.contabilizacao.domain;

import br.com.itau.financas.kernel.Dinheiro;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato financeiro recebido de outro contexto, já traduzido para a linguagem da Contabilização.
 * {@code eventoId} é o id do CloudEvent: chave de deduplicação (entrega at-least-once).
 */
public record FatoContabil(UUID eventoId, Tipo tipo, String idAutorizacao, String idContrato, String tipoOperacao,
                           Dinheiro valor, Instant ocorridoEm) {

    public enum Tipo { TRANSACAO_AUTORIZADA, ESTORNO_REALIZADO }

    public FatoContabil {
        Objects.requireNonNull(eventoId);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(idAutorizacao);
        Objects.requireNonNull(idContrato);
        Objects.requireNonNull(tipoOperacao);
        Objects.requireNonNull(valor);
        Objects.requireNonNull(ocorridoEm);
    }
}
