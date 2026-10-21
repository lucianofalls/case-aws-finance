package br.com.itau.financas.acl;

import br.com.itau.financas.acl.RegistroDeContratoLegado.RegistroLegadoInvalidoException;
import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClientBuilder;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Gatilho: fila {@code corebanking-contratos} (o legado publica via MQ → SQS). Publica {@code LimiteConcedido}
 * no bus {@code financas}. Mensagem com erro volta para a fila e vai para a DLQ após maxReceiveCount.
 */
public final class ContratoLegadoHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger LOG = LoggerFactory.getLogger(ContratoLegadoHandler.class);
    private static final String ORIGEM = "itau.financas.corebanking-acl";
    private static final EventBridgeClient EVENTBRIDGE = eventBridge();
    private static final TradutorDeContratoLegado TRADUTOR = new TradutorDeContratoLegado();

    @Override
    public SQSBatchResponse handleRequest(SQSEvent lote, Context contexto) {
        List<SQSBatchResponse.BatchItemFailure> falhas = new ArrayList<>();
        for (SQSEvent.SQSMessage mensagem : lote.getRecords()) {
            try {
                publicar(TRADUTOR.traduzir(RegistroDeContratoLegado.ler(mensagem.getBody())));
            } catch (RuntimeException e) {
                String causa = e instanceof RegistroLegadoInvalidoException ? "registro rejeitado pela ACL" : "falha técnica";
                LOG.error("{} na mensagem {}", causa, mensagem.getMessageId(), e);
                falhas.add(new SQSBatchResponse.BatchItemFailure(mensagem.getMessageId()));
            }
        }
        return new SQSBatchResponse(falhas);
    }

    private static void publicar(CloudEvent evento) {
        var resultado = EVENTBRIDGE.putEvents(PutEventsRequest.builder().entries(PutEventsRequestEntry.builder()
                .eventBusName(System.getenv("BARRAMENTO_EVENTOS"))
                .source(ORIGEM)
                .detailType(evento.type())
                .detail(CodecDeEventos.paraJson(evento))
                .build()).build());
        if (resultado.failedEntryCount() > 0) {
            throw new IllegalStateException("EventBridge rejeitou o evento " + evento.id() + ": "
                    + resultado.entries().getFirst().errorMessage());
        }
    }

    private static EventBridgeClient eventBridge() {
        EventBridgeClientBuilder builder = EventBridgeClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
