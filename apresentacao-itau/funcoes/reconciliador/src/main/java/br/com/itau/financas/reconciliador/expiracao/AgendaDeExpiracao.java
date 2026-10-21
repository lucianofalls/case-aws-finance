package br.com.itau.financas.reconciliador.expiracao;

import java.time.Instant;

/** Agenda a expiração de uma reserva. Idempotente: agendar duas vezes a mesma reserva não cria duas expirações. */
public interface AgendaDeExpiracao {

    void agendar(String idReserva, String idContrato, Instant expiraEm);
}
