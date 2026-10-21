package br.com.itau.financas.autorizacao.config;

import br.com.itau.financas.autorizacao.adapters.out.dynamodb.DynamoDbAutorizacaoRepository;
import br.com.itau.financas.autorizacao.adapters.out.limites.LimitesHttpAdapter;
import br.com.itau.financas.autorizacao.application.LimitesIndisponivelException;
import br.com.itau.financas.autorizacao.application.port.out.AutorizacaoRepository;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.net.http.HttpClient;

/** Único lugar que conhece frameworks e AWS SDK (DIP): monta os adapters dos ports. */
@Configuration
public class AdaptersConfig {

    private static final int CONEXOES_DYNAMODB = 200;

    @Bean
    DynamoDbClient dynamoDbClient(AutorizacaoProperties propriedades) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(propriedades.aws().regiao()))
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(CONEXOES_DYNAMODB));
        if (propriedades.aws().temEndpointAlternativo()) {
            builder.endpointOverride(URI.create(propriedades.aws().endpoint()));
        }
        return builder.build();
    }

    @Bean
    AutorizacaoRepository autorizacaoRepository(DynamoDbClient dynamo, AutorizacaoProperties propriedades) {
        return new DynamoDbAutorizacaoRepository(dynamo, propriedades.aws().tabelaAutorizacoes(),
                propriedades.aws().retencao());
    }

    @Bean
    OperacaoDeLimitePort operacaoDeLimite(RestClient.Builder builder, ObjectMapper json,
                                          AutorizacaoProperties propriedades) {
        AutorizacaoProperties.Limites config = propriedades.limites();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeoutConexao()).build());
        fabrica.setReadTimeout(config.timeoutLeitura());
        RestClient http = builder.baseUrl(config.url().toString()).requestFactory(fabrica).build();

        CircuitBreaker circuitBreaker = CircuitBreaker.of("limites", CircuitBreakerConfig.custom()
                .failureRateThreshold(config.limiarFalhasPercentual())
                .slidingWindowSize(50)
                .minimumNumberOfCalls(20)
                .waitDurationInOpenState(config.esperaCircuitoAberto())
                .recordExceptions(LimitesIndisponivelException.class)
                .build());
        Retry retry = Retry.of("limites", RetryConfig.custom()
                .maxAttempts(config.tentativas())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(config.esperaInicial(), 2.0))
                .retryExceptions(LimitesIndisponivelException.class)
                .build());
        return new LimitesHttpAdapter(http, json, circuitBreaker, retry);
    }
}
