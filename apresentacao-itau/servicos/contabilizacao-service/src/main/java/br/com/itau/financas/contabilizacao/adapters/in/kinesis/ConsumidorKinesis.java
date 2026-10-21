package br.com.itau.financas.contabilizacao.adapters.in.kinesis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.kinesis.coordinator.Scheduler;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Liga o KCL ao ciclo de vida do Spring: inicia com a aplicação e faz shutdown gracioso (checkpoint final)
 * no deploy/scale-in da task ECS. O KCL distribui os shards entre as tasks via tabela de leases no DynamoDB.
 */
public final class ConsumidorKinesis implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumidorKinesis.class);
    private static final long PRAZO_SHUTDOWN_SEGUNDOS = 20;

    private final Scheduler scheduler;
    private final SinalDeEncerramento sinal;
    private volatile boolean rodando;

    public ConsumidorKinesis(Scheduler scheduler, SinalDeEncerramento sinal) {
        this.scheduler = scheduler;
        this.sinal = sinal;
    }

    @Override
    public void start() {
        Thread.ofPlatform().name("kcl-scheduler").daemon(true).start(scheduler);
        rodando = true;
    }

    @Override
    public void stop() {
        sinal.encerrar(); // libera processadores retidos esperando a quarentena (sem checkpoint)
        try {
            scheduler.startGracefulShutdown().get(PRAZO_SHUTDOWN_SEGUNDOS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("shutdown gracioso do KCL não concluiu no prazo", e);
        } finally {
            rodando = false;
        }
    }

    @Override
    public boolean isRunning() {
        return rodando;
    }
}
