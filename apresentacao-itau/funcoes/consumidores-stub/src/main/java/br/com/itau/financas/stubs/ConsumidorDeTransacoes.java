package br.com.itau.financas.stubs;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Base dos consumidores stub do stream {@code transacoes} (Lambda + Kinesis event source mapping).
 * Os sistemas reais (antifraude, notificações) são de outros times; o stub prova o contrato do evento
 * (CloudEvents v1) e o desacoplamento. Falha de um registro → retoma a partir dele (ordem do shard; a ordem lógica entre fatos não é garantida).
 */
public abstract class ConsumidorDeTransacoes implements RequestHandler<KinesisEvent, StreamsEventResponse> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected abstract Set<String> tiposDeInteresse();

    protected abstract void reagir(CloudEvent evento, Logger log);

    @Override
    public StreamsEventResponse handleRequest(KinesisEvent lote, Context contexto) {
        for (KinesisEvent.KinesisEventRecord registro : lote.getRecords()) {
            try {
                CloudEvent evento = CodecDeEventos.deJson(
                        StandardCharsets.UTF_8.decode(registro.getKinesis().getData()).toString());
                if (tiposDeInteresse().contains(evento.type())) {
                    reagir(evento, log);
                }
            } catch (RuntimeException e) {
                log.error("falha no registro {}", registro.getKinesis().getSequenceNumber(), e);
                return StreamsEventResponse.builder().withBatchItemFailures(List.of(
                        new StreamsEventResponse.BatchItemFailure(registro.getKinesis().getSequenceNumber()))).build();
            }
        }
        return new StreamsEventResponse();
    }
}
