package br.com.itau.financas.relay;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;

import java.util.Optional;

/** Strategy: como cada tabela carrega seus eventos no Stream (outbox embutido ou explícito). */
public interface ExtratorDeEventos {

    Optional<EventoParaPublicar> extrair(DynamodbStreamRecord registro);
}
