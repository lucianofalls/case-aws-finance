package br.com.itau.financas.autorizacao.adapters.in.web;

import br.com.itau.financas.autorizacao.application.ConflitoDeConcorrenciaException;
import br.com.itau.financas.autorizacao.application.LimitesIndisponivelException;
import br.com.itau.financas.autorizacao.application.OperacaoEncerradaException;
import br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.stream.Collectors;

/** Tradução única de exceções → códigos HTTP do contrato (PDF §2.3). */
@RestControllerAdvice
public class ProblemDetailsHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    @ExceptionHandler(RegraDeNegocioVioladaException.class)
    ResponseEntity<ProblemDetail> regraDeNegocio(RegraDeNegocioVioladaException e) {
        return responder(HttpStatus.UNPROCESSABLE_ENTITY, e.codigo(), "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> payloadInvalido(MethodArgumentNotValidException e) {
        String detalhe = e.getBindingResult().getFieldErrors().stream()
                .map(erro -> erro.getField() + ": " + erro.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return responder(HttpStatus.UNPROCESSABLE_ENTITY, "payload-invalido", "Payload inválido", detalhe);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ProblemDetail> requisicaoMalformada(Exception e) {
        return responder(HttpStatus.UNPROCESSABLE_ENTITY, "requisicao-malformada", "Requisição malformada",
                e.getMessage());
    }

    @ExceptionHandler(ConflitoDeConcorrenciaException.class)
    ResponseEntity<ProblemDetail> conflito(ConflitoDeConcorrenciaException e) {
        return responder(HttpStatus.CONFLICT, "conflito-de-concorrencia", "Conflito de estado", e.getMessage());
    }

    @ExceptionHandler(OperacaoEncerradaException.class)
    ResponseEntity<ProblemDetail> encerrada(OperacaoEncerradaException e) {
        return responder(HttpStatus.CONFLICT, "operacao-encerrada", "Operação encerrada", e.getMessage());
    }

    @ExceptionHandler(LimitesIndisponivelException.class)
    ResponseEntity<ProblemDetail> indisponivel(LimitesIndisponivelException e) {
        LOG.warn("fail-fast: {} (resultado incerto={})", e.getMessage(), e.resultadoIncerto());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Problemas.de(HttpStatus.SERVICE_UNAVAILABLE, "servico-degradado", "Serviço degradado",
                        "autorização temporariamente indisponível; retente com a mesma Idempotency-Key"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e) {
        LOG.error("erro inesperado", e);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "erro-interno", "Erro interno",
                "erro inesperado; use o correlation_id para rastrear");
    }

    private static ResponseEntity<ProblemDetail> responder(HttpStatus status, String codigo, String titulo,
                                                           String detalhe) {
        return ResponseEntity.status(status).body(Problemas.de(status, codigo, titulo, detalhe));
    }
}
