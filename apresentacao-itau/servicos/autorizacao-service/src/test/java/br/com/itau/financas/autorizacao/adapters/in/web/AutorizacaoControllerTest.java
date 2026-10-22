package br.com.itau.financas.autorizacao.adapters.in.web;

import br.com.itau.financas.autorizacao.application.ConflitoDeConcorrenciaException;
import br.com.itau.financas.autorizacao.application.LimitesIndisponivelException;
import br.com.itau.financas.autorizacao.application.ResultadoAutorizacao;
import br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacao;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContaId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.autorizacao.domain.ImpressaoDigital;
import br.com.itau.financas.autorizacao.domain.NovaAutorizacao;
import br.com.itau.financas.autorizacao.domain.PosicaoDeLimite;
import br.com.itau.financas.autorizacao.domain.Rastreio;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;
import br.com.itau.financas.kernel.Dinheiro;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP do PDF §2.3: cada resultado do caso de uso vira o status e o Problem Details certos. */
class AutorizacaoControllerTest {

    private static final String CORPO = """
            {"id_conta":"0001-123","valor":100.00,"moeda":"BRL","tipo_operacao":"DEBITO"}
            """;

    private Supplier<ResultadoAutorizacao> resposta;

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AutorizacaoController(solicitacao -> resposta.get()))
            .setControllerAdvice(new ProblemDetailsHandler())
            .addFilters(new CorrelationIdFilter())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build()))
            .build();

    @Test
    void autorizacaoNovaResponde201ComLocation() throws Exception {
        resposta = () -> new ResultadoAutorizacao(aprovada(), true);

        enviar(CORPO).andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.saldo_reservado").value("100.00"));
    }

    @Test
    void repeticaoIdempotenteResponde200() throws Exception {
        resposta = () -> new ResultadoAutorizacao(aprovada(), false);

        enviar(CORPO).andExpect(status().isOk());
    }

    @Test
    void recusaResponde402EmProblemDetails() throws Exception {
        resposta = () -> new ResultadoAutorizacao(Autorizacao.recusada(pedido(),
                Autorizacao.MotivoRecusa.LIMITE_INSUFICIENTE, posicao(), Instant.now()), true);

        enviar(CORPO).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.codigo").value("limite-insuficiente"))
                .andExpect(jsonPath("$.correlation_id").exists());
    }

    @Test
    void conflitoResponde409() throws Exception {
        resposta = () -> {
            throw new ConflitoDeConcorrenciaException("concorrência");
        };

        enviar(CORPO).andExpect(status().isConflict());
    }

    @Test
    void circuitoAbertoResponde503ComRetryAfter() throws Exception {
        resposta = () -> {
            throw new LimitesIndisponivelException("aberto", false, null);
        };

        enviar(CORPO).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void payloadInvalidoResponde422() throws Exception {
        enviar("""
                {"id_conta":"","valor":-1,"moeda":"real","tipo_operacao":"SAQUE"}
                """).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("payload-invalido"));
    }

    private ResultActions enviar(String corpo) throws Exception {
        return mvc.perform(post("/v1/contratos/CTR-1/autorizacoes")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .principal(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("teste")
                                .header("alg", "RS256").claim("client_id", "canal-app").build()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private static Autorizacao aprovada() {
        return Autorizacao.aprovada(pedido(), posicao(), Instant.now());
    }

    private static NovaAutorizacao pedido() {
        return new NovaAutorizacao(new AutorizacaoId(UUID.randomUUID()), new ContratoId("CTR-1"),
                new ContaId("0001-123"), TipoOperacao.DEBITO, Dinheiro.de("100.00", "BRL"), ImpressaoDigital.de("x"),
                null, null, new Rastreio("corr", null));
    }

    private static PosicaoDeLimite posicao() {
        return new PosicaoDeLimite(Dinheiro.de("100.00", "BRL"), Dinheiro.de("900.00", "BRL"));
    }
}
