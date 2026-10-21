package br.com.itau.financas.autorizacao.application;

import br.com.itau.financas.autorizacao.domain.Autorizacao;

/**
 * @param criadaAgora false quando a resposta é a repetição idempotente de uma decisão já tomada
 */
public record ResultadoAutorizacao(Autorizacao autorizacao, boolean criadaAgora) {
}
