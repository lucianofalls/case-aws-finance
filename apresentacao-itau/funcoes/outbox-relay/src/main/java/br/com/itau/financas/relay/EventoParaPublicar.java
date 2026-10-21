package br.com.itau.financas.relay;

import br.com.itau.financas.eventos.CloudEvent;

/**
 * @param sequencia número de sequência do registro no DynamoDB Streams (usado no retorno de falha parcial)
 */
public record EventoParaPublicar(String sequencia, CloudEvent evento, String json) {

    public String chaveDeParticao() {
        return evento.partitionkey() != null ? evento.partitionkey() : evento.subject();
    }
}
