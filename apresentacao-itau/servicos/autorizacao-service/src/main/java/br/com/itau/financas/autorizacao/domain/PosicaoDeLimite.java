package br.com.itau.financas.autorizacao.domain;

import br.com.itau.financas.kernel.Dinheiro;

import java.util.Objects;

/**
 * Fotografia da posição do contrato devolvida pelo contexto Limites após a operação.
 * {@code saldoReservado} = limite comprometido após a operação (campo do PDF);
 * {@code limiteDisponivel} = quanto ainda pode ser autorizado.
 */
public record PosicaoDeLimite(Dinheiro saldoReservado, Dinheiro limiteDisponivel) {

    public PosicaoDeLimite {
        Objects.requireNonNull(saldoReservado, "saldoReservado é obrigatório");
        Objects.requireNonNull(limiteDisponivel, "limiteDisponivel é obrigatório");
    }
}
