package br.com.itau.financas.limites.domain;

/** 422 — violação de regra de domínio. */
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
