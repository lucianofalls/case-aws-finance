package br.com.itau.financas.autorizacao.domain;

/**
 * Tipo de operação do contrato da API (PDF §2.3), com o ciclo da reserva do BIAN Position Keeping (AmountBlock) e da
 * indústria de cartões (pré-autorização → captura/liberação; ISO 20022 {@code caaa}). Semântica (ADR 021, ADR 035):
 * <ul>
 *   <li>DEBITO — consome limite imediatamente</li>
 *   <li>RESERVA — bloqueia limite (pré-autorização) até a captura, a liberação ou a expiração</li>
 *   <li>CAPTURA — efetiva, total ou parcialmente, uma RESERVA (o bloqueado passa a utilizado)</li>
 *   <li>LIBERACAO — devolve ao disponível, total ou parcialmente, o saldo bloqueado de uma RESERVA</li>
 *   <li>CREDITO — devolve limite (pagamento de fatura/parcela), até o valor utilizado</li>
 *   <li>ESTORNO — desfaz, total ou parcialmente, um DEBITO ou uma CAPTURA</li>
 * </ul>
 * CAPTURA, LIBERACAO e ESTORNO referenciam a autorização original em {@code metadata.id_autorizacao_original}.
 */
public enum TipoOperacao {
    DEBITO,
    RESERVA,
    CAPTURA,
    LIBERACAO,
    CREDITO,
    ESTORNO;

    /** Operações que retiram do disponível e, por isso, podem ser recusadas por limite insuficiente (402). */
    public boolean retiraDoDisponivel() {
        return this == DEBITO || this == RESERVA;
    }

    /** Operações cujo valor efetivamente utilizado pode ser estornado. */
    public boolean aceitaEstorno() {
        return this == DEBITO || this == CAPTURA;
    }

    /** Movimentos sobre o saldo bloqueado de uma RESERVA. */
    public boolean movimentaReserva() {
        return this == CAPTURA || this == LIBERACAO;
    }

    public boolean exigeOriginal() {
        return this == ESTORNO || movimentaReserva();
    }

    public static TipoOperacao de(String texto) {
        try {
            return TipoOperacao.valueOf(texto);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RegraDeNegocioVioladaException("tipo-operacao-invalido",
                    "tipo_operacao deve ser DEBITO, CREDITO, RESERVA, CAPTURA, LIBERACAO ou ESTORNO");
        }
    }
}
