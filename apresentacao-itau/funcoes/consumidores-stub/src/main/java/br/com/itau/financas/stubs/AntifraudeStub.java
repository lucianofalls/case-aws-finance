package br.com.itau.financas.stubs;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.TiposDeEvento;
import org.slf4j.Logger;

import java.util.Set;

/** BIAN Fraud Evaluation — modo "transaction logs" (assíncrono, fora do caminho de 5k TPS). */
public final class AntifraudeStub extends ConsumidorDeTransacoes {

    @Override
    protected Set<String> tiposDeInteresse() {
        return Set.of(TiposDeEvento.TRANSACAO_AUTORIZADA, TiposDeEvento.TRANSACAO_RECUSADA);
    }

    @Override
    protected void reagir(CloudEvent evento, Logger log) {
        log.info("[stub antifraude] avaliado evento {} do contrato {} (correlation_id {})",
                evento.id(), evento.partitionkey(), evento.correlationid());
    }
}
