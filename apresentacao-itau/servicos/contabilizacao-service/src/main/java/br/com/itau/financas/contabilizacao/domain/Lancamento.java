package br.com.itau.financas.contabilizacao.domain;

import br.com.itau.financas.kernel.Dinheiro;

import java.util.Objects;

/** Behavior Qualifier "Ledger Posting" do BIAN Financial Accounting: uma perna da partida dobrada. */
public record Lancamento(ContaContabil conta, Natureza natureza, Dinheiro valor) {

    public enum Natureza { DEVEDORA, CREDORA }

    public record ContaContabil(String codigo, String nome) {
        public ContaContabil {
            Objects.requireNonNull(codigo);
            Objects.requireNonNull(nome);
        }
    }

    public Lancamento {
        Objects.requireNonNull(conta);
        Objects.requireNonNull(natureza);
        Objects.requireNonNull(valor);
        if (!valor.ehPositivo()) {
            throw new IllegalArgumentException("lançamento deve ter valor positivo");
        }
    }
}
