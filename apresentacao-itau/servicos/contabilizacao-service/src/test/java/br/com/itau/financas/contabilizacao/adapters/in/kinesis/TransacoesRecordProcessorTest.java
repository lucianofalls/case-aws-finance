package br.com.itau.financas.contabilizacao.adapters.in.kinesis;

import br.com.itau.financas.contabilizacao.application.ContabilizarFatoService;
import br.com.itau.financas.contabilizacao.domain.PlanoDeContas;
import br.com.itau.financas.contabilizacao.domain.RegistroContabil;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import org.junit.jupiter.api.Test;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Auditoria C06: nenhum registro passa do checkpoint sem ter sido contabilizado ou guardado na quarentena. */
class TransacoesRecordProcessorTest {

    private final List<RegistroContabil> razao = new CopyOnWriteArrayList<>();
    private final ContabilizarFatoService contabilizar = new ContabilizarFatoService(new PlanoDeContas(),
            (eventoId, registro) -> {
                registro.ifPresent(razao::add);
                return true;
            });
    private final SinalDeEncerramento sinal = new SinalDeEncerramento();
    private final RecordProcessorCheckpointer checkpointer = mock(RecordProcessorCheckpointer.class);

    @Test
    void quarentenaForaDoArRetemOShardAteAceitarORegistro() throws Exception {
        QuarentenaInstavel quarentena = new QuarentenaInstavel(3);
        TransacoesRecordProcessor processador = processador(quarentena, milissegundos -> { });

        processador.processRecords(lote(registro("1", debito()), registro("2", "{invalido"), registro("3", debito())));

        assertThat(quarentena.tentativas).isEqualTo(4);
        assertThat(quarentena.aceitos).containsExactly("{invalido");
        assertThat(razao).hasSize(2);
        verify(checkpointer).checkpoint("3", 0L);
    }

    @Test
    void encerramentoDuranteAEsperaDevolveOShardSemConfirmarORegistroPendente() throws Exception {
        QuarentenaInstavel quarentena = new QuarentenaInstavel(Integer.MAX_VALUE);
        TransacoesRecordProcessor processador = processador(quarentena, milissegundos -> sinal.encerrar());

        processador.processRecords(lote(registro("1", debito()), registro("2", "{invalido"), registro("3", debito())));
        processador.processRecords(lote(registro("4", debito())));

        verify(checkpointer).checkpoint("1", 0L);
        verify(checkpointer, never()).checkpoint("2", 0L);
        verify(checkpointer, never()).checkpoint("3", 0L);
        assertThat(razao).as("o registro 3 e o lote seguinte ficam para o próximo dono do shard").hasSize(1);
    }

    @Test
    void loteSemNenhumRegistroTratadoNaoFazCheckpoint() throws Exception {
        TransacoesRecordProcessor processador = processador(new QuarentenaInstavel(Integer.MAX_VALUE),
                milissegundos -> sinal.encerrar());

        processador.processRecords(lote(registro("1", "{invalido")));

        verify(checkpointer, never()).checkpoint(anyString(), anyLong());
    }

    private TransacoesRecordProcessor processador(Quarentena quarentena, TransacoesRecordProcessor.Espera espera) {
        return new TransacoesRecordProcessor(new TradutorDeEventos(), contabilizar, quarentena, sinal, espera);
    }

    private ProcessRecordsInput lote(KinesisClientRecord... registros) {
        return ProcessRecordsInput.builder().records(List.of(registros)).checkpointer(checkpointer).build();
    }

    private static KinesisClientRecord registro(String sequencia, String json) {
        return KinesisClientRecord.builder().sequenceNumber(sequencia).subSequenceNumber(0)
                .data(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8))).build();
    }

    private static String debito() {
        return CodecDeEventos.paraJson(CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO,
                TiposDeEvento.TRANSACAO_AUTORIZADA, Instant.parse("2026-10-21T12:00:00Z"), "contrato/CTR-1",
                "CTR-1", "corr", null, new PayloadsV1.TransacaoAutorizada(UUID.randomUUID().toString(), "CTR-1",
                        "0001", "DEBITO", "10.00", "BRL", "10.00", "990.00", null)));
    }

    /** Falha nas primeiras {@code falhas} chamadas e depois aceita. */
    private static final class QuarentenaInstavel implements Quarentena {
        private final int falhas;
        private final List<String> aceitos = new ArrayList<>();
        private int tentativas;

        QuarentenaInstavel(int falhas) {
            this.falhas = falhas;
        }

        @Override
        public void enviar(String registro, String motivo) {
            tentativas++;
            if (tentativas <= falhas) {
                throw new IllegalStateException("SQS indisponível");
            }
            aceitos.add(registro);
        }
    }
}
