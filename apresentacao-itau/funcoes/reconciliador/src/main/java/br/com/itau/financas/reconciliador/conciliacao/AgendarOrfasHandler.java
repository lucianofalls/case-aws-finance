package br.com.itau.financas.reconciliador.conciliacao;

import br.com.itau.financas.reconciliador.AgendarVerificacoes;
import br.com.itau.financas.reconciliador.AgendarVerificacoes.OperacaoGravada;
import br.com.itau.financas.reconciliador.adapters.SqsFilaDeVerificacao;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Último passo da conciliação para as operações sem decisão: cada candidata vai para a fila de verificação, e o
 * reconciliador confere a tabela viva antes de reverter. Rede de segurança do agendamento por Streams (ADR 032).
 * Entrada: {@code {"resultado": "s3://.../resultado.csv"}}.
 */
public final class AgendarOrfasHandler implements RequestHandler<Map<String, String>, Map<String, Object>> {

    private static final class Padrao {
        static final S3Client S3 = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        static final AgendarVerificacoes AGENDAR = new AgendarVerificacoes(new SqsFilaDeVerificacao(
                SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                System.getenv("FILA_VERIFICACAO_URL")));
    }

    private final Function<String, String> lerS3;
    private final AgendarVerificacoes agendar;

    public AgendarOrfasHandler() {
        this(s3 -> {
            String[] local = ResultadoAthena.localizacao(s3);
            return Padrao.S3.getObjectAsBytes(pedido -> pedido.bucket(local[0]).key(local[1])).asUtf8String();
        }, Padrao.AGENDAR);
    }

    AgendarOrfasHandler(Function<String, String> lerS3, AgendarVerificacoes agendar) {
        this.lerS3 = lerS3;
        this.agendar = agendar;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, String> entrada, Context contexto) {
        List<Map<String, String>> linhas = ResultadoAthena.linhas(lerS3.apply(entrada.get("resultado")));
        List<OperacaoGravada> candidatas = IntStream.range(0, linhas.size())
                .mapToObj(i -> new OperacaoGravada(Integer.toString(i), linhas.get(i).get("id_contrato"),
                        linhas.get(i).get("id_operacao")))
                .toList();
        if (agendar.agendar(candidatas).isPresent()) {
            throw new IllegalStateException("falha ao agendar parte das verificações; a máquina de estados retenta");
        }
        // Embedded Metric Format: vira métrica do CloudWatch (alarme quando > 0).
        System.out.printf("{\"_aws\":{\"Timestamp\":%d,\"CloudWatchMetrics\":[{\"Namespace\":\"Financas/Conciliacao\","
                + "\"Dimensions\":[[]],\"Metrics\":[{\"Name\":\"OperacoesSemDecisao\",\"Unit\":\"Count\"}]}]},"
                + "\"OperacoesSemDecisao\":%d}%n", System.currentTimeMillis(), candidatas.size());
        return Map.of("candidatas", candidatas.size());
    }
}
