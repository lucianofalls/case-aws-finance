package br.com.itau.financas.autorizacao.config;

import br.com.itau.financas.autorizacao.adapters.in.web.*;
import br.com.itau.financas.autorizacao.application.*;
import br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacao;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class SegurancaConfigTest {
    private static RSAKey chave;
    private static Path arquivo;
    private AnnotationConfigWebApplicationContext contexto;
    private MockMvc mvc;
    private AutorizarTransacao autorizar;
    private static final String ISSUER = "https://emissor.exemplo";

    @BeforeAll static void chaveLocal() throws Exception {
        chave = new RSAKeyGenerator(2048).generate();
        arquivo = Files.createTempFile("itau-jwt-publica-", ".pem");
        Files.writeString(arquivo, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                .encodeToString(chave.toRSAPublicKey().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
    }
    @AfterAll static void limparChavePublica() throws Exception { Files.deleteIfExists(arquivo); }
    @BeforeEach void iniciar() {
        contexto = new AnnotationConfigWebApplicationContext();
        contexto.setServletContext(new MockServletContext());
        contexto.getEnvironment().setActiveProfiles("local");
        contexto.getEnvironment().getPropertySources().addFirst(new MapPropertySource("teste", Map.of(
                "seguranca.jwt.issuer", ISSUER, "seguranca.jwt.clientes-permitidos", "canal-app",
                "seguranca.jwt.chave-publica", arquivo.toUri().toString())));
        contexto.register(WebConfig.class, SegurancaConfig.class);
        contexto.refresh();
        autorizar = contexto.getBean(AutorizarTransacao.class);
        when(autorizar.executar(any())).thenThrow(new AcessoNegadoException());
        mvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(contexto.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }
    @AfterEach void fechar() { contexto.close(); }

    @Test void headerForjadoSemTokenNaoAutentica() throws Exception {
        requisicao(null).andExpect(status().isUnauthorized());
        verifyNoInteractions(autorizar);
    }
    @Test void assinaturaDeOutraChaveNaoAutentica() throws Exception {
        requisicao(token(new RSAKeyGenerator(2048).generate(), ISSUER, "canal-app", "access", "financas/autorizacoes.write", 600))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(autorizar);
    }
    @Test void tokenExpiradoEmissorClienteOuTipoErradoNaoAutenticam() throws Exception {
        requisicao(token(chave, ISSUER, "canal-app", "access", "financas/autorizacoes.write", -120)).andExpect(status().isUnauthorized());
        requisicao(token(chave, "https://outro.exemplo", "canal-app", "access", "financas/autorizacoes.write", 600)).andExpect(status().isUnauthorized());
        requisicao(token(chave, ISSUER, "outro-cliente", "access", "financas/autorizacoes.write", 600)).andExpect(status().isUnauthorized());
        requisicao(token(chave, ISSUER, "canal-app", "id", "financas/autorizacoes.write", 600)).andExpect(status().isUnauthorized());
        verifyNoInteractions(autorizar);
    }
    @Test void escopoAusenteNegaAcesso() throws Exception {
        requisicao(token(chave, ISSUER, "canal-app", "access", "outro", 600)).andExpect(status().isForbidden());
        verifyNoInteractions(autorizar);
    }
    @Test void identidadeVemDoJwtNuncaDoHeader() throws Exception {
        requisicao(token(chave, ISSUER, "canal-app", "access", "financas/autorizacoes.write", 600))
                .andExpect(status().isForbidden()); // entitlement negado pelo caso de uso
        var captor = org.mockito.ArgumentCaptor.forClass(SolicitacaoDeAutorizacao.class);
        verify(autorizar).executar(captor.capture());
        assertThat(captor.getValue().clienteId()).isEqualTo("canal-app");
    }
    private ResultActions requisicao(String token) throws Exception {
        var req = post("/v1/contratos/CTR-1/autorizacoes").header("X-Client-Id", "identidade-forjada")
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"id_conta\":\"0001-123\",\"valor\":10.00,\"moeda\":\"BRL\",\"tipo_operacao\":\"DEBITO\"}");
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }
    private static String token(RSAKey key, String issuer, String client, String use, String scope, long segundos) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().issuer(issuer)
                .subject(client).claim("client_id", client).claim("token_use", use).claim("scope", scope)
                .issueTime(Date.from(Instant.now().minusSeconds(300))).expirationTime(Date.from(Instant.now().plusSeconds(segundos))).build());
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    @Configuration @EnableWebMvc
    static class WebConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Bean AutorizarTransacao autorizar() { return mock(AutorizarTransacao.class); }
        @Bean AutorizacaoController controller(AutorizarTransacao autorizar) { return new AutorizacaoController(autorizar); }
        @Bean ProblemDetailsHandler problemas() { return new ProblemDetailsHandler(); }
        @Override public void configureMessageConverters(java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                    .propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build()));
        }
    }
}
