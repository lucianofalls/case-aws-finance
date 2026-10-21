package br.com.itau.financas.contabilizacao.adapters.in.kinesis;

import br.com.itau.financas.contabilizacao.application.ContabilizarFatoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.kinesis.exceptions.InvalidStateException;
import software.amazon.kinesis.exceptions.ShutdownException;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.retrieval.KinesisClientRecord;

import java.nio.charset.StandardCharsets;

/**
 * Processa um shard do stream {@code transacoes} (partition key = id_contrato).
 * Um registro por vez, na ordem do shard, que não é a ordem lógica dos fatos: cada fato é contabilizado
 * de forma independente (o estorno usa o próprio payload). Reentrega é segura: o inbox deduplica pelo id.
 * <p>
 * Regra de ouro (auditoria C06): <b>nenhum registro passa do checkpoint sem destino durável</b> — ou foi
 * contabilizado, ou está na quarentena. Se a quarentena estiver fora, o shard fica retido no registro (o alarme
 * de idade do iterador avisa) em vez de seguir adiante. O KCL não reentrega um lote que lança exceção, por isso
 * o processador nunca deixa uma falha escapar de {@code processRecords}.
 */
public final class TransacoesRecordProcessor implements ShardRecordProcessor {

    /** Espera entre tentativas; injetável para os testes. */
    @FunctionalInterface
    public interface Espera {
        void aguardar(long milissegundos);
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransacoesRecordProcessor.class);
    private static final int TENTATIVAS_CONTABILIZACAO = 3;
    private static final long ESPERA_MAXIMA_QUARENTENA_MS = 30_000;

    private final TradutorDeEventos tradutor;
    private final ContabilizarFatoService contabilizar;
    private final Quarentena quarentena;
    private final SinalDeEncerramento sinal;
    private final Espera espera;
    private String shardId;
    private KinesisClientRecord ultimoTratado;
    private volatile boolean interrompido;

    public TransacoesRecordProcessor(TradutorDeEventos tradutor, ContabilizarFatoService contabilizar,
                                     Quarentena quarentena, SinalDeEncerramento sinal) {
        this(tradutor, contabilizar, quarentena, sinal, TransacoesRecordProcessor::dormir);
    }

    TransacoesRecordProcessor(TradutorDeEventos tradutor, ContabilizarFatoService contabilizar,
                              Quarentena quarentena, SinalDeEncerramento sinal, Espera espera) {
        this.tradutor = tradutor;
        this.contabilizar = contabilizar;
        this.quarentena = quarentena;
        this.sinal = sinal;
        this.espera = espera;
    }

    @Override
    public void initialize(InitializationInput entrada) {
        shardId = entrada.shardId();
        LOG.info("assumindo shard {} a partir de {}", shardId, entrada.extendedSequenceNumber());
    }

    @Override
    public void processRecords(ProcessRecordsInput entrada) {
        if (interrompido) {
            return; // shard devolvido: nada mais é processado nem confirmado por este processador
        }
        try {
            for (KinesisClientRecord registro : entrada.records()) {
                tratar(registro);
                ultimoTratado = registro;
            }
        } catch (ProcessamentoInterrompido e) {
            interrompido = true;
            LOG.warn("shard {} devolvido sem checkpoint do registro pendente (encerramento da task)", shardId);
        }
        checkpointNoUltimoTratado(entrada.checkpointer());
    }

    private void tratar(KinesisClientRecord registro) {
        String json = StandardCharsets.UTF_8.decode(registro.data()).toString();
        RuntimeException ultimaFalha = null;
        for (int tentativa = 1; tentativa <= TENTATIVAS_CONTABILIZACAO; tentativa++) {
            try {
                tradutor.traduzir(json).ifPresent(contabilizar::contabilizar);
                return;
            } catch (RuntimeException e) {
                ultimaFalha = e;
                espera.aguardar(50L * tentativa);
            }
        }
        LOG.error("registro {} do shard {} enviado à quarentena", registro.sequenceNumber(), shardId, ultimaFalha);
        enviarParaQuarentena(json, ultimaFalha.getClass().getSimpleName() + ": " + ultimaFalha.getMessage(),
                registro.sequenceNumber());
    }

    /** Só retorna quando a quarentena aceitou o registro; o shard fica retido enquanto ela estiver fora. */
    private void enviarParaQuarentena(String json, String motivo, String sequencia) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                quarentena.enviar(json, motivo);
                return;
            } catch (RuntimeException e) {
                LOG.error("quarentena indisponível: shard {} retido no registro {} (tentativa {})", shardId,
                        sequencia, tentativa, e);
            }
            if (sinal.encerrando() || Thread.currentThread().isInterrupted()) {
                throw new ProcessamentoInterrompido();
            }
            espera.aguardar(Math.min(ESPERA_MAXIMA_QUARENTENA_MS, 200L << Math.min(tentativa - 1, 10)));
        }
    }

    @Override
    public void leaseLost(LeaseLostInput entrada) {
        interrompido = true;
        LOG.info("lease do shard {} perdido para outro worker", shardId);
    }

    @Override
    public void shardEnded(ShardEndedInput entrada) {
        try {
            entrada.checkpointer().checkpoint(); // obrigatório no fim do shard; só chega aqui com tudo tratado
        } catch (InvalidStateException | ShutdownException e) {
            LOG.warn("checkpoint de fim do shard {} não realizado", shardId, e);
        }
    }

    @Override
    public void shutdownRequested(ShutdownRequestedInput entrada) {
        checkpointNoUltimoTratado(entrada.checkpointer());
    }

    /** Checkpoint na sequência exata do último registro com destino durável — nunca "no fim do lote". */
    private void checkpointNoUltimoTratado(RecordProcessorCheckpointer checkpointer) {
        if (ultimoTratado == null) {
            return;
        }
        try {
            checkpointer.checkpoint(ultimoTratado.sequenceNumber(), ultimoTratado.subSequenceNumber());
        } catch (InvalidStateException | ShutdownException e) {
            LOG.warn("checkpoint do shard {} não realizado; os registros serão reprocessados (idempotente)", shardId, e);
        }
    }

    private static void dormir(long milissegundos) {
        try {
            Thread.sleep(milissegundos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ProcessamentoInterrompido extends RuntimeException {
        ProcessamentoInterrompido() {
            super(null, null, false, false);
        }
    }
}
