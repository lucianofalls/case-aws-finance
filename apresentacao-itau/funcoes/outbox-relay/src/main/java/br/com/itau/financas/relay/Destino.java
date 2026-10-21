package br.com.itau.financas.relay;

import java.util.List;
import java.util.Set;

/** Port de saída do relay: Kinesis (alto volume) ou EventBridge (integração entre domínios). */
public interface Destino {

    /** @return sequências dos eventos que NÃO foram aceitos pelo destino */
    Set<String> publicar(List<EventoParaPublicar> eventos);
}
