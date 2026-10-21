package br.com.itau.financas.stubs;

import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumidorDeTransacoesTest {

    @Test
    void registroMalformadoDevolveFalhaParcialParaRetomarDaquele() {
        StreamsEventResponse resposta = new AntifraudeStub().handleRequest(lote("nao-e-json"), null);

        assertThat(resposta.getBatchItemFailures()).extracting(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("seq-1");
    }

    @Test
    void eventoValidoEhConsumidoSemFalhas() {
        String evento = """
                {"specversion":"1.0","id":"e1","source":"s",
                 "type":"br.com.itau.financas.autorizacao.transacao-autorizada.v1",
                 "time":"2026-10-20T00:00:00Z","partitionkey":"CTR-1","data":{"id_contrato":"CTR-1"}}
                """;

        assertThat(new NotificacoesStub().handleRequest(lote(evento), null).getBatchItemFailures()).isNullOrEmpty();
    }

    private static KinesisEvent lote(String dados) {
        KinesisEvent.Record kinesis = new KinesisEvent.Record();
        kinesis.setSequenceNumber("seq-1");
        kinesis.setData(ByteBuffer.wrap(dados.getBytes(StandardCharsets.UTF_8)));
        KinesisEvent.KinesisEventRecord registro = new KinesisEvent.KinesisEventRecord();
        registro.setKinesis(kinesis);
        KinesisEvent evento = new KinesisEvent();
        evento.setRecords(List.of(registro));
        return evento;
    }
}
