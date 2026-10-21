package br.com.itau.financas.reconciliador;

import br.com.itau.financas.reconciliador.adapters.DynamoDbConsultaDeAutorizacoes;
import br.com.itau.financas.reconciliador.adapters.HttpReversaoNoLimite;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/**
 * Gatilho: fila {@code autorizacoes-verificacao} (DelaySeconds = 900). Falha parcial de lote:
 * só as mensagens com erro voltam para a fila (e vão para a DLQ após maxReceiveCount).
 */
public final class VerificacaoPosteriorHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger LOG = LoggerFactory.getLogger(VerificacaoPosteriorHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VerificarAutorizacaoIncerta CASO_DE_USO = new VerificarAutorizacaoIncerta(
            new DynamoDbConsultaDeAutorizacoes(dynamo(), variavel("TABELA_AUTORIZACOES")),
            new HttpReversaoNoLimite(HttpClient.newHttpClient(), URI.create(variavel("LIMITES_URL"))));

    private final VerificarAutorizacaoIncerta casoDeUso;

    public VerificacaoPosteriorHandler() {
        this(CASO_DE_USO);
    }

    VerificacaoPosteriorHandler(VerificarAutorizacaoIncerta casoDeUso) {
        this.casoDeUso = casoDeUso;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent lote, Context contexto) {
        List<SQSBatchResponse.BatchItemFailure> falhas = new ArrayList<>();
        for (SQSEvent.SQSMessage mensagem : lote.getRecords()) {
            try {
                JsonNode corpo = JSON.readTree(mensagem.getBody());
                String idAutorizacao = corpo.path("id_autorizacao").asText();
                var desfecho = casoDeUso.verificar(idAutorizacao, corpo.path("id_contrato").asText());
                LOG.info("autorização incerta {}: {}", idAutorizacao, desfecho);
            } catch (Exception e) {
                LOG.error("falha ao verificar mensagem {}", mensagem.getMessageId(), e);
                falhas.add(new SQSBatchResponse.BatchItemFailure(mensagem.getMessageId()));
            }
        }
        return new SQSBatchResponse(falhas);
    }

    private static DynamoDbClient dynamo() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    private static String variavel(String nome) {
        String valor = System.getenv(nome);
        return valor == null ? "" : valor;
    }
}
