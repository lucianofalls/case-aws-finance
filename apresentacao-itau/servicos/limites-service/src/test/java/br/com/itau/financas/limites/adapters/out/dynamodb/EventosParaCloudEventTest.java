package br.com.itau.financas.limites.adapters.out.dynamodb;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.domain.EventoDeLimites.LimiteAlterado;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventosParaCloudEventTest {

    private static final LimiteAlterado LIMITE_ALTERADO = new LimiteAlterado(new ContratoId("CTR-1"),
            Dinheiro.de("1000.00", "BRL"), Dinheiro.de("1500.00", "BRL"), Dinheiro.de("1500.00", "BRL"), 2,
            Instant.parse("2026-10-21T12:00:00Z"));

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    void eventoContinuaACadeiaDeRastreioDaRequisicao() {
        MDC.put("correlation_id", "corr-da-autorizacao");

        CloudEvent evento = EventosParaCloudEvent.converter(LIMITE_ALTERADO);

        assertThat(evento.correlationid()).isEqualTo("corr-da-autorizacao");
    }

    @Test
    void semCorrelacaoRecebidaOEventoAbreUmaCadeiaNova() {
        CloudEvent evento = EventosParaCloudEvent.converter(LIMITE_ALTERADO);

        assertThat(evento.correlationid()).isEqualTo(evento.id());
    }
}
