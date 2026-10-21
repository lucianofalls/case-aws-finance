package br.com.itau.financas.autorizacao.application.port.in;

import br.com.itau.financas.autorizacao.application.ResultadoAutorizacao;
import br.com.itau.financas.autorizacao.application.SolicitacaoDeAutorizacao;

/** Caso de uso — BIAN Action Term "Evaluate" sobre Transaction Authorization. */
public interface AutorizarTransacao {

    ResultadoAutorizacao executar(SolicitacaoDeAutorizacao solicitacao);
}
