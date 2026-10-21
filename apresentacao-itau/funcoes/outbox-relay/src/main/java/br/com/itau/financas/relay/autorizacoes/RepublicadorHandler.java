package br.com.itau.financas.relay.autorizacoes;

import br.com.itau.financas.relay.autorizacoes.Republicador.LeitorDeAutorizacoes;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Passo da máquina de estados {@code conciliacao}: lê o CSV do Athena (colunas {@code id_autorizacao, evento_id,
 * motivo}) e republica no Kinesis. Entrada: {@code {"resultado": "s3://.../resultado.csv"}}.
 */
public final class RepublicadorHandler implements RequestHandler<Map<String, String>, Map<String, Object>> {

    private static final int LOTE_BATCH_GET = 100;

    private static final class Padrao {
        static final DynamoDbClient DYNAMO = cliente(DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())).build();
        static final S3Client S3 = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        static final Republicador REPUBLICADOR = new Republicador(leitor(DYNAMO, System.getenv("TABELA_AUTORIZACOES")),
                new KinesisDestino(cliente(KinesisClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()))
                        .build(), System.getenv("STREAM_TRANSACOES")));
    }

    private final Function<String, String> lerS3;
    private final Republicador republicador;

    public RepublicadorHandler() {
        this(s3 -> {
            URI uri = URI.create(s3);
            return Padrao.S3.getObjectAsBytes(pedido -> pedido.bucket(uri.getHost()).key(uri.getPath().substring(1)))
                    .asUtf8String();
        }, Padrao.REPUBLICADOR);
    }

    RepublicadorHandler(Function<String, String> lerS3, Republicador republicador) {
        this.lerS3 = lerS3;
        this.republicador = republicador;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, String> entrada, Context contexto) {
        List<String> ids = idsDoCsv(lerS3.apply(entrada.get("resultado")));
        Republicador.Resultado resultado = republicador.republicar(ids);
        // Embedded Metric Format → métrica do CloudWatch (alarme quando > 0: houve lacuna de publicação/contabilização)
        System.out.printf("{\"_aws\":{\"Timestamp\":%d,\"CloudWatchMetrics\":[{\"Namespace\":\"Financas/Conciliacao\","
                + "\"Dimensions\":[[]],\"Metrics\":[{\"Name\":\"FatosRepublicados\",\"Unit\":\"Count\"}]}]},"
                + "\"FatosRepublicados\":%d}%n", System.currentTimeMillis(), resultado.republicados());
        return Map.of("republicados", resultado.republicados(), "nao_encontrados", resultado.naoEncontrados());
    }

    /** Primeira coluna do CSV do Athena ({@code id_autorizacao}), sem o cabeçalho e sem repetição. */
    static List<String> idsDoCsv(String csv) {
        return Arrays.stream(csv.strip().split("\\R")).skip(1)
                .filter(linha -> !linha.isBlank())
                .map(linha -> linha.split(",", -1)[0].strip().replaceAll("^\"|\"$", ""))
                .distinct()
                .toList();
    }

    static LeitorDeAutorizacoes leitor(DynamoDbClient dynamo, String tabela) {
        return ids -> {
            Map<String, Map<String, AttributeValue>> itens = new HashMap<>();
            for (int inicio = 0; inicio < ids.size(); inicio += LOTE_BATCH_GET) {
                List<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> chaves = new ArrayList<>();
                ids.subList(inicio, Math.min(inicio + LOTE_BATCH_GET, ids.size())).forEach(id -> chaves.add(Map.of(
                        "id_autorizacao", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(id))));
                Map<String, KeysAndAttributes> pedido = Map.of(tabela,
                        KeysAndAttributes.builder().keys(chaves).consistentRead(true).build());
                while (!pedido.isEmpty()) {
                    var resposta = dynamo.batchGetItem(BatchGetItemRequest.builder().requestItems(pedido).build());
                    resposta.responses().getOrDefault(tabela, List.of()).forEach(item ->
                            itens.put(item.get("id_autorizacao").s(), Republicador.doSdk(item)));
                    pedido = resposta.unprocessedKeys();
                }
            }
            return itens;
        };
    }

    private static <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>> B cliente(B builder) {
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder;
    }
}
