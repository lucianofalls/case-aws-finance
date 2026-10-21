package br.com.itau.financas.contabilizacao.config;

import br.com.itau.financas.contabilizacao.adapters.in.kinesis.ConsumidorKinesis;
import br.com.itau.financas.contabilizacao.adapters.in.kinesis.Quarentena;
import br.com.itau.financas.contabilizacao.adapters.in.kinesis.SinalDeEncerramento;
import br.com.itau.financas.contabilizacao.adapters.in.kinesis.TradutorDeEventos;
import br.com.itau.financas.contabilizacao.adapters.in.kinesis.TransacoesRecordProcessor;
import br.com.itau.financas.contabilizacao.adapters.in.sqs.ReprocessadorDeQuarentena;
import br.com.itau.financas.contabilizacao.adapters.out.jdbc.JdbcRegistroContabilRepository;
import br.com.itau.financas.contabilizacao.adapters.out.sqs.SqsQuarentena;
import br.com.itau.financas.contabilizacao.application.ContabilizarFatoService;
import br.com.itau.financas.contabilizacao.application.port.out.RegistroContabilRepository;
import br.com.itau.financas.contabilizacao.domain.PlanoDeContas;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.kinesis.common.ConfigsBuilder;
import software.amazon.kinesis.common.InitialPositionInStream;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;
import software.amazon.kinesis.common.KinesisClientUtil;
import software.amazon.kinesis.coordinator.Scheduler;
import software.amazon.kinesis.retrieval.RetrievalConfig;
import software.amazon.kinesis.retrieval.polling.PollingConfig;

import java.net.URI;
import java.util.UUID;

/** Wiring: único lugar que conhece Spring, JDBC, KCL e AWS SDK (DIP). */
@Configuration
@EnableScheduling
public class ContabilizacaoConfig {

    @Bean
    ContabilizarFatoService contabilizarFatoService(RegistroContabilRepository repositorio) {
        return new ContabilizarFatoService(new PlanoDeContas(), repositorio);
    }

    @Bean
    RegistroContabilRepository registroContabilRepository(JdbcTemplate jdbc, TransactionTemplate transacao) {
        return new JdbcRegistroContabilRepository(jdbc, transacao);
    }

    @Bean
    SqsClient sqs(ContabilizacaoProperties propriedades) {
        return configurar(SqsClient.builder(), propriedades).build();
    }

    @Bean
    Quarentena quarentena(SqsClient sqs, ContabilizacaoProperties propriedades) {
        return new SqsQuarentena(sqs, propriedades.urlFilaQuarentena());
    }

    @Bean
    @ConditionalOnProperty(name = "contabilizacao.quarentena.reprocessar", havingValue = "true", matchIfMissing = true)
    ReprocessadorDeQuarentena reprocessadorDeQuarentena(SqsClient sqs, ContabilizacaoProperties propriedades,
                                                        ContabilizarFatoService contabilizar) {
        return new ReprocessadorDeQuarentena(sqs, propriedades.urlFilaQuarentena(), new TradutorDeEventos(),
                contabilizar);
    }

    @Bean
    @Profile("!sem-kinesis")
    ConsumidorKinesis consumidorKinesis(ContabilizacaoProperties propriedades, ContabilizarFatoService contabilizar,
                                        Quarentena quarentena) {
        KinesisAsyncClient kinesis = propriedades.leituraPorPolling()
                ? configurar(KinesisAsyncClient.builder(), propriedades).build()
                : KinesisClientUtil.createKinesisAsyncClient(configurar(KinesisAsyncClient.builder(), propriedades));
        DynamoDbAsyncClient leases = configurar(DynamoDbAsyncClient.builder(), propriedades).build();
        CloudWatchAsyncClient metricas = configurar(CloudWatchAsyncClient.builder(), propriedades).build();
        TradutorDeEventos tradutor = new TradutorDeEventos();
        SinalDeEncerramento sinal = new SinalDeEncerramento();

        ConfigsBuilder configs = new ConfigsBuilder(propriedades.stream(), propriedades.nomeAplicacaoKcl(), kinesis,
                leases, metricas, UUID.randomUUID().toString(),
                () -> new TransacoesRecordProcessor(tradutor, contabilizar, quarentena, sinal));
        // TRIM_HORIZON: na primeira execução lê desde o início da retenção. Contabilidade não pode perder fato;
        // reprocessar é seguro porque o inbox deduplica pelo id do evento.
        RetrievalConfig leitura = configs.retrievalConfig().initialPositionInStreamExtended(
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON));
        if (propriedades.leituraPorPolling()) {
            leitura = leitura.retrievalSpecificConfig(new PollingConfig(propriedades.stream(), kinesis));
        }
        Scheduler scheduler = new Scheduler(configs.checkpointConfig(), configs.coordinatorConfig(),
                configs.leaseManagementConfig(), configs.lifecycleConfig(), configs.metricsConfig(),
                configs.processorConfig(), leitura);
        return new ConsumidorKinesis(scheduler, sinal);
    }

    private static <B extends AwsClientBuilder<B, ?>> B configurar(B builder, ContabilizacaoProperties propriedades) {
        builder.region(Region.of(propriedades.regiao()));
        if (propriedades.temEndpointAlternativo()) {
            builder.endpointOverride(URI.create(propriedades.endpoint()));
        }
        return builder;
    }
}
