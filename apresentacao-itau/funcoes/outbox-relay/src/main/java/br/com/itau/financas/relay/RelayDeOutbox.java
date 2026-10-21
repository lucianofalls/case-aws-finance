package br.com.itau.financas.relay;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse.BatchItemFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Núcleo do relay (independente do destino): extrai os eventos do lote do Stream e publica.
 * <p>
 * Falha parcial: devolve a <b>primeira</b> sequência que falhou. O Lambda retoma o shard do Streams a partir
 * dela (nada é pulado); os eventos seguintes podem ser reenviados (at-least-once), o que é seguro porque
 * todo consumidor deduplica pelo {@code id} do CloudEvent.
 * <p>
 * Por que Lambda e não EventBridge Pipes: no volume do PDF o Pipes custaria ~US$ 0,69/milhão
 * (~US$ 2,7 mil/mês); este relay em lote custa dezenas de dólares (docs/custos.md, ADR 026).
 */
public final class RelayDeOutbox {

    private final ExtratorDeEventos extrator;
    private final Destino destino;

    public RelayDeOutbox(ExtratorDeEventos extrator, Destino destino) {
        this.extrator = extrator;
        this.destino = destino;
    }

    public StreamsEventResponse processar(DynamodbEvent lote) {
        List<EventoParaPublicar> eventos = new ArrayList<>();
        for (DynamodbStreamRecord registro : lote.getRecords()) {
            extrator.extrair(registro).ifPresent(eventos::add);
        }
        if (eventos.isEmpty()) {
            return new StreamsEventResponse();
        }
        Set<String> falhas = destino.publicar(eventos);
        return eventos.stream()
                .filter(evento -> falhas.contains(evento.sequencia()))
                .findFirst()
                .map(primeiraFalha -> StreamsEventResponse.builder()
                        .withBatchItemFailures(List.of(new BatchItemFailure(primeiraFalha.sequencia())))
                        .build())
                .orElseGet(StreamsEventResponse::new);
    }
}
