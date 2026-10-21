package br.com.itau.financas.autorizacao.domain;

/** Mesma Idempotency-Key reutilizada com payload diferente → HTTP 422. */
public class RequisicaoIdempotenteDivergenteException extends RegraDeNegocioVioladaException {

    public RequisicaoIdempotenteDivergenteException(AutorizacaoId id) {
        super("idempotency-key-reutilizada",
                "Idempotency-Key já usada com outro conteúdo (autorização " + id + ")");
    }
}
