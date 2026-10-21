package br.com.itau.financas.autorizacao.adapters.in.web;

import br.com.itau.financas.autorizacao.domain.Autorizacao;

/** Resposta 201/200. Valores monetários como string decimal. Campos da reserva só para RESERVA (ADR 035). */
public record AutorizacaoResponse(
        String idAutorizacao,
        String estado,
        String tipoOperacao,
        String valor,
        String moeda,
        String saldoReservado,
        String limiteDisponivel,
        String criadaEm,
        String saldoDaReserva,
        String reservaExpiraEm) {

    static AutorizacaoResponse de(Autorizacao autorizacao) {
        return new AutorizacaoResponse(
                autorizacao.id().toString(),
                autorizacao.estado().name(),
                autorizacao.tipo().name(),
                autorizacao.valor().comoTexto(),
                autorizacao.valor().codigoMoeda(),
                autorizacao.posicao().saldoReservado().comoTexto(),
                autorizacao.posicao().limiteDisponivel().comoTexto(),
                autorizacao.criadaEm().toString(),
                autorizacao.reserva().map(r -> autorizacao.saldoDaReserva().comoTexto()).orElse(null),
                autorizacao.reserva().map(r -> r.expiraEm().toString()).orElse(null));
    }
}
