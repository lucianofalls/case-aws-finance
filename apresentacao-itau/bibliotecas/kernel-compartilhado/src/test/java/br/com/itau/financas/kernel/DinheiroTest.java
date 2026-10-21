package br.com.itau.financas.kernel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DinheiroTest {

    @Test
    void normalizaParaDuasCasasDecimais() {
        assertThat(Dinheiro.de("10", "BRL").comoTexto()).isEqualTo("10.00");
    }

    @Test
    void rejeitaMaisDeDuasCasasDecimais() {
        assertThatThrownBy(() -> Dinheiro.de("10.001", "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void naoUsaPontoFlutuanteNaSoma() {
        Dinheiro soma = Dinheiro.de("0.10", "BRL").somar(Dinheiro.de("0.20", "BRL"));

        assertThat(soma.comoTexto()).isEqualTo("0.30");
    }

    @Test
    void proibeOperacaoEntreMoedasDiferentes() {
        assertThatThrownBy(() -> Dinheiro.de("1", "BRL").somar(Dinheiro.de("1", "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejeitaValorAcimaDeDecimal18Com2() {
        assertThatThrownBy(() -> Dinheiro.de("12345678901234567.00", "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
