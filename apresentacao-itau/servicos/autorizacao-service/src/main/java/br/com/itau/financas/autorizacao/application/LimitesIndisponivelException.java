package br.com.itau.financas.autorizacao.application;

/**
 * Contexto Limites indisponível → HTTP 503 (fail-fast).
 *
 * @see #resultadoIncerto()
 */
public class LimitesIndisponivelException extends RuntimeException {

    private final boolean resultadoIncerto;

    public LimitesIndisponivelException(String mensagem, boolean resultadoIncerto, Throwable causa) {
        super(mensagem, causa);
        this.resultadoIncerto = resultadoIncerto;
    }

    /**
     * true quando o pedido pode ter sido aplicado (timeout de leitura, 5xx); false quando com certeza
     * não saiu (circuit breaker aberto, conexão recusada). Só o caso incerto agenda verificação posterior.
     */
    public boolean resultadoIncerto() {
        return resultadoIncerto;
    }
}
