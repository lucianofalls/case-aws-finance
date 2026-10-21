package br.com.itau.financas.relay.limites;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.relay.EventoParaPublicar;
import br.com.itau.financas.relay.ExtratorDeEventos;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

import java.util.Map;
import java.util.Optional;

/**
 * Outbox explícito da tabela {@code limites}: só itens {@code OUT#} inseridos carregam um CloudEvent pronto.
 * O filtro do event source mapping (Terraform) já descarta os demais itens — e registros filtrados não são cobrados.
 */
public final class ExtratorDeOutboxExplicito implements ExtratorDeEventos {

    private static final String PREFIXO_OUTBOX = "OUT#";

    @Override
    public Optional<EventoParaPublicar> extrair(DynamodbStreamRecord registro) {
        if (!"INSERT".equals(registro.getEventName())) {
            return Optional.empty();
        }
        Map<String, AttributeValue> nova = registro.getDynamodb().getNewImage();
        AttributeValue sk = nova.get("sk");
        AttributeValue json = nova.get("cloudevent");
        if (sk == null || !sk.getS().startsWith(PREFIXO_OUTBOX) || json == null) {
            return Optional.empty();
        }
        CloudEvent evento = CodecDeEventos.deJson(json.getS());
        return Optional.of(new EventoParaPublicar(registro.getDynamodb().getSequenceNumber(), evento, json.getS()));
    }
}
