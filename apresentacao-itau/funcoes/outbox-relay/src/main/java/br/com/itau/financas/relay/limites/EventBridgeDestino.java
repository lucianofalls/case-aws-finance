package br.com.itau.financas.relay.limites;

import br.com.itau.financas.relay.Destino;
import br.com.itau.financas.relay.EventoParaPublicar;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bus {@code financas}: eventos de integração entre domínios, baixo volume, roteados por regras.
 * Envelope EventBridge: {@code source} e {@code detail-type} para as regras; {@code detail} = CloudEvent inteiro.
 */
public final class EventBridgeDestino implements Destino {

    private static final int MAXIMO_POR_CHAMADA = 10;

    private final EventBridgeClient eventBridge;
    private final String barramento;
    private final String origem;

    public EventBridgeDestino(EventBridgeClient eventBridge, String barramento, String origem) {
        this.eventBridge = eventBridge;
        this.barramento = barramento;
        this.origem = origem;
    }

    @Override
    public Set<String> publicar(List<EventoParaPublicar> eventos) {
        Set<String> falhas = new HashSet<>();
        for (int inicio = 0; inicio < eventos.size(); inicio += MAXIMO_POR_CHAMADA) {
            List<EventoParaPublicar> lote = eventos.subList(inicio, Math.min(inicio + MAXIMO_POR_CHAMADA, eventos.size()));
            List<PutEventsResultEntry> resultados = eventBridge.putEvents(PutEventsRequest.builder()
                    .entries(lote.stream().map(this::entrada).toList())
                    .build()).entries();
            for (int i = 0; i < resultados.size(); i++) {
                if (resultados.get(i).errorCode() != null) {
                    falhas.add(lote.get(i).sequencia());
                }
            }
        }
        return falhas;
    }

    private PutEventsRequestEntry entrada(EventoParaPublicar evento) {
        return PutEventsRequestEntry.builder()
                .eventBusName(barramento)
                .source(origem)
                .detailType(evento.evento().type())
                .detail(evento.json())
                .build();
    }
}
