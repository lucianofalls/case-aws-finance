package br.com.itau.financas.reconciliador;

import br.com.itau.financas.reconciliador.AgendarVerificacoes.OperacaoGravada;
import br.com.itau.financas.reconciliador.adapters.SqsFilaDeVerificacao;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gatilho: DynamoDB Streams da tabela {@code limites}, filtrado no event source mapping para {@code INSERT} de
 * itens {@code OPERACAO#} (o relay do outbox lê os {@code OUT#} do mesmo stream). Falha parcial: devolve a
 * primeira sequência não agendada; o Lambda retoma dali.
 */
public final class AgendadorDeVerificacaoHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {

    static final String PREFIXO_OPERACAO = "OPERACAO#";

    /** Criado só quando a Lambda roda (e não ao carregar a classe nos testes, onde não há região AWS). */
    private static final class Padrao {
        static final AgendarVerificacoes CASO_DE_USO = new AgendarVerificacoes(
                new SqsFilaDeVerificacao(sqs(), System.getenv("FILA_VERIFICACAO_URL")));
    }

    private final AgendarVerificacoes casoDeUso;

    public AgendadorDeVerificacaoHandler() {
        this(Padrao.CASO_DE_USO);
    }

    AgendadorDeVerificacaoHandler(AgendarVerificacoes casoDeUso) {
        this.casoDeUso = casoDeUso;
    }

    @Override
    public StreamsEventResponse handleRequest(DynamodbEvent lote, Context contexto) {
        List<OperacaoGravada> operacoes = lote.getRecords().stream()
                .map(AgendadorDeVerificacaoHandler::operacao)
                .filter(Objects::nonNull)
                .toList();
        return casoDeUso.agendar(operacoes)
                .map(sequencia -> StreamsEventResponse.builder()
                        .withBatchItemFailures(List.of(new StreamsEventResponse.BatchItemFailure(sequencia)))
                        .build())
                .orElseGet(StreamsEventResponse::new);
    }

    static OperacaoGravada operacao(DynamodbStreamRecord registro) {
        if (!"INSERT".equals(registro.getEventName())) {
            return null;
        }
        Map<String, AttributeValue> chaves = registro.getDynamodb().getKeys();
        String sk = chaves.get("sk").getS();
        if (!sk.startsWith(PREFIXO_OPERACAO)) {
            return null;
        }
        return new OperacaoGravada(registro.getDynamodb().getSequenceNumber(), chaves.get("id_contrato").getS(),
                sk.substring(PREFIXO_OPERACAO.length()));
    }

    private static SqsClient sqs() {
        SqsClientBuilder builder = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
