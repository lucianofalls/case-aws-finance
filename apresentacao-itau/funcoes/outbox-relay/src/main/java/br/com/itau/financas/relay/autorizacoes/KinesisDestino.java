package br.com.itau.financas.relay.autorizacoes;

import br.com.itau.financas.relay.Destino;
import br.com.itau.financas.relay.EventoParaPublicar;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResultEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stream de alto volume {@code transacoes}, partition key = id_contrato (distribuição entre shards).
 * PutRecords em lotes de até 500: <b>não garante ordem</b> (doc AWS), e o relay processa itens em paralelo.
 * Os consumidores são independentes de ordem e deduplicam pelo id do CloudEvent (PDF §2.4).
 */
public final class KinesisDestino implements Destino {

    private static final int MAXIMO_POR_CHAMADA = 500;

    private final KinesisClient kinesis;
    private final String stream;

    public KinesisDestino(KinesisClient kinesis, String stream) {
        this.kinesis = kinesis;
        this.stream = stream;
    }

    @Override
    public Set<String> publicar(List<EventoParaPublicar> eventos) {
        Set<String> falhas = new HashSet<>();
        for (int inicio = 0; inicio < eventos.size(); inicio += MAXIMO_POR_CHAMADA) {
            List<EventoParaPublicar> lote = eventos.subList(inicio, Math.min(inicio + MAXIMO_POR_CHAMADA, eventos.size()));
            List<PutRecordsResultEntry> resultados = kinesis.putRecords(PutRecordsRequest.builder()
                    .streamName(stream)
                    .records(lote.stream().map(KinesisDestino::registro).toList())
                    .build()).records();
            for (int i = 0; i < resultados.size(); i++) {
                if (resultados.get(i).errorCode() != null) {
                    falhas.add(lote.get(i).sequencia());
                }
            }
        }
        return falhas;
    }

    private static PutRecordsRequestEntry registro(EventoParaPublicar evento) {
        return PutRecordsRequestEntry.builder()
                .partitionKey(evento.chaveDeParticao())
                .data(SdkBytes.fromUtf8String(evento.json()))
                .build();
    }
}
