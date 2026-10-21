package br.com.itau.financas.limites.domain;

/** Exceções de domínio do contexto Limites, cada uma com significado HTTP explícito no adapter. */
public final class Excecoes {

    private Excecoes() {
    }

    /** 402 — a invariante {@code disponivel >= valor} impediu a operação. */
    public static final class LimiteInsuficienteException extends RuntimeException {
        private final Posicao posicao;

        public LimiteInsuficienteException(Posicao posicao) {
            super("limite disponível insuficiente no contrato " + posicao.contratoId().valor());
            this.posicao = posicao;
        }

        public Posicao posicao() {
            return posicao;
        }
    }

    /** 404 — contrato ainda não sincronizado do core banking. */
    public static final class ContratoSemLimiteException extends RuntimeException {
        public ContratoSemLimiteException(Identificadores.ContratoId contrato) {
            super("contrato " + contrato.valor() + " sem limite cadastrado");
        }
    }

    /**
     * 409 (codigo operacao-encerrada) — a operação foi revertida como órfã ou a repetição chegou depois da
     * janela de repetição. Janela de repetição menor que o atraso do reconciliador elimina a corrida entre a
     * retentativa do cliente e a reversão (ADR 027). O cliente deve usar uma nova Idempotency-Key.
     */
    public static final class OperacaoEncerradaException extends RuntimeException {
        public OperacaoEncerradaException(Identificadores.OperacaoId id, String motivo) {
            super("operação " + id + " encerrada: " + motivo);
        }
    }

    /** Evento do legado mais antigo que o estado atual: ignorado (tolerância a reordenação). */
    public static final class VersaoObsoletaException extends RuntimeException {
        public VersaoObsoletaException(long recebida, long atual) {
            super("versão de origem " + recebida + " não é mais nova que " + atual);
        }
    }
}
