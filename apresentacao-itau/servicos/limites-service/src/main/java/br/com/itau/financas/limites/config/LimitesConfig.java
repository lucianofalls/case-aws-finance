package br.com.itau.financas.limites.config;

import br.com.itau.financas.limites.adapters.in.sqs.LimiteConcedidoListener;
import br.com.itau.financas.limites.adapters.out.dynamodb.DynamoDbPosicaoRepository;
import br.com.itau.financas.limites.application.AplicarLimiteDoLegadoService;
import br.com.itau.financas.limites.application.AplicarOperacaoService;
import br.com.itau.financas.limites.application.PoliticaDeRetentativa;
import br.com.itau.financas.limites.application.ReverterOperacaoOrfaService;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

/** Wiring: único ponto que conhece Spring e AWS SDK (DIP). */
@Configuration
public class LimitesConfig {

    private static final int CONEXOES_DYNAMODB = 200;

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }

    @Bean
    DynamoDbClient dynamoDbClient(LimitesProperties propriedades) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(propriedades.regiao()))
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(CONEXOES_DYNAMODB));
        if (propriedades.temEndpointAlternativo()) {
            builder.endpointOverride(URI.create(propriedades.endpoint()));
        }
        return builder.build();
    }

    @Bean
    PosicaoRepository posicaoRepository(DynamoDbClient dynamo, LimitesProperties propriedades) {
        return new DynamoDbPosicaoRepository(dynamo, propriedades.tabela(), propriedades.retencaoOperacoes());
    }

    /** Backoff exponencial com jitter total: espera aleatória em [0, base·2^tentativa). */
    @Bean
    PoliticaDeRetentativa politicaDeRetentativa(LimitesProperties propriedades) {
        long baseMs = propriedades.esperaBase().toMillis();
        return new PoliticaDeRetentativa(propriedades.tentativas(), tentativa -> {
            long teto = baseMs << tentativa;
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(teto + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Bean
    AplicarOperacaoService aplicarOperacaoService(PosicaoRepository repositorio, PoliticaDeRetentativa retentativa,
                                                  Clock relogio, LimitesProperties propriedades) {
        return new AplicarOperacaoService(repositorio, retentativa, relogio, propriedades.janelaDeRepeticao());
    }

    @Bean
    AplicarLimiteDoLegadoService aplicarLimiteDoLegadoService(PosicaoRepository repositorio,
                                                              PoliticaDeRetentativa retentativa, Clock relogio) {
        return new AplicarLimiteDoLegadoService(repositorio, retentativa, relogio);
    }

    @Bean
    ReverterOperacaoOrfaService reverterOperacaoOrfaService(PosicaoRepository repositorio,
                                                            PoliticaDeRetentativa retentativa, Clock relogio) {
        return new ReverterOperacaoOrfaService(repositorio, retentativa, relogio);
    }

    @Bean
    LimiteConcedidoListener limiteConcedidoListener(AplicarLimiteDoLegadoService aplicarLimite, ObjectMapper json) {
        return new LimiteConcedidoListener(aplicarLimite, json);
    }
}
