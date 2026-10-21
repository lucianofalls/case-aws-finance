package br.com.itau.financas.eventos.v1;

/**
 * Payloads v1 da linguagem publicada. Valores monetários são strings decimais (nunca float).
 * Regra de evolução (schema registry com compatibilidade FULL): só adicionar ou remover campos opcionais.
 */
public final class PayloadsV1 {

    private PayloadsV1() {
    }

    public record TransacaoAutorizada(
            String idAutorizacao,
            String idContrato,
            String idConta,
            String tipoOperacao,
            String valor,
            String moeda,
            String saldoReservado,
            String limiteDisponivel,
            String idEstabelecimento,
            String idAutorizacaoOriginal,
            String reservaExpiraEm) {

        /**
         * Fato sem autorização original (DEBITO, CREDITO). CAPTURA e LIBERACAO informam a reserva em
         * {@code idAutorizacaoOriginal}; a RESERVA informa {@code reservaExpiraEm} (ADR 035).
         */
        public TransacaoAutorizada(String idAutorizacao, String idContrato, String idConta, String tipoOperacao,
                                   String valor, String moeda, String saldoReservado, String limiteDisponivel,
                                   String idEstabelecimento) {
            this(idAutorizacao, idContrato, idConta, tipoOperacao, valor, moeda, saldoReservado, limiteDisponivel,
                    idEstabelecimento, null, null);
        }
    }

    public record TransacaoRecusada(
            String idAutorizacao,
            String idContrato,
            String idConta,
            String tipoOperacao,
            String valor,
            String moeda,
            String motivo,
            String limiteDisponivel) {
    }

    public record EstornoRealizado(
            String idAutorizacao,
            String idAutorizacaoOriginal,
            String idContrato,
            String idConta,
            String valor,
            String moeda,
            String limiteDisponivel) {
    }

    public record LimiteConcedido(
            String idContrato,
            String idConta,
            String produto,
            String limiteConcedido,
            String moeda,
            long versaoOrigem) {
    }

    public record LimiteAlterado(
            String idContrato,
            String limiteAnterior,
            String limiteNovo,
            String limiteDisponivel,
            String moeda,
            long versaoOrigem) {
    }

    /** Operação órfã (aplicada no limite sem autorização registrada) revertida pelo reconciliador. */
    public record OperacaoRevertida(
            String idContrato,
            String idOperacao,
            String tipoOperacao,
            String valor,
            String moeda,
            String limiteDisponivel) {
    }
}
