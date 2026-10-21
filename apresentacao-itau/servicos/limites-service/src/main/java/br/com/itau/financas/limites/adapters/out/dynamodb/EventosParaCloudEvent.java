package br.com.itau.financas.limites.adapters.out.dynamodb;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import br.com.itau.financas.limites.domain.EventoDeLimites;
import br.com.itau.financas.limites.domain.EventoDeLimites.LimiteAlterado;
import br.com.itau.financas.limites.domain.EventoDeLimites.OperacaoRevertida;
import org.slf4j.MDC;

/** Tradução Domain Event → linguagem publicada (CloudEvents v1). O domínio não conhece o contrato externo. */
final class EventosParaCloudEvent {

    private EventosParaCloudEvent() {
    }

    /** Correlação da requisição ou do evento que originou a mudança; ausente → o codec usa o id do evento. */
    private static String correlacaoAtual() {
        return MDC.get("correlation_id");
    }

    static CloudEvent converter(EventoDeLimites evento) {
        String contrato = evento.contratoId().valor();
        return switch (evento) {
            case LimiteAlterado e -> CodecDeEventos.novo(TiposDeEvento.FONTE_LIMITES, TiposDeEvento.LIMITE_ALTERADO,
                    e.ocorridoEm(), "contrato/" + contrato, contrato, correlacaoAtual(), null,
                    new PayloadsV1.LimiteAlterado(contrato, e.limiteAnterior().comoTexto(),
                            e.limiteNovo().comoTexto(), e.disponivel().comoTexto(), e.limiteNovo().codigoMoeda(),
                            e.versaoOrigem()));
            case OperacaoRevertida e -> CodecDeEventos.novo(TiposDeEvento.FONTE_LIMITES,
                    TiposDeEvento.OPERACAO_REVERTIDA, e.ocorridoEm(), "contrato/" + contrato, contrato, correlacaoAtual(), null,
                    new PayloadsV1.OperacaoRevertida(contrato, e.operacaoId().toString(), e.tipo().name(),
                            e.valor().comoTexto(), e.valor().codigoMoeda(), e.disponivel().comoTexto()));
        };
    }
}
