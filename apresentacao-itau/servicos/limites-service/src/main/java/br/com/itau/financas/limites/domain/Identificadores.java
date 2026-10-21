package br.com.itau.financas.limites.domain;

import java.util.Objects;
import java.util.UUID;

/** Identificadores do contexto Limites. */
public final class Identificadores {

    private Identificadores() {
    }

    public record ContratoId(String valor) {
        public ContratoId {
            if (valor == null || valor.isBlank() || valor.length() > 64) {
                throw new RegraDeNegocioVioladaException("contrato-invalido", "id_contrato inválido");
            }
        }
    }

    /** Id da operação = id da autorização no contexto Autorização (chave de idempotência). */
    public record OperacaoId(UUID valor) {
        public OperacaoId {
            Objects.requireNonNull(valor);
        }

        public static OperacaoId de(String texto) {
            try {
                return new OperacaoId(UUID.fromString(texto));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new RegraDeNegocioVioladaException("id-operacao-invalido", "id_operacao deve ser UUID");
            }
        }

        @Override
        public String toString() {
            return valor.toString();
        }
    }
}
