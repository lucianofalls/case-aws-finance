package br.com.itau.financas.reconciliador.expiracao;

import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.ActionAfterCompletion;
import software.amazon.awssdk.services.scheduler.model.ConflictException;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Um agendamento único ({@code at(...)}) por reserva no EventBridge Scheduler, apagado após executar
 * ({@code ActionAfterCompletion = DELETE}). O nome {@code reserva-<id>} torna a criação idempotente: a reentrega do
 * evento recebe {@code ConflictException}, que significa "já agendado". Quotas padrão em sa-east-1: 10 milhões de
 * agendamentos e 5.000 criações/s, ajustáveis (doc AWS).
 */
public final class SchedulerAgendaDeExpiracao implements AgendaDeExpiracao {

    private static final DateTimeFormatter FORMATO_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    /** Uma reserva cujo evento chegou depois da expiração expira logo em seguida, não no passado. */
    private static final Duration MARGEM_MINIMA = Duration.ofMinutes(1);

    private final SchedulerClient scheduler;
    private final String grupo;
    private final String filaArn;
    private final String papelArn;
    private final Clock relogio;

    public SchedulerAgendaDeExpiracao(SchedulerClient scheduler, String grupo, String filaArn, String papelArn,
                                      Clock relogio) {
        this.scheduler = scheduler;
        this.grupo = grupo;
        this.filaArn = filaArn;
        this.papelArn = papelArn;
        this.relogio = relogio;
    }

    @Override
    public void agendar(String idReserva, String idContrato, Instant expiraEm) {
        try {
            scheduler.createSchedule(pedido -> pedido
                    .name(nome(idReserva))
                    .groupName(grupo)
                    .scheduleExpression(expressao(expiraEm))
                    .scheduleExpressionTimezone("UTC")
                    .flexibleTimeWindow(janela -> janela.mode(FlexibleTimeWindowMode.OFF))
                    .actionAfterCompletion(ActionAfterCompletion.DELETE)
                    .target(alvo -> alvo.arn(filaArn).roleArn(papelArn)
                            .input("{\"id_autorizacao\":\"" + idReserva + "\",\"id_contrato\":\"" + idContrato + "\"}")));
        } catch (ConflictException jaAgendada) {
            // reentrega do evento: a expiração desta reserva já está agendada
        }
    }

    static String nome(String idReserva) {
        return "reserva-" + idReserva;
    }

    String expressao(Instant expiraEm) {
        Instant minimo = relogio.instant().plus(MARGEM_MINIMA);
        return "at(" + FORMATO_AT.format(expiraEm.isBefore(minimo) ? minimo : expiraEm) + ")";
    }
}
