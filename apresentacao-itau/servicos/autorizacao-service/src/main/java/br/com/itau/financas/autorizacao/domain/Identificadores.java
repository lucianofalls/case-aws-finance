package br.com.itau.financas.autorizacao.domain;

/** Identificadores externos usados pela Autorização. Validação mínima: o dono do formato é o core banking. */
public final class Identificadores {

    private static final int TAMANHO_MAXIMO = 64;

    private Identificadores() {
    }

    public record ContratoId(String valor) {
        public ContratoId {
            valor = exigirTexto(valor, "id_contrato");
        }
    }

    public record ContaId(String valor) {
        public ContaId {
            valor = exigirTexto(valor, "id_conta");
        }
    }

    static String exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new RegraDeNegocioVioladaException("campo-obrigatorio", campo + " é obrigatório");
        }
        if (valor.length() > TAMANHO_MAXIMO) {
            throw new RegraDeNegocioVioladaException("campo-invalido", campo + " excede " + TAMANHO_MAXIMO + " caracteres");
        }
        return valor.trim();
    }
}
