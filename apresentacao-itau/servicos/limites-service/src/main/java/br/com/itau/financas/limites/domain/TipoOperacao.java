package br.com.itau.financas.limites.domain;

/**
 * Efeito de cada tipo de operação sobre a posição (ADR 021 e ADR 035). A reserva segue o {@code AmountBlock} do BIAN
 * Position Keeping: bloqueia o valor (pendente) até a captura (final) ou a liberação/expiração.
 */
public enum TipoOperacao {
    DEBITO(Efeito.CONSOME),
    RESERVA(Efeito.BLOQUEIA),
    CAPTURA(Efeito.CAPTURA),
    LIBERACAO(Efeito.LIBERA),
    CREDITO(Efeito.DEVOLVE),
    ESTORNO(Efeito.DEVOLVE);

    public enum Efeito {
        /** disponível → utilizado */
        CONSOME,
        /** disponível → bloqueado */
        BLOQUEIA,
        /** bloqueado → utilizado */
        CAPTURA,
        /** bloqueado → disponível */
        LIBERA,
        /** utilizado → disponível */
        DEVOLVE
    }

    private final Efeito efeito;

    TipoOperacao(Efeito efeito) {
        this.efeito = efeito;
    }

    public Efeito efeito() {
        return efeito;
    }

    public static TipoOperacao de(String texto) {
        try {
            return TipoOperacao.valueOf(texto);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RegraDeNegocioVioladaException("tipo-operacao-invalido", "tipo_operacao inválido: " + texto);
        }
    }
}
