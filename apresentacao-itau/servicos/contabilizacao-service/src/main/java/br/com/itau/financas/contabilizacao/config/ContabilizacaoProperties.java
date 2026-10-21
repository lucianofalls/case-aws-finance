package br.com.itau.financas.contabilizacao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code modoLeitura}: {@code fan-out} (produção: enhanced fan-out, HTTP/2, leitura dedicada por consumidor) ou
 * {@code polling} (emuladores como o LocalStack, onde SubscribeToShard sobre HTTP/2 em texto puro não é confiável).
 */
@ConfigurationProperties(prefix = "contabilizacao")
public record ContabilizacaoProperties(String regiao, String endpoint, String stream, String nomeAplicacaoKcl,
                                       String urlFilaQuarentena, String modoLeitura) {

    public boolean temEndpointAlternativo() {
        return endpoint != null && !endpoint.isBlank();
    }

    public boolean leituraPorPolling() {
        return "polling".equalsIgnoreCase(modoLeitura);
    }
}
