package br.com.itau.financas.autorizacao.domain;

/** Correlação ponta a ponta (PDF §2.4: correlation_id obrigatório, trace_id W3C opcional). */
public record Rastreio(String correlationId, String traceparent) {

    public Rastreio {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId é obrigatório");
        }
    }
}
