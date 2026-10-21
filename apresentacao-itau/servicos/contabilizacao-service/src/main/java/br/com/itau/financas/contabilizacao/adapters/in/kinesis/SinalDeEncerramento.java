package br.com.itau.financas.contabilizacao.adapters.in.kinesis;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Avisa os processadores de shard que a task está encerrando. O KCL chama {@code processRecords} e
 * {@code shutdownRequested} na mesma thread: um processador retido esperando a quarentena precisa deste sinal
 * para devolver o shard sem checkpoint (o próximo dono reprocessa; o inbox evita duplicar).
 */
public final class SinalDeEncerramento {

    private final AtomicBoolean encerrando = new AtomicBoolean();

    public void encerrar() {
        encerrando.set(true);
    }

    public boolean encerrando() {
        return encerrando.get();
    }
}
