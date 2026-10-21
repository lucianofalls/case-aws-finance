package br.com.itau.financas.contabilizacao.adapters.in.kinesis;

/**
 * Destino de registros que falharam após as retentativas ("poison pill"). Sem isso, um único registro
 * ruim travaria o shard inteiro (o checkpoint do Kinesis é sequencial).
 */
public interface Quarentena {

    void enviar(String registro, String motivo);
}
