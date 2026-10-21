package br.com.itau.financas.limites.application.port.out;

import br.com.itau.financas.limites.domain.EventoDeLimites;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;
import br.com.itau.financas.limites.domain.Operacao;
import br.com.itau.financas.limites.domain.Posicao;

import java.util.Optional;

/**
 * Repository do agregado Posição e das suas Operações (mesma partição: id_contrato).
 * Toda escrita é condicional na {@code versao} lida (controle otimista): nunca há lost update.
 */
public interface PosicaoRepository {

    enum ResultadoDeEscrita {
        GRAVADO,
        /** outra escrita mudou a posição depois da leitura: reler e tentar de novo */
        POSICAO_DESATUALIZADA,
        /** a operação já existe (repetição concorrente com o mesmo id) */
        OPERACAO_JA_EXISTE,
        /** transação concorrente no mesmo item (TransactionConflict do DynamoDB): tentar de novo */
        CONFLITO_TRANSACIONAL
    }

    Optional<Posicao> buscarPosicao(ContratoId contratoId);

    Optional<Operacao> buscarOperacao(ContratoId contratoId, OperacaoId operacaoId);

    /** Grava a nova posição e a operação atomicamente. */
    ResultadoDeEscrita registrar(Posicao lida, Posicao nova, Operacao operacao);

    /** Cria a posição de um contrato novo e o evento (outbox) atomicamente. */
    ResultadoDeEscrita criar(Posicao nova, EventoDeLimites evento);

    /** Aplica o limite vindo do legado e o evento (outbox) atomicamente. */
    ResultadoDeEscrita atualizarLimite(Posicao lida, Posicao nova, EventoDeLimites evento);

    /** Reverte uma operação órfã: posição + estado da operação + evento, atomicamente. */
    ResultadoDeEscrita reverter(Posicao lida, Posicao nova, Operacao revertida, EventoDeLimites evento);
}
