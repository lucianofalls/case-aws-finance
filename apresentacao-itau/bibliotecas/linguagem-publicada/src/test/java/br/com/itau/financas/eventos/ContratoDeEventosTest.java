package br.com.itau.financas.eventos;

import br.com.itau.financas.eventos.v1.PayloadsV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de contrato: o que o código publica obedece aos JSON Schemas de contracts/schemas/eventos
 * (os mesmos registrados no Glue Schema Registry com compatibilidade FULL pelo Terraform).
 */
class ContratoDeEventosTest {

    private static final Path SCHEMAS = Path.of("../../contracts/schemas/eventos");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ID = UUID.randomUUID().toString();

    static Stream<Arguments> eventos() {
        return Stream.of(
                Arguments.of("transacao-autorizada-v1", TiposDeEvento.TRANSACAO_AUTORIZADA,
                        new PayloadsV1.TransacaoAutorizada(ID, "CTR-1", "0001-1", "DEBITO", "1500.00", "BRL",
                                "1500.00", "3500.00", "EST-1")),
                Arguments.of("transacao-autorizada-v1", TiposDeEvento.TRANSACAO_AUTORIZADA,
                        new PayloadsV1.TransacaoAutorizada(ID, "CTR-1", "0001-1", "RESERVA", "400.00", "BRL",
                                "400.00", "600.00", null, null, "2026-10-27T15:00:00Z")),
                Arguments.of("transacao-autorizada-v1", TiposDeEvento.TRANSACAO_AUTORIZADA,
                        new PayloadsV1.TransacaoAutorizada(UUID.randomUUID().toString(), "CTR-1", "0001-1",
                                "CAPTURA", "250.00", "BRL", "250.00", "750.00", "EST-1", ID, null)),
                Arguments.of("transacao-recusada-v1", TiposDeEvento.TRANSACAO_RECUSADA,
                        new PayloadsV1.TransacaoRecusada(ID, "CTR-1", "0001-1", "DEBITO", "9000.00", "BRL",
                                "LIMITE_INSUFICIENTE", "3500.00")),
                Arguments.of("estorno-realizado-v1", TiposDeEvento.ESTORNO_REALIZADO,
                        new PayloadsV1.EstornoRealizado(ID, UUID.randomUUID().toString(), "CTR-1", "0001-1",
                                "300.00", "BRL", "3800.00")),
                Arguments.of("limite-concedido-v1", TiposDeEvento.LIMITE_CONCEDIDO,
                        new PayloadsV1.LimiteConcedido("CTR-1", "0001-1", "CONSORCIO", "15000.00", "BRL", 42)),
                Arguments.of("limite-alterado-v1", TiposDeEvento.LIMITE_ALTERADO,
                        new PayloadsV1.LimiteAlterado("CTR-1", "1000.00", "500.00", "-200.00", "BRL", 2)),
                Arguments.of("operacao-revertida-v1", TiposDeEvento.OPERACAO_REVERTIDA,
                        new PayloadsV1.OperacaoRevertida("CTR-1", ID, "DEBITO", "30.00", "BRL", "100.00")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventos")
    void eventoPublicadoObedeceAoSchemaRegistrado(String schema, String tipo, Object payload) throws IOException {
        CloudEvent evento = CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO, tipo,
                Instant.parse("2026-10-20T15:00:00Z"), "contrato/CTR-1", "CTR-1", "corr-1", null, payload);
        JsonNode json = JSON.readTree(CodecDeEventos.paraJson(evento));

        Set<ValidationMessage> violacoes = carregar(schema).validate(json);

        assertThat(violacoes).as("violações do contrato %s", schema).isEmpty();
    }

    @Test
    void eventoSemCorrelacaoRecebidaAbreCadeiaComOProprioId() throws IOException {
        CloudEvent evento = CodecDeEventos.novo(ID, TiposDeEvento.FONTE_LIMITES, TiposDeEvento.LIMITE_ALTERADO,
                Instant.now(), "contrato/CTR-1", "CTR-1", null, null,
                new PayloadsV1.LimiteAlterado("CTR-1", "1000.00", "500.00", "-200.00", "BRL", 2));

        Set<ValidationMessage> violacoes = carregar("limite-alterado-v1")
                .validate(JSON.readTree(CodecDeEventos.paraJson(evento)));

        assertThat(evento.correlationid()).isEqualTo(ID);
        assertThat(violacoes).isEmpty();
    }

    @Test
    void valorMonetarioForaDoFormatoViolaOContrato() throws IOException {
        CloudEvent evento = CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO, TiposDeEvento.TRANSACAO_AUTORIZADA,
                Instant.now(), "contrato/CTR-1", "CTR-1", "corr-1", null,
                new PayloadsV1.TransacaoAutorizada(ID, "CTR-1", "0001-1", "DEBITO", "1500", "BRL", "1500", "0", null));

        Set<ValidationMessage> violacoes = carregar("transacao-autorizada-v1")
                .validate(JSON.readTree(CodecDeEventos.paraJson(evento)));

        assertThat(violacoes).isNotEmpty();
    }

    private static JsonSchema carregar(String nome) throws IOException {
        String conteudo = Files.readString(SCHEMAS.resolve(nome + ".json"));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(conteudo);
    }
}
