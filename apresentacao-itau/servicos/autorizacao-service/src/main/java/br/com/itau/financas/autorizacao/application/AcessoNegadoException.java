package br.com.itau.financas.autorizacao.application;

/** Nunca revela a existência ou titularidade de um contrato a um cliente não autorizado. */
public final class AcessoNegadoException extends RuntimeException {
    public AcessoNegadoException() {
        super("cliente sem acesso autorizado ao contrato ou conta");
    }
}
