package br.com.itau.financas.limites.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "limites")
public record LimitesProperties(String regiao, String endpoint, String tabela, Duration retencaoOperacoes,
                                int tentativas, Duration esperaBase, Duration janelaDeRepeticao,
                                String filaLimitesConcedidos) {

    public boolean temEndpointAlternativo() {
        return endpoint != null && !endpoint.isBlank();
    }
}
