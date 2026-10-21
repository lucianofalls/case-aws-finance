package br.com.itau.financas.autorizacao.adapters.in.sqs;

import br.com.itau.financas.autorizacao.application.ExpirarReservaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Consumidor da fila {@code reservas-a-expirar}: o EventBridge Scheduler entrega {@code {"id_autorizacao": "..."}} na
 * data de expiração de cada reserva (ADR 035). Exceção = mensagem não removida → nova entrega → DLQ após
 * maxReceiveCount (Terraform). A liberação é idempotente, então reentregar é seguro.
 */
public class ExpiracaoDeReservaListener {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiracaoDeReservaListener.class);

    private final ExpirarReservaService expirar;
    private final ObjectMapper json;

    public ExpiracaoDeReservaListener(ExpirarReservaService expirar, ObjectMapper json) {
        this.expirar = expirar;
        this.json = json;
    }

    @SqsListener("${autorizacao.reserva.fila-expiracao}")
    public void receber(String mensagem) throws IOException {
        JsonNode corpo = json.readTree(mensagem);
        String idReserva = corpo.path("id_autorizacao").asText();
        ExpirarReservaService.Desfecho desfecho = expirar.expirar(idReserva);
        LOG.info("expiração da reserva {}: {}", idReserva, desfecho);
    }
}
