package br.com.itau.financas.reconciliador.expiracao;

import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 035: cada RESERVA aprovada ganha um agendamento único de expiração; os outros fatos são ignorados. */
class AgendadorDeExpiracaoHandlerTest {

    private final List<String> agendadas = new ArrayList<>();

    @Test
    void agendaSoAsReservasAprovadas() {
        var handler = new AgendadorDeExpiracaoHandler((id, contrato, expiraEm) -> agendadas.add(id + "@" + expiraEm));

        StreamsEventResponse resposta = handler.handleRequest(lote(
                registro("1", evento("RESERVA", "res-1", "\"reserva_expira_em\":\"2026-10-27T12:00:00Z\",")),
                registro("2", evento("DEBITO", "deb-1", "")),
                registro("3", evento("CAPTURA", "cap-1", ""))), null);

        assertThat(agendadas).containsExactly("res-1@2026-10-27T12:00:00Z");
        assertThat(resposta.getBatchItemFailures()).isNullOrEmpty();
    }

    @Test
    void falhaAoAgendarDevolveASequenciaParaRetentar() {
        var handler = new AgendadorDeExpiracaoHandler((id, contrato, expiraEm) -> {
            throw new IllegalStateException("Scheduler indisponível");
        });

        StreamsEventResponse resposta = handler.handleRequest(lote(
                registro("7", evento("RESERVA", "res-7", "\"reserva_expira_em\":\"2026-10-27T12:00:00Z\","))), null);

        assertThat(resposta.getBatchItemFailures()).extracting(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("7");
    }

    @Test
    void expiracaoNoPassadoViraUmMinutoDepoisDeAgora() {
        var agenda = new SchedulerAgendaDeExpiracao(null, "reservas", "arn:fila", "arn:papel", "arn:dlq",
                Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC));

        assertThat(agenda.expressao(Instant.parse("2026-10-27T12:00:00Z"))).isEqualTo("at(2026-10-28T10:01:00)");
        assertThat(agenda.expressao(Instant.parse("2026-11-03T09:30:00Z"))).isEqualTo("at(2026-11-03T09:30:00)");
        assertThat(SchedulerAgendaDeExpiracao.nome("abc")).isEqualTo("reserva-abc");
        assertThat(SchedulerAgendaDeExpiracao.payload("id-a\"b", "contrato-c"))
                .contains("id-a\\\"b", "contrato-c");
    }

    private static String evento(String tipoOperacao, String id, String extra) {
        return "{\"type\":\"" + AgendadorDeExpiracaoHandler.TIPO_AUTORIZADA + "\",\"data\":{" + extra
                + "\"id_autorizacao\":\"" + id + "\",\"id_contrato\":\"CTR-1\",\"tipo_operacao\":\"" + tipoOperacao + "\"}}";
    }

    private static KinesisEvent lote(KinesisEvent.KinesisEventRecord... registros) {
        KinesisEvent evento = new KinesisEvent();
        evento.setRecords(List.of(registros));
        return evento;
    }

    private static KinesisEvent.KinesisEventRecord registro(String sequencia, String json) {
        KinesisEvent.Record dados = new KinesisEvent.Record();
        dados.setSequenceNumber(sequencia);
        dados.setData(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        KinesisEvent.KinesisEventRecord registro = new KinesisEvent.KinesisEventRecord();
        registro.setKinesis(dados);
        return registro;
    }
}
