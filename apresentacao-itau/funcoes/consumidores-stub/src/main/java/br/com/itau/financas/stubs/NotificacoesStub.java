package br.com.itau.financas.stubs;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.TiposDeEvento;
import org.slf4j.Logger;

import java.util.Set;

/** BIAN Correspondence — no real publicaria em SNS (push/SMS); o stub só registra. */
public final class NotificacoesStub extends ConsumidorDeTransacoes {

    @Override
    protected Set<String> tiposDeInteresse() {
        return Set.of(TiposDeEvento.TRANSACAO_AUTORIZADA, TiposDeEvento.ESTORNO_REALIZADO);
    }

    @Override
    protected void reagir(CloudEvent evento, Logger log) {
        log.info("[stub notificações] cliente notificado sobre {} ({})", evento.type(), evento.id());
    }
}
