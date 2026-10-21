package br.com.itau.financas.contabilizacao.adapters.out.sqs;

import br.com.itau.financas.contabilizacao.adapters.in.kinesis.Quarentena;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

/** Quarentena em SQS (DLQ) com alarme de profundidade; o redrive é manual após a correção. */
public final class SqsQuarentena implements Quarentena {

    private final SqsClient sqs;
    private final String urlDaFila;

    public SqsQuarentena(SqsClient sqs, String urlDaFila) {
        this.sqs = sqs;
        this.urlDaFila = urlDaFila;
    }

    @Override
    public void enviar(String registro, String motivo) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(urlDaFila)
                .messageBody(registro)
                .messageAttributes(Map.of("motivo", MessageAttributeValue.builder()
                        .dataType("String").stringValue(motivo.length() > 256 ? motivo.substring(0, 256) : motivo)
                        .build()))
                .build());
    }
}
