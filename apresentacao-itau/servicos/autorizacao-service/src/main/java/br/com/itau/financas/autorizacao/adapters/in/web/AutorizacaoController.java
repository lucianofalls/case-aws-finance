package br.com.itau.financas.autorizacao.adapters.in.web;

import br.com.itau.financas.autorizacao.application.ResultadoAutorizacao;
import br.com.itau.financas.autorizacao.application.SolicitacaoDeAutorizacao;
import br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacao;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Adapter de entrada HTTP: só traduz HTTP ↔ caso de uso (SRP).
 * O header X-Client-Id é injetado pelo API Gateway a partir do client_id do token Cognito.
 */
@RestController
public class AutorizacaoController {

    private final AutorizarTransacao autorizarTransacao;

    public AutorizacaoController(AutorizarTransacao autorizarTransacao) {
        this.autorizarTransacao = autorizarTransacao;
    }

    @PostMapping("/v1/contratos/{idContrato}/autorizacoes")
    public ResponseEntity<?> autorizar(@PathVariable String idContrato,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @RequestHeader("X-Client-Id") String clienteId,
                                       @RequestHeader(value = "traceparent", required = false) String traceparent,
                                       @Valid @RequestBody AutorizacaoRequest corpo) {
        if (corpo.idContrato() != null && !corpo.idContrato().equals(idContrato)) {
            throw new RegraDeNegocioVioladaException("contrato-divergente", "id_contrato do corpo difere do path");
        }
        ResultadoAutorizacao resultado = autorizarTransacao.executar(new SolicitacaoDeAutorizacao(
                clienteId, idempotencyKey, idContrato, corpo.idConta(), corpo.valor(), corpo.moeda(),
                corpo.tipoOperacao(), corpo.idEstabelecimento(), corpo.idAutorizacaoOriginal(),
                MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID), traceparent));
        return responder(resultado);
    }

    private static ResponseEntity<?> responder(ResultadoAutorizacao resultado) {
        Autorizacao autorizacao = resultado.autorizacao();
        if (!autorizacao.foiAprovada()) {
            // Recusa por regra de negócio; a repetição idempotente devolve a mesma recusa.
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(recusa(autorizacao));
        }
        AutorizacaoResponse corpo = AutorizacaoResponse.de(autorizacao);
        if (!resultado.criadaAgora()) {
            return ResponseEntity.ok(corpo);
        }
        return ResponseEntity.created(URI.create("/v1/autorizacoes/" + autorizacao.id())).body(corpo);
    }

    private static ProblemDetail recusa(Autorizacao autorizacao) {
        ProblemDetail problema = Problemas.de(HttpStatus.PAYMENT_REQUIRED, "limite-insuficiente",
                "Limite insuficiente", "o contrato não tem limite disponível para o valor solicitado");
        problema.setProperty("id_autorizacao", autorizacao.id().toString());
        problema.setProperty("limite_disponivel", autorizacao.posicao().limiteDisponivel().comoTexto());
        return problema;
    }
}
