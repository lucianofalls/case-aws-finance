package br.com.itau.financas.reconciliador.adapters;

import br.com.itau.financas.reconciliador.AgendarVerificacoes.FilaDeVerificacao;
import br.com.itau.financas.reconciliador.AgendarVerificacoes.OperacaoGravada;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * SendMessageBatch de até 10 mensagens: um décimo das requisições (e do custo) de mensagens avulsas. O atraso de
 * 15 min é o da própria fila (DelaySeconds), definido no Terraform. Corpo igual ao que o reconciliador já lê.
 */
public final class SqsFilaDeVerificacao implements FilaDeVerificacao {

    private static final int LOTE_SQS = 10;

    private final SqsClient sqs;
    private final String urlDaFila;

    public SqsFilaDeVerificacao(SqsClient sqs, String urlDaFila) {
        this.sqs = sqs;
        this.urlDaFila = urlDaFila;
    }

    @Override
    public Set<String> enviar(List<OperacaoGravada> operacoes) {
        Set<String> falhas = new HashSet<>();
        for (int inicio = 0; inicio < operacoes.size(); inicio += LOTE_SQS) {
            List<OperacaoGravada> lote = operacoes.subList(inicio, Math.min(inicio + LOTE_SQS, operacoes.size()));
            try {
                List<BatchResultErrorEntry> erros = sqs.sendMessageBatch(pedido -> pedido.queueUrl(urlDaFila)
                        .entries(IntStream.range(0, lote.size()).mapToObj(i -> entrada(i, lote.get(i))).toList()))
                        .failed();
                erros.forEach(erro -> falhas.add(lote.get(Integer.parseInt(erro.id())).sequencia()));
            } catch (RuntimeException e) {
                lote.forEach(operacao -> falhas.add(operacao.sequencia()));
            }
        }
        return falhas;
    }

    private static SendMessageBatchRequestEntry entrada(int indice, OperacaoGravada operacao) {
        return SendMessageBatchRequestEntry.builder()
                .id(Integer.toString(indice))
                .messageBody("{\"id_autorizacao\":\"" + operacao.idOperacao() + "\",\"id_contrato\":\""
                        + operacao.idContrato() + "\"}")
                .build();
    }
}
