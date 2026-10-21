package br.com.itau.financas.autorizacao.application;

/**
 * A operação desta Idempotency-Key foi encerrada no Limites: revertida pelo reconciliador ou repetida fora da janela.
 * → HTTP 409: o cliente deve enviar uma nova Idempotency-Key.
 */
public class OperacaoEncerradaException extends RuntimeException {

    public OperacaoEncerradaException(String mensagem) {
        super(mensagem);
    }
}
