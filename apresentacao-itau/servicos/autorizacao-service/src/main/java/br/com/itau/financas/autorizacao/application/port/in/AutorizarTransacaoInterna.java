package br.com.itau.financas.autorizacao.application.port.in;

import br.com.itau.financas.autorizacao.application.ResultadoAutorizacao;
import br.com.itau.financas.autorizacao.application.SolicitacaoDeAutorizacao;

/** Porta PRIVADA para comando do EventBridge Scheduler/SQS; não exportada por controller HTTP.
 * O consumidor valida a reserva original no repositório antes de chamar esta porta.
 */
@FunctionalInterface
public interface AutorizarTransacaoInterna {
    ResultadoAutorizacao executarInternamente(SolicitacaoDeAutorizacao comando);
}
