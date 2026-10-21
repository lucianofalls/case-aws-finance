package br.com.itau.financas.reconciliador.expiracao;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Gatilho: Kinesis {@code transacoes}, filtrado no event source mapping para {@code TransacaoAutorizada} de
 * RESERVA. Cria um agendamento único por reserva no EventBridge Scheduler (ADR 035): na expiração, a fila
 * {@code reservas-a-expirar} recebe o id e a Autorização libera o saldo ainda bloqueado.
 * Falha parcial: devolve a primeira sequência não agendada; o Lambda retoma dali (agendar é idempotente).
 */
public final class AgendadorDeExpiracaoHandler implements RequestHandler<KinesisEvent, StreamsEventResponse> {

    static final String TIPO_AUTORIZADA = "br.com.itau.financas.autorizacao.transacao-autorizada.v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Padrao {
        static final AgendaDeExpiracao AGENDA = new SchedulerAgendaDeExpiracao(
                SchedulerClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                System.getenv("GRUPO_AGENDAMENTOS"), System.getenv("FILA_RESERVAS_A_EXPIRAR_ARN"),
                System.getenv("PAPEL_AGENDADOR_ARN"), Clock.systemUTC());
    }

    private final AgendaDeExpiracao agenda;

    public AgendadorDeExpiracaoHandler() {
        this(Padrao.AGENDA);
    }

    AgendadorDeExpiracaoHandler(AgendaDeExpiracao agenda) {
        this.agenda = agenda;
    }

    @Override
    public StreamsEventResponse handleRequest(KinesisEvent lote, Context contexto) {
        for (KinesisEvent.KinesisEventRecord registro : lote.getRecords()) {
            try {
                agendarSeForReserva(registro.getKinesis().getData().array());
            } catch (RuntimeException | IOException e) {
                return StreamsEventResponse.builder().withBatchItemFailures(List.of(
                        new StreamsEventResponse.BatchItemFailure(registro.getKinesis().getSequenceNumber()))).build();
            }
        }
        return new StreamsEventResponse();
    }

    private void agendarSeForReserva(byte[] dados) throws IOException {
        JsonNode evento = JSON.readTree(new String(dados, StandardCharsets.UTF_8));
        JsonNode dadosDoFato = evento.path("data");
        if (!TIPO_AUTORIZADA.equals(evento.path("type").asText())
                || !"RESERVA".equals(dadosDoFato.path("tipo_operacao").asText())
                || dadosDoFato.path("reserva_expira_em").isMissingNode()) {
            return;
        }
        agenda.agendar(dadosDoFato.path("id_autorizacao").asText(), dadosDoFato.path("id_contrato").asText(),
                Instant.parse(dadosDoFato.path("reserva_expira_em").asText()));
    }
}
