package br.com.itau.financas.limites.adapters.in.sqs;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import br.com.itau.financas.limites.application.AplicarLimiteDoLegadoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Consumidor de {@code LimiteConcedido}: EventBridge (regra por detail-type) → SQS → este listener.
 * A mensagem é o envelope do EventBridge; o CloudEvent está em {@code detail}.
 * Exceção = mensagem não removida → nova entrega → DLQ após maxReceiveCount (configurado no Terraform).
 */
public class LimiteConcedidoListener {

    private static final Logger LOG = LoggerFactory.getLogger(LimiteConcedidoListener.class);
    /** O LimiteAlterado resultante continua a cadeia do LimiteConcedido. */
    private static final String MDC_CORRELATION_ID = "correlation_id";

    private final AplicarLimiteDoLegadoService aplicarLimite;
    private final ObjectMapper json;

    public LimiteConcedidoListener(AplicarLimiteDoLegadoService aplicarLimite, ObjectMapper json) {
        this.aplicarLimite = aplicarLimite;
        this.json = json;
    }

    @SqsListener("${limites.fila-limites-concedidos}")
    public void receber(String mensagem) throws JsonProcessingException {
        JsonNode envelope = json.readTree(mensagem);
        CloudEvent evento = CodecDeEventos.deJson(json.writeValueAsString(envelope.path("detail")));
        if (!TiposDeEvento.LIMITE_CONCEDIDO.equals(evento.type())) {
            LOG.warn("tipo de evento inesperado {} ignorado (id {})", evento.type(), evento.id());
            return;
        }
        PayloadsV1.LimiteConcedido payload = CodecDeEventos.payloadDe(evento, PayloadsV1.LimiteConcedido.class);
        MDC.put(MDC_CORRELATION_ID, evento.correlationid() == null ? evento.id() : evento.correlationid());
        try {
            var desfecho = aplicarLimite.aplicar(new AplicarLimiteDoLegadoService.Comando(payload.idContrato(),
                    payload.idConta(), payload.produto(), payload.limiteConcedido(), payload.moeda(),
                    payload.versaoOrigem()));
            LOG.info("limite do contrato {} versão {}: {}", payload.idContrato(), payload.versaoOrigem(), desfecho);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }
}
