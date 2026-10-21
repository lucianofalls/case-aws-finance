package br.com.itau.financas.relay;

import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import br.com.itau.financas.relay.autorizacoes.ExtratorDeAutorizacoes;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RelayDeOutboxTest {

    private final List<EventoParaPublicar> publicados = new ArrayList<>();

    @Test
    void publicaFatoNovoComoCloudEventComChaveDeParticaoPorContrato() {
        RelayDeOutbox relay = new RelayDeOutbox(new ExtratorDeAutorizacoes(), destinoQueAceitaTudo());

        relay.processar(lote(registro("1", "INSERT", item("evt-1"), null)));

        assertThat(publicados).hasSize(1);
        EventoParaPublicar evento = publicados.getFirst();
        assertThat(evento.chaveDeParticao()).isEqualTo("CTR-1");
        assertThat(evento.evento().type()).isEqualTo(TiposDeEvento.TRANSACAO_AUTORIZADA);
        assertThat(CodecDeEventos.payloadDe(evento.evento(), PayloadsV1.TransacaoAutorizada.class).valor())
                .isEqualTo("150.00");
    }

    @Test
    void atualizacaoSemFatoNovoNaoEhPublicada() {
        RelayDeOutbox relay = new RelayDeOutbox(new ExtratorDeAutorizacoes(), destinoQueAceitaTudo());

        relay.processar(lote(registro("2", "MODIFY", item("evt-1"), item("evt-1"))));

        assertThat(publicados).isEmpty();
    }

    @Test
    void falhaParcialDevolveAPrimeiraSequenciaQueFalhou() {
        RelayDeOutbox relay = new RelayDeOutbox(new ExtratorDeAutorizacoes(), eventos -> Set.of("20", "30"));

        StreamsEventResponse resposta = relay.processar(lote(
                registro("10", "INSERT", item("a"), null),
                registro("20", "INSERT", item("b"), null),
                registro("30", "INSERT", item("c"), null)));

        assertThat(resposta.getBatchItemFailures()).hasSize(1);
        assertThat(resposta.getBatchItemFailures().getFirst().getItemIdentifier()).isEqualTo("20");
    }

    private Destino destinoQueAceitaTudo() {
        return eventos -> {
            publicados.addAll(eventos);
            return Set.of();
        };
    }

    private static DynamodbEvent lote(DynamodbStreamRecord... registros) {
        DynamodbEvent evento = new DynamodbEvent();
        evento.setRecords(List.of(registros));
        return evento;
    }

    private static DynamodbStreamRecord registro(String sequencia, String operacao, Map<String, AttributeValue> nova,
                                                 Map<String, AttributeValue> antiga) {
        StreamRecord dados = new StreamRecord();
        dados.setSequenceNumber(sequencia);
        dados.setNewImage(nova);
        dados.setOldImage(antiga);
        DynamodbStreamRecord registro = new DynamodbStreamRecord();
        registro.setEventName(operacao);
        registro.setDynamodb(dados);
        return registro;
    }

    private static Map<String, AttributeValue> item(String eventoId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id_autorizacao", new AttributeValue().withS("6f1c5a52-1d0e-5c0a-9a1e-7c1f0e2b9d10"));
        item.put("id_contrato", new AttributeValue().withS("CTR-1"));
        item.put("id_conta", new AttributeValue().withS("0001-123"));
        item.put("tipo_operacao", new AttributeValue().withS("DEBITO"));
        item.put("valor", new AttributeValue().withN("150.00"));
        item.put("moeda", new AttributeValue().withS("BRL"));
        item.put("saldo_reservado", new AttributeValue().withN("150.00"));
        item.put("limite_disponivel", new AttributeValue().withN("850.00"));
        item.put("correlation_id", new AttributeValue().withS("corr-1"));
        item.put("evento_id", new AttributeValue().withS(eventoId));
        item.put("evento_tipo", new AttributeValue().withS("TRANSACAO_AUTORIZADA"));
        item.put("evento_ocorrido_em", new AttributeValue().withS("2026-10-20T15:00:00Z"));
        return item;
    }
}
