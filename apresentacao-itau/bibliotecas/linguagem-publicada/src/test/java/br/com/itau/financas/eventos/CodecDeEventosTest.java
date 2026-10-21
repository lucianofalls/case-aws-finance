package br.com.itau.financas.eventos;

import br.com.itau.financas.eventos.v1.PayloadsV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CodecDeEventosTest {

    @Test
    void serializaEnvelopeCloudEventsComPayloadEmSnakeCase() {
        CloudEvent evento = CodecDeEventos.novo("evt-1", TiposDeEvento.FONTE_AUTORIZACAO,
                TiposDeEvento.TRANSACAO_AUTORIZADA, Instant.parse("2026-10-20T14:32:10Z"), "contrato/CTR-1",
                "CTR-1", "corr-1", null,
                new PayloadsV1.TransacaoAutorizada("AUT-1", "CTR-1", "0001-1", "DEBITO", "1500.00", "BRL",
                        "1500.00", "3500.00", null));

        String json = CodecDeEventos.paraJson(evento);

        assertThat(json).contains("\"specversion\":\"1.0\"", "\"correlationid\":\"corr-1\"",
                "\"id_autorizacao\":\"AUT-1\"", "\"valor\":\"1500.00\"");
        assertThat(json).doesNotContain("traceparent");
    }

    @Test
    void consumidorToleraCampoNovoDesconhecido() {
        String json = """
                {"specversion":"1.0","id":"e1","source":"s","type":"t","time":"2026-10-20T00:00:00Z",
                 "data":{"id_contrato":"CTR-1","limite_anterior":"1.00","limite_novo":"2.00",
                         "limite_disponivel":"2.00","moeda":"BRL","versao_origem":3,"campo_futuro":"x"}}
                """;

        PayloadsV1.LimiteAlterado payload =
                CodecDeEventos.payloadDe(CodecDeEventos.deJson(json), PayloadsV1.LimiteAlterado.class);

        assertThat(payload.versaoOrigem()).isEqualTo(3);
        assertThat(payload.limiteNovo()).isEqualTo("2.00");
    }

    @Test
    void geraUriDeSchemaPorTipo() {
        assertThat(TiposDeEvento.schemaDe(TiposDeEvento.TRANSACAO_AUTORIZADA))
                .isEqualTo("https://schemas.itau.com.br/financas/autorizacao/transacao-autorizada/v1.json");
    }
}
