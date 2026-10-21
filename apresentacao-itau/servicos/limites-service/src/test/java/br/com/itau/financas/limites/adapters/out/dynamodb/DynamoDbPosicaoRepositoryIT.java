package br.com.itau.financas.limites.adapters.out.dynamodb;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.application.AplicarOperacaoService;
import br.com.itau.financas.limites.application.PoliticaDeRetentativa;
import br.com.itau.financas.limites.domain.EventoDeLimites.LimiteAlterado;
import br.com.itau.financas.limites.domain.Excecoes.LimiteInsuficienteException;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Posicao;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O adaptador real contra o DynamoDB (imagem oficial amazon/dynamodb-local): expressões condicionais,
 * TransactWriteItems e leitura dos CancellationReasons. Mesmos cenários do teste com o fake em memória —
 * prova que o fake e o DynamoDB têm a mesma semântica (LSP).
 * <p>
 * Roda só com Docker e no perfil de integração: {@code mvn verify -Pintegracao}. Sem Docker, é ignorado.
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDbPosicaoRepositoryIT {

    private static final String TABELA = "limites";

    @Container
    private static final GenericContainer<?> DYNAMODB =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.3.2")).withExposedPorts(8000);

    private static DynamoDbClient dynamo;

    private ContratoId contrato;
    private DynamoDbPosicaoRepository repositorio;
    private AplicarOperacaoService servico;

    @BeforeAll
    static void criarTabela() {
        dynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000)))
                .region(Region.SA_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClientBuilder(ApacheHttpClient.builder())
                .build();
        dynamo.createTable(tabela -> tabela.tableName(TABELA)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName(ItensDeLimites.PK).attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(ItensDeLimites.SK).attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName(ItensDeLimites.PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(ItensDeLimites.SK).keyType(KeyType.RANGE).build()));
    }

    @BeforeEach
    void preparar() {
        contrato = new ContratoId("CTR-" + UUID.randomUUID());
        repositorio = new DynamoDbPosicaoRepository(dynamo, TABELA, Duration.ofDays(35));
        Posicao nova = Posicao.nova(contrato, "0001-1", "CREDITO_PESSOAL", Dinheiro.de("100.00", "BRL"), 1);
        repositorio.criar(nova, new LimiteAlterado(contrato, Dinheiro.de("0.00", "BRL"), Dinheiro.de("100.00", "BRL"),
                Dinheiro.de("100.00", "BRL"), 1, Instant.now()));
        servico = new AplicarOperacaoService(repositorio, new PoliticaDeRetentativa(200, DynamoDbPosicaoRepositoryIT::esperarComJitter),
                Clock.systemUTC(), Duration.ofMinutes(10));
    }

    @Test
    void criarGravaPosicaoEOutboxNaMesmaTransacao() {
        var itens = dynamo.query(consulta -> consulta.tableName(TABELA)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", ItensDeLimites.PK))
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(contrato.valor())))).items();

        assertThat(itens).extracting(item -> item.get(ItensDeLimites.SK).s())
                .anyMatch(ItensDeLimites.SK_POSICAO::equals)
                .anyMatch(sk -> sk.startsWith(ItensDeLimites.PREFIXO_OUTBOX));
    }

    @Test
    void debitoGravaAFotografiaDaPosicao() {
        var resultado = servico.aplicar(comando(UUID.randomUUID(), "30.00"));

        assertThat(resultado.operacao().disponivelApos().comoTexto()).isEqualTo("70.00");
        assertThat(repositorio.buscarPosicao(contrato).orElseThrow().disponivel().comoTexto()).isEqualTo("70.00");
    }

    @Test
    void repeticaoComOMesmoIdNaoDebitaDuasVezes() {
        UUID id = UUID.randomUUID();
        servico.aplicar(comando(id, "30.00"));

        var repeticao = servico.aplicar(comando(id, "30.00"));

        assertThat(repeticao.criadaAgora()).isFalse();
        assertThat(repositorio.buscarPosicao(contrato).orElseThrow().disponivel().comoTexto()).isEqualTo("70.00");
    }

    @Test
    void bloqueadoDaReservaEhPersistidoEReLidoDoBanco() {
        servico.aplicar(new AplicarOperacaoService.Comando(contrato.valor(), UUID.randomUUID().toString(), "RESERVA",
                "40.00", "BRL", "reserva", null));
        servico.aplicar(new AplicarOperacaoService.Comando(contrato.valor(), UUID.randomUUID().toString(), "CAPTURA",
                "25.00", "BRL", "captura", null));

        Posicao relida = new DynamoDbPosicaoRepository(dynamo, TABELA, Duration.ofDays(35)).buscarPosicao(contrato)
                .orElseThrow();

        assertThat(relida.bloqueado().comoTexto()).isEqualTo("15.00");
        assertThat(relida.utilizado().comoTexto()).isEqualTo("25.00");
        assertThat(relida.disponivel().comoTexto()).isEqualTo("60.00");
    }

    @Test
    void cinquentaDebitosSimultaneosNuncaUltrapassamOLimite() throws Exception {
        AtomicInteger aprovados = new AtomicInteger();
        AtomicInteger recusados = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futuros.add(executor.submit(() -> {
                    try {
                        servico.aplicar(comando(UUID.randomUUID(), "3.00"));
                        aprovados.incrementAndGet();
                    } catch (LimiteInsuficienteException e) {
                        recusados.incrementAndGet();
                    }
                }));
            }
            for (Future<?> futuro : futuros) {
                futuro.get();
            }
        }

        assertThat(aprovados.get()).isEqualTo(33);
        assertThat(recusados.get()).isEqualTo(17);
        assertThat(repositorio.buscarPosicao(contrato).orElseThrow().disponivel().comoTexto()).isEqualTo("1.00");
    }

    /** Backoff com jitter: com DynamoDB real, retentar sem espera só multiplica os conflitos. */
    private static void esperarComJitter(int tentativa) {
        LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(1_000_000L, 2_000_000L * Math.min(tentativa, 10)));
    }

    private AplicarOperacaoService.Comando comando(UUID id, String valor) {
        return new AplicarOperacaoService.Comando(contrato.valor(), id.toString(), "DEBITO", valor, "BRL",
                "impressao-" + valor, null);
    }
}
