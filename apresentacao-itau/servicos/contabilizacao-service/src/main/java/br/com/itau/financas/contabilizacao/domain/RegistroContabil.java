package br.com.itau.financas.contabilizacao.domain;

import br.com.itau.financas.contabilizacao.domain.Lancamento.Natureza;
import br.com.itau.financas.kernel.Dinheiro;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregado Registro Contábil — Control Record "Financial Booking Log" do BIAN Financial Accounting.
 * Invariante das partidas dobradas: Σ lançamentos devedores = Σ lançamentos credores (mesma moeda).
 * Append-only: um fato contábil nunca é alterado; correção é um novo registro (estorno contábil).
 */
public record RegistroContabil(UUID fatoId, String idAutorizacao, String idContrato, LocalDate dataContabil,
                               List<Lancamento> lancamentos) {

    public RegistroContabil {
        Objects.requireNonNull(fatoId);
        Objects.requireNonNull(idAutorizacao);
        Objects.requireNonNull(idContrato);
        Objects.requireNonNull(dataContabil);
        lancamentos = List.copyOf(lancamentos);
        if (lancamentos.size() < 2) {
            throw new IllegalArgumentException("partida dobrada exige ao menos 2 lançamentos");
        }
        Dinheiro devedor = soma(lancamentos, Natureza.DEVEDORA);
        Dinheiro credor = soma(lancamentos, Natureza.CREDORA);
        if (devedor.compareTo(credor) != 0) {
            throw new IllegalArgumentException("partidas dobradas desbalanceadas: D " + devedor + " ≠ C " + credor);
        }
    }

    private static Dinheiro soma(List<Lancamento> lancamentos, Natureza natureza) {
        Dinheiro zero = Dinheiro.zero(lancamentos.getFirst().valor().moeda());
        return lancamentos.stream()
                .filter(l -> l.natureza() == natureza)
                .map(Lancamento::valor)
                .reduce(zero, Dinheiro::somar);
    }
}
