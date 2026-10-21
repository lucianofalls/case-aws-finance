package br.com.itau.financas.autorizacao.domain;

import java.util.UUID;

/** Chave de idempotência enviada pelo cliente no header {@code Idempotency-Key} (UUID, obrigatório). */
public record IdempotencyKey(UUID valor) {

    public static IdempotencyKey de(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            throw new RegraDeNegocioVioladaException("idempotency-key-ausente", "header Idempotency-Key é obrigatório");
        }
        try {
            return new IdempotencyKey(UUID.fromString(bruto.trim()));
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocioVioladaException("idempotency-key-invalida", "Idempotency-Key deve ser um UUID");
        }
    }
}
