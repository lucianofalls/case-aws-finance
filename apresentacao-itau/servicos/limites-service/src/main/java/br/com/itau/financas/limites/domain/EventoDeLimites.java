package br.com.itau.financas.limites.domain;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;

import java.time.Instant;

/**
 * Domain Events de baixo volume do contexto Limites, publicados no EventBridge via outbox explícito.
 * Movimentos de alto volume (cada reserva) não viram evento próprio: o fato de negócio já vai no
 * TransacaoAutorizada (Kinesis) com o saldo resultante — evita ~3,9 bi de eventos/mês (docs/custos.md).
 */
public sealed interface EventoDeLimites {

    ContratoId contratoId();

    Instant ocorridoEm();

    record LimiteAlterado(ContratoId contratoId, Dinheiro limiteAnterior, Dinheiro limiteNovo, Dinheiro disponivel,
                          long versaoOrigem, Instant ocorridoEm) implements EventoDeLimites {
    }

    record OperacaoRevertida(ContratoId contratoId, OperacaoId operacaoId, TipoOperacao tipo, Dinheiro valor,
                             Dinheiro disponivel, Instant ocorridoEm) implements EventoDeLimites {
    }
}
