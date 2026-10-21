package br.com.itau.financas.reconciliador;

import br.com.itau.financas.reconciliador.VerificarAutorizacaoIncerta.Desfecho;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificarAutorizacaoIncertaTest {

    private final List<String> reversoes = new ArrayList<>();

    @Test
    void autorizacaoRegistradaNaoTocaNoLimite() {
        var casoDeUso = new VerificarAutorizacaoIncerta(id -> true, this::reverter);

        assertThat(casoDeUso.verificar("AUT-1", "CTR-1")).isEqualTo(Desfecho.AUTORIZACAO_REGISTRADA);
        assertThat(reversoes).isEmpty();
    }

    @Test
    void autorizacaoAusenteRevertOperacaoOrfa() {
        var casoDeUso = new VerificarAutorizacaoIncerta(id -> false, this::reverter);

        assertThat(casoDeUso.verificar("AUT-1", "CTR-1")).isEqualTo(Desfecho.OPERACAO_REVERTIDA);
        assertThat(reversoes).containsExactly("CTR-1/AUT-1");
    }

    @Test
    void operacaoQueNuncaChegouAoLimiteNaoTemNadaAReverter() {
        var casoDeUso = new VerificarAutorizacaoIncerta(id -> false, (contrato, id) -> "NAO_ENCONTRADA");

        assertThat(casoDeUso.verificar("AUT-1", "CTR-1")).isEqualTo(Desfecho.NADA_A_REVERTER);
    }

    private String reverter(String contrato, String id) {
        reversoes.add(contrato + "/" + id);
        return "REVERTIDA";
    }
}
