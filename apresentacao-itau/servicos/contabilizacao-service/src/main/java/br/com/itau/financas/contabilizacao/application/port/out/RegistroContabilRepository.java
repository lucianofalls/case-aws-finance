package br.com.itau.financas.contabilizacao.application.port.out;

import br.com.itau.financas.contabilizacao.domain.RegistroContabil;

import java.util.Optional;
import java.util.UUID;

/**
 * Inbox + razão na mesma transação: marca o evento como processado e grava os lançamentos
 * atomicamente. Devolve false se o evento já tinha sido processado (reentrega).
 */
public interface RegistroContabilRepository {

    boolean registrarSeInedito(UUID eventoId, Optional<RegistroContabil> registro);
}
