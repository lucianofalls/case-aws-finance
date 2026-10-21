package br.com.itau.financas.autorizacao.application;

import java.math.BigDecimal;

/** Comando de entrada do caso de uso, ainda com tipos primitivos (vem do adapter HTTP). */
public record SolicitacaoDeAutorizacao(
        String clienteId,
        String idempotencyKey,
        String idContrato,
        String idConta,
        BigDecimal valor,
        String moeda,
        String tipoOperacao,
        String idEstabelecimento,
        String idAutorizacaoOriginal,
        String correlationId,
        String traceparent) {
}
