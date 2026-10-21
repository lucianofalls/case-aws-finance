package br.com.itau.financas.limites.adapters.in.web;

import br.com.itau.financas.limites.application.ConflitoDeConcorrenciaException;
import br.com.itau.financas.limites.domain.Excecoes.ContratoSemLimiteException;
import br.com.itau.financas.limites.domain.Excecoes.LimiteInsuficienteException;
import br.com.itau.financas.limites.domain.Excecoes.OperacaoEncerradaException;
import br.com.itau.financas.limites.domain.Posicao;
import br.com.itau.financas.limites.domain.RegraDeNegocioVioladaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** Problem Details (RFC 7807) da API interna; o 402 carrega a posição para o chamador montar a recusa. */
@RestControllerAdvice
public class ProblemDetailsHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsHandler.class);
    private static final String BASE_TIPOS = "https://api.itau.com.br/financas/limites/problemas/";

    @ExceptionHandler(LimiteInsuficienteException.class)
    ResponseEntity<ProblemDetail> insuficiente(LimiteInsuficienteException e) {
        Posicao posicao = e.posicao();
        ProblemDetail problema = problema(HttpStatus.PAYMENT_REQUIRED, "limite-insuficiente", e.getMessage());
        problema.setProperty("saldo_reservado", posicao.comprometido().comoTexto());
        problema.setProperty("limite_disponivel", posicao.disponivel().comoTexto());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(problema);
    }

    @ExceptionHandler(ContratoSemLimiteException.class)
    ResponseEntity<ProblemDetail> semLimite(ContratoSemLimiteException e) {
        return responder(HttpStatus.NOT_FOUND, "contrato-sem-limite", e.getMessage());
    }

    @ExceptionHandler(OperacaoEncerradaException.class)
    ResponseEntity<ProblemDetail> encerrada(OperacaoEncerradaException e) {
        return responder(HttpStatus.CONFLICT, "operacao-encerrada", e.getMessage());
    }

    @ExceptionHandler(ConflitoDeConcorrenciaException.class)
    ResponseEntity<ProblemDetail> conflito(ConflitoDeConcorrenciaException e) {
        return responder(HttpStatus.CONFLICT, "conflito-de-concorrencia", e.getMessage());
    }

    @ExceptionHandler(RegraDeNegocioVioladaException.class)
    ResponseEntity<ProblemDetail> regra(RegraDeNegocioVioladaException e) {
        return responder(HttpStatus.UNPROCESSABLE_ENTITY, e.codigo(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalido(Exception e) {
        return responder(HttpStatus.UNPROCESSABLE_ENTITY, "payload-invalido", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e) {
        LOG.error("erro inesperado", e);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "erro-interno", "erro inesperado");
    }

    private static ResponseEntity<ProblemDetail> responder(HttpStatus status, String codigo, String detalhe) {
        return ResponseEntity.status(status).body(problema(status, codigo, detalhe));
    }

    private static ProblemDetail problema(HttpStatus status, String codigo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(URI.create(BASE_TIPOS + codigo));
        problema.setProperty("codigo", codigo);
        return problema;
    }
}
