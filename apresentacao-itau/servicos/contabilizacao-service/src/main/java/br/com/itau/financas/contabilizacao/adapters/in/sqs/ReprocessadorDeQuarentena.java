package br.com.itau.financas.contabilizacao.adapters.in.sqs;

import br.com.itau.financas.contabilizacao.adapters.in.kinesis.TradutorDeEventos;
import br.com.itau.financas.contabilizacao.application.ContabilizarFatoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Reprocessa a quarentena da contabilização (auditoria C06). Cada mensagem é um CloudEvent que falhou no
 * consumidor do Kinesis; o reprocessamento é idempotente (inbox).
 * <ul>
 *   <li>contabilizou (ou já estava contabilizado) → remove da fila</li>
 *   <li>falhou de novo → a mensagem volta após o visibility timeout da fila (1 h); depois de N recebimentos o
 *       redrive do SQS a move para a DLQ da quarentena, que tem alarme e tratamento manual</li>
 * </ul>
 */
public final class ReprocessadorDeQuarentena {

    private static final Logger LOG = LoggerFactory.getLogger(ReprocessadorDeQuarentena.class);
    private static final int LOTE = 10;

    private final SqsClient sqs;
    private final String urlDaFila;
    private final TradutorDeEventos tradutor;
    private final ContabilizarFatoService contabilizar;

    public ReprocessadorDeQuarentena(SqsClient sqs, String urlDaFila, TradutorDeEventos tradutor,
                                     ContabilizarFatoService contabilizar) {
        this.sqs = sqs;
        this.urlDaFila = urlDaFila;
        this.tradutor = tradutor;
        this.contabilizar = contabilizar;
    }

    /** @return quantas mensagens foram contabilizadas e removidas nesta rodada */
    @Scheduled(fixedDelayString = "${contabilizacao.quarentena.intervalo:PT1M}")
    public int reprocessar() {
        int resolvidas = 0;
        for (Message mensagem : sqs.receiveMessage(pedido -> pedido.queueUrl(urlDaFila)
                .maxNumberOfMessages(LOTE).waitTimeSeconds(1)).messages()) {
            try {
                // Apenas tipos conscientemente ignoráveis retornam vazio (ex.: TransacaoRecusada).
                // Tipo desconhecido ou schema inválido lança exceção, permanece na fila e vai à DLQ.
                tradutor.traduzir(mensagem.body()).ifPresent(contabilizar::contabilizar);
                sqs.deleteMessage(pedido -> pedido.queueUrl(urlDaFila).receiptHandle(mensagem.receiptHandle()));
                resolvidas++;
            } catch (RuntimeException e) {
                LOG.warn("mensagem {} da quarentena continua falhando; volta após o visibility timeout",
                        mensagem.messageId(), e);
            }
        }
        if (resolvidas > 0) {
            LOG.info("{} fato(s) da quarentena contabilizado(s)", resolvidas);
        }
        return resolvidas;
    }
}
