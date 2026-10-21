package br.com.itau.financas.autorizacao.domain;

/**
 * Ciclo de vida da autorização. Não há estado PENDENTE persistido (ver ADR 027).
 * <pre>
 *   DEBITO / CAPTURA:  APROVADA → ESTORNADA_PARCIALMENTE → ESTORNADA
 *   RESERVA:           APROVADA (há saldo bloqueado) → ENCERRADA (tudo capturado e/ou liberado, inclusive por expiração)
 *   qualquer tipo:     RECUSADA (limite insuficiente)
 * </pre>
 */
public enum EstadoAutorizacao {
    APROVADA,
    RECUSADA,
    ESTORNADA_PARCIALMENTE,
    ESTORNADA,
    ENCERRADA;

    public boolean aceitaEstorno() {
        return this == APROVADA || this == ESTORNADA_PARCIALMENTE;
    }
}
