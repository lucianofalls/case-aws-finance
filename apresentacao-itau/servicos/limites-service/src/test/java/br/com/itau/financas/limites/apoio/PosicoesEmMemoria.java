package br.com.itau.financas.limites.apoio;

import br.com.itau.financas.limites.application.port.out.PosicaoRepository;
import br.com.itau.financas.limites.domain.EventoDeLimites;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;
import br.com.itau.financas.limites.domain.Operacao;
import br.com.itau.financas.limites.domain.Posicao;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mesmo contrato do DynamoDbPosicaoRepository (LSP): escrita condicional à versão lida, criação condicional
 * da operação e atomicidade (synchronized simula a transação).
 */
public final class PosicoesEmMemoria implements PosicaoRepository {

    private final Map<ContratoId, Posicao> posicoes = new ConcurrentHashMap<>();
    private final Map<OperacaoId, Operacao> operacoes = new ConcurrentHashMap<>();
    private final List<EventoDeLimites> outbox = new CopyOnWriteArrayList<>();

    public void com(Posicao posicao) {
        posicoes.put(posicao.contratoId(), posicao);
    }

    public List<EventoDeLimites> outbox() {
        return List.copyOf(outbox);
    }

    @Override
    public Optional<Posicao> buscarPosicao(ContratoId contratoId) {
        return Optional.ofNullable(posicoes.get(contratoId));
    }

    @Override
    public Optional<Operacao> buscarOperacao(ContratoId contratoId, OperacaoId operacaoId) {
        return Optional.ofNullable(operacoes.get(operacaoId));
    }

    @Override
    public synchronized ResultadoDeEscrita registrar(Posicao lida, Posicao nova, Operacao operacao) {
        if (posicoes.get(lida.contratoId()).versao() != lida.versao()) {
            return ResultadoDeEscrita.POSICAO_DESATUALIZADA;
        }
        if (operacoes.containsKey(operacao.id())) {
            return ResultadoDeEscrita.OPERACAO_JA_EXISTE;
        }
        posicoes.put(nova.contratoId(), nova);
        operacoes.put(operacao.id(), operacao);
        return ResultadoDeEscrita.GRAVADO;
    }

    @Override
    public synchronized ResultadoDeEscrita criar(Posicao nova, EventoDeLimites evento) {
        if (posicoes.putIfAbsent(nova.contratoId(), nova) != null) {
            return ResultadoDeEscrita.POSICAO_DESATUALIZADA;
        }
        outbox.add(evento);
        return ResultadoDeEscrita.GRAVADO;
    }

    @Override
    public synchronized ResultadoDeEscrita atualizarLimite(Posicao lida, Posicao nova, EventoDeLimites evento) {
        if (posicoes.get(lida.contratoId()).versao() != lida.versao()) {
            return ResultadoDeEscrita.POSICAO_DESATUALIZADA;
        }
        posicoes.put(nova.contratoId(), nova);
        outbox.add(evento);
        return ResultadoDeEscrita.GRAVADO;
    }

    @Override
    public synchronized ResultadoDeEscrita reverter(Posicao lida, Posicao nova, Operacao revertida,
                                                    EventoDeLimites evento) {
        if (posicoes.get(lida.contratoId()).versao() != lida.versao()) {
            return ResultadoDeEscrita.POSICAO_DESATUALIZADA;
        }
        if (operacoes.get(revertida.id()).estado() != Operacao.Estado.EFETIVADA) {
            return ResultadoDeEscrita.OPERACAO_JA_EXISTE;
        }
        posicoes.put(nova.contratoId(), nova);
        operacoes.put(revertida.id(), revertida);
        outbox.add(evento);
        return ResultadoDeEscrita.GRAVADO;
    }
}
