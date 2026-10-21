package br.com.itau.financas.contabilizacao.domain;

import br.com.itau.financas.contabilizacao.domain.Lancamento.Natureza;
import br.com.itau.financas.kernel.Dinheiro;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanoDeContasTest {

    private final PlanoDeContas plano = new PlanoDeContas();

    @Test
    void debitoGeraPartidaDobradaClientesAReceberContraEstabelecimentos() {
        RegistroContabil registro = plano.contabilizar(fato(FatoContabil.Tipo.TRANSACAO_AUTORIZADA, "DEBITO"))
                .orElseThrow();

        assertThat(registro.lancamentos()).extracting(Lancamento::conta, Lancamento::natureza).containsExactly(
                org.assertj.core.groups.Tuple.tuple(PlanoDeContas.CLIENTES_A_RECEBER, Natureza.DEVEDORA),
                org.assertj.core.groups.Tuple.tuple(PlanoDeContas.OBRIGACOES_COM_ESTABELECIMENTOS, Natureza.CREDORA));
    }

    @Test
    void reservaNaoEhFatoContabil() {
        assertThat(plano.contabilizar(fato(FatoContabil.Tipo.TRANSACAO_AUTORIZADA, "RESERVA"))).isEmpty();
    }

    @Test
    void capturaDaReservaGeraOsMesmosLancamentosDoDebito() {
        var captura = plano.contabilizar(fato(FatoContabil.Tipo.TRANSACAO_AUTORIZADA, "CAPTURA")).orElseThrow();
        var debito = plano.contabilizar(fato(FatoContabil.Tipo.TRANSACAO_AUTORIZADA, "DEBITO")).orElseThrow();

        assertThat(captura.lancamentos()).isEqualTo(debito.lancamentos());
    }

    @Test
    void liberacaoDaReservaNaoEhFatoContabil() {
        assertThat(plano.contabilizar(fato(FatoContabil.Tipo.TRANSACAO_AUTORIZADA, "LIBERACAO"))).isEmpty();
    }

    @Test
    void dataContabilUsaOFusoDeSaoPaulo() {
        FatoContabil fatoAsDuasDaManhaUtc = new FatoContabil(UUID.randomUUID(), FatoContabil.Tipo.ESTORNO_REALIZADO,
                "AUT", "CTR", "ESTORNO", Dinheiro.de("1.00", "BRL"), Instant.parse("2026-10-21T02:00:00Z"));

        assertThat(plano.contabilizar(fatoAsDuasDaManhaUtc).orElseThrow().dataContabil())
                .isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void registroDesbalanceadoEhRejeitado() {
        assertThatThrownBy(() -> new RegistroContabil(UUID.randomUUID(), "AUT", "CTR", LocalDate.now(), List.of(
                new Lancamento(PlanoDeContas.CLIENTES_A_RECEBER, Natureza.DEVEDORA, Dinheiro.de("10.00", "BRL")),
                new Lancamento(PlanoDeContas.DISPONIBILIDADES, Natureza.CREDORA, Dinheiro.de("9.99", "BRL")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desbalanceadas");
    }

    private static FatoContabil fato(FatoContabil.Tipo tipo, String tipoOperacao) {
        return new FatoContabil(UUID.randomUUID(), tipo, "AUT-1", "CTR-1", tipoOperacao, Dinheiro.de("150.00", "BRL"),
                Instant.parse("2026-10-20T15:00:00Z"));
    }
}
