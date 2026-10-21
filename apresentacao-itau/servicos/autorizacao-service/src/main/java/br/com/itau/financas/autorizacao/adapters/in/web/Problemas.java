package br.com.itau.financas.autorizacao.adapters.in.web;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/** Fábrica única de Problem Details (RFC 7807): type, title, status, detail + codigo e correlation_id. */
final class Problemas {

    private static final String BASE_TIPOS = "https://api.itau.com.br/financas/problemas/";

    private Problemas() {
    }

    static ProblemDetail de(HttpStatus status, String codigo, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(URI.create(BASE_TIPOS + codigo));
        problema.setTitle(titulo);
        problema.setProperty("codigo", codigo);
        problema.setProperty("correlation_id", MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID));
        return problema;
    }
}
