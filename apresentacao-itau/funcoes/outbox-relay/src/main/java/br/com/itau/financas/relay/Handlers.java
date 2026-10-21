package br.com.itau.financas.relay;

import br.com.itau.financas.relay.autorizacoes.ExtratorDeAutorizacoes;
import br.com.itau.financas.relay.autorizacoes.KinesisDestino;
import br.com.itau.financas.relay.limites.EventBridgeDestino;
import br.com.itau.financas.relay.limites.ExtratorDeOutboxExplicito;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;

import java.net.URI;

/**
 * Pontos de entrada Lambda. Clientes criados fora do handler: reaproveitados entre invocações e
 * capturados no snapshot do SnapStart.
 */
public final class Handlers {

    private Handlers() {
    }

    /** autorizacoes (Streams) → Kinesis {@code transacoes}. */
    public static final class AutorizacoesParaKinesis implements RequestHandler<DynamodbEvent, StreamsEventResponse> {

        private static final RelayDeOutbox RELAY = new RelayDeOutbox(new ExtratorDeAutorizacoes(),
                new KinesisDestino(cliente(KinesisClient.builder()).build(), variavel("STREAM_TRANSACOES")));

        @Override
        public StreamsEventResponse handleRequest(DynamodbEvent lote, Context contexto) {
            return RELAY.processar(lote);
        }
    }

    /** limites (Streams, itens OUT#) → EventBridge bus {@code financas}. */
    public static final class LimitesParaEventBridge implements RequestHandler<DynamodbEvent, StreamsEventResponse> {

        private static final RelayDeOutbox RELAY = new RelayDeOutbox(new ExtratorDeOutboxExplicito(),
                new EventBridgeDestino(cliente(EventBridgeClient.builder()).build(), variavel("BARRAMENTO_EVENTOS"),
                        "itau.financas.limites"));

        @Override
        public StreamsEventResponse handleRequest(DynamodbEvent lote, Context contexto) {
            return RELAY.processar(lote);
        }
    }

    /** HTTP client leve (URLConnection) reduz o cold start; endpoint alternativo só no LocalStack. */
    static <B extends AwsClientBuilder<B, ?> & AwsSyncClientBuilder<B, ?>> B cliente(B builder) {
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.httpClientBuilder(UrlConnectionHttpClient.builder());
    }

    static String variavel(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("variável de ambiente obrigatória ausente: " + nome);
        }
        return valor;
    }
}
