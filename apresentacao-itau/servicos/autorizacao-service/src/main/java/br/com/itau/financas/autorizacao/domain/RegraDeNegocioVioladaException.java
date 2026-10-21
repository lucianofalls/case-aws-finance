package br.com.itau.financas.autorizacao.domain;

/** Violação de regra de domínio ou de validação do contrato → HTTP 422. */
public class RegraDeNegocioVioladaException extends RuntimeException {

    private final String codigo;

    public RegraDeNegocioVioladaException(String codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
