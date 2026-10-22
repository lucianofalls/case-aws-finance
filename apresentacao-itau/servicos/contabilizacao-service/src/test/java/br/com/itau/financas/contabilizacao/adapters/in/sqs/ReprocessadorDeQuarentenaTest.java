package br.com.itau.financas.contabilizacao.adapters.in.sqs;

import br.com.itau.financas.contabilizacao.adapters.in.kinesis.TradutorDeEventos;
import br.com.itau.financas.contabilizacao.application.ContabilizarFatoService;
import br.com.itau.financas.contabilizacao.domain.PlanoDeContas;
import br.com.itau.financas.contabilizacao.domain.RegistroContabil;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Auditoria C06: a quarentena é reprocessada; só sai da fila o que foi contabilizado. */
class ReprocessadorDeQuarentenaTest {

    @Test
    void removeSoAsMensagensQueForamContabilizadas() {
        List<RegistroContabil> razao = new CopyOnWriteArrayList<>();
        ContabilizarFatoService contabilizar = new ContabilizarFatoService(new PlanoDeContas(), (id, registro) -> {
            registro.ifPresent(razao::add);
            return true;
        });
        FilaFalsa fila = new FilaFalsa(List.of(
                Message.builder().messageId("m1").receiptHandle("r1").body(debito()).build(),
                Message.builder().messageId("m2").receiptHandle("r2").body("{ainda-invalido").build()));

        int resolvidas = new ReprocessadorDeQuarentena(fila, "url", new TradutorDeEventos(), contabilizar).reprocessar();

        assertThat(resolvidas).isEqualTo(1);
        assertThat(razao).hasSize(1);
        assertThat(fila.removidas).containsExactly("r1");
    }

    @Test
    void tipoDesconhecidoPermaneceNaFilaParaDlq() {
        FilaFalsa fila = new FilaFalsa(List.of(Message.builder().messageId("desconhecido")
                .receiptHandle("rx").body("{\"specversion\":\"1.0\",\"type\":\"br.com.itau.financas.futuro.v2\"}").build()));
        ContabilizarFatoService contabilizar = new ContabilizarFatoService(new PlanoDeContas(),
                (id, registro) -> { throw new AssertionError("não deve contabilizar tipo desconhecido"); });

        int resolvidas = new ReprocessadorDeQuarentena(fila, "url", new TradutorDeEventos(), contabilizar).reprocessar();

        assertThat(resolvidas).isZero();
        assertThat(fila.removidas).isEmpty();
    }

    private static String debito() {
        return CodecDeEventos.paraJson(CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO,
                TiposDeEvento.TRANSACAO_AUTORIZADA, Instant.parse("2026-10-21T12:00:00Z"), "contrato/CTR-1",
                "CTR-1", "corr", null, new PayloadsV1.TransacaoAutorizada(UUID.randomUUID().toString(), "CTR-1",
                        "0001", "DEBITO", "10.00", "BRL", "10.00", "990.00", null)));
    }

    private static final class FilaFalsa implements SqsClient {
        private final List<Message> mensagens;
        private final List<String> removidas = new CopyOnWriteArrayList<>();

        FilaFalsa(List<Message> mensagens) {
            this.mensagens = mensagens;
        }

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest pedido) {
            return ReceiveMessageResponse.builder().messages(mensagens).build();
        }

        @Override
        public DeleteMessageResponse deleteMessage(DeleteMessageRequest pedido) {
            removidas.add(pedido.receiptHandle());
            return DeleteMessageResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }
    }
}
