package br.com.itau.financas.reconciliador.adapters;

import br.com.itau.financas.reconciliador.AgendarVerificacoes.OperacaoGravada;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Garantia: qualquer identificador aceito no domínio gera JSON válido para o reconciliador. */
class SqsFilaDeVerificacaoTest {

    @Test
    void serializaIdentificadorComAspasSemQuebrarMensagem() throws Exception {
        String contrato = "CTR-\"test\"";
        var operacao = new OperacaoGravada("seq-123", contrato,
                "e9af3197-3fb7-40f8-b3b1-c28d2421e76e");

        var mensagem = SqsFilaDeVerificacao.entrada(0, operacao);
        var json = new ObjectMapper().readTree(mensagem.messageBody());
        assertThat(json.path("id_contrato").asText()).isEqualTo(contrato);
        assertThat(json.path("id_autorizacao").asText()).isEqualTo(operacao.idOperacao());
        assertThat(mensagem.id()).isEqualTo("0");
    }
}
