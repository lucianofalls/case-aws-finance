package br.com.itau.financas.autorizacao.apoio;

import br.com.itau.financas.autorizacao.application.ConflitoDeConcorrenciaException;
import br.com.itau.financas.autorizacao.application.port.out.AutorizacaoRepository;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.autorizacao.domain.PosicaoDeLimite;
import br.com.itau.financas.kernel.Dinheiro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Implementações em memória dos ports — respeitam o mesmo contrato dos adapters reais (LSP). */
public final class Fakes {

    private Fakes() {
    }

    public static final class AutorizacoesEmMemoria implements AutorizacaoRepository {

        private final Map<AutorizacaoId, Autorizacao> itens = new ConcurrentHashMap<>();
        private RuntimeException falhaNaGravacao;

        public void falharGravacaoCom(RuntimeException falha) {
            this.falhaNaGravacao = falha;
        }

        public int quantidade() {
            return itens.size();
        }

        @Override
        public Optional<Autorizacao> buscar(AutorizacaoId id) {
            return Optional.ofNullable(itens.get(id));
        }

        @Override
        public boolean inserirSeAusente(Autorizacao nova) {
            if (falhaNaGravacao != null) {
                throw falhaNaGravacao;
            }
            return itens.putIfAbsent(nova.id(), nova) == null;
        }

        @Override
        public synchronized boolean inserirComOriginalAtualizado(Autorizacao estorno, Autorizacao originalAtualizado) {
            if (itens.containsKey(estorno.id())) {
                return false;
            }
            Autorizacao atual = itens.get(originalAtualizado.id());
            if (atual.versao() != originalAtualizado.versao() - 1) {
                throw new ConflitoDeConcorrenciaException("original alterado");
            }
            itens.put(estorno.id(), estorno);
            itens.put(originalAtualizado.id(), originalAtualizado);
            return true;
        }
    }

    /** Limites simulado com saldo real e idempotência por id de operação. */
    public static final class LimitesFalso implements OperacaoDeLimitePort {

        private Dinheiro disponivel;
        private final Dinheiro concedido;
        private final Map<AutorizacaoId, ResultadoNoLimite> aplicadas = new ConcurrentHashMap<>();
        private final AtomicInteger chamadas = new AtomicInteger();
        private Function<PedidoAoLimite, RuntimeException> falha;

        public LimitesFalso(String concedido) {
            this.concedido = Dinheiro.de(concedido, "BRL");
            this.disponivel = this.concedido;
        }

        public void falharCom(Function<PedidoAoLimite, RuntimeException> falha) {
            this.falha = falha;
        }

        public int chamadas() {
            return chamadas.get();
        }

        public Dinheiro disponivel() {
            return disponivel;
        }

        @Override
        public synchronized ResultadoNoLimite aplicar(PedidoAoLimite pedido) {
            chamadas.incrementAndGet();
            if (falha != null) {
                throw falha.apply(pedido);
            }
            return aplicadas.computeIfAbsent(pedido.idOperacao(), id -> efetivar(pedido));
        }

        private ResultadoNoLimite efetivar(PedidoAoLimite pedido) {
            switch (pedido.tipo()) {
                case DEBITO, RESERVA -> {
                    if (disponivel.menorQue(pedido.valor())) {
                        return new Insuficiente(posicao());
                    }
                    disponivel = disponivel.subtrair(pedido.valor());
                }
                case CAPTURA -> { } // bloqueado → utilizado: o comprometido e o disponível não mudam
                case LIBERACAO, CREDITO, ESTORNO -> disponivel = disponivel.somar(pedido.valor());
            }
            return new Efetivada(posicao());
        }

        private PosicaoDeLimite posicao() {
            return new PosicaoDeLimite(concedido.subtrair(disponivel), disponivel);
        }
    }
}
