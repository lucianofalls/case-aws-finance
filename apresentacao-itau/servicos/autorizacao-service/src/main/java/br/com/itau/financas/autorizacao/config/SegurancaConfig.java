package br.com.itau.financas.autorizacao.config;

import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.web.client.RestTemplateBuilder;
import java.time.Duration;

/** Validação local de JWT; nenhuma consulta ao Cognito por transação. */
@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class SegurancaConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${seguranca.jwt.issuer}") String issuer,
                          @Value("${seguranca.jwt.jwks-uri:}") String jwks,
                          @Value("${seguranca.jwt.clientes-permitidos}") String clientes,
                          @Value("${seguranca.jwt.chave-publica:}") Resource chave,
                          Environment ambiente) throws Exception {
        NimbusJwtDecoder decoder;
        if (chave.exists() && ambiente.acceptsProfiles(Profiles.of("local"))) {
            try (var entrada = chave.getInputStream()) {
                RSAPublicKey publica = RsaKeyConverters.x509().convert(entrada);
                decoder = NimbusJwtDecoder.withPublicKey(publica).build();
            }
        } else {
            if (!issuer.startsWith("https://") || !jwks.startsWith("https://")) {
                throw new IllegalArgumentException("issuer e JWKS HTTPS obrigatórios fora do perfil local");
            }
            decoder = NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(new RestTemplateBuilder()
                    .setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(2)).build()).build();
        }
        Set<String> permitidos = Arrays.stream(clientes.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (permitidos.isEmpty()) throw new IllegalArgumentException("configure clientes JWT permitidos");
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), token -> {
                    boolean valido = "access".equals(token.getClaimAsString("token_use"))
                            && token.getExpiresAt() != null
                            && permitidos.contains(token.getClaimAsString("client_id"));
                    return valido ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "token de acesso ou cliente inválido", null));
                }));
        return decoder;
    }

    @Bean
    SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/contratos/*/autorizacoes")
                        .hasAuthority("SCOPE_financas/autorizacoes.write").anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(401); res.setHeader("WWW-Authenticate", "Bearer");
                    res.setContentType("application/problem+json");
                    res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Não autenticado\",\"status\":401,\"codigo\":\"nao-autenticado\"}");
                }))
                .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/problem+json");
                    res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Acesso negado\",\"status\":403,\"codigo\":\"acesso-negado\"}");
                })).build();
    }
}
