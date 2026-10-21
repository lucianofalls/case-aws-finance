package br.com.itau.financas.limites.application;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository.ResultadoDeEscrita;
import br.com.itau.financas.limites.domain.EventoDeLimites.LimiteAlterado;
import br.com.itau.financas.limites.domain.Excecoes.VersaoObsoletaException;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Posicao;

import java.time.Clock;
import java.util.Optional;

/**
 * Consome {@code LimiteConcedido} (publicado pela ACL do core banking no EventBridge → SQS).
 * Idempotente e tolerante a reordenação pela {@code versaoOrigem} monotônica do legado (C7).
 * Aplica o novo limite por <b>delta</b>: reservas em andamento continuam valendo.
 */
public final class AplicarLimiteDoLegadoService {

    public enum Desfecho { CRIADO, ATUALIZADO, IGNORADO_VERSAO_OBSOLETA }

    public record Comando(String idContrato, String idConta, String produto, String limite, String moeda,
                          long versaoOrigem) {
    }

    private final PosicaoRepository repositorio;
    private final PoliticaDeRetentativa retentativa;
    private final Clock relogio;

    public AplicarLimiteDoLegadoService(PosicaoRepository repositorio, PoliticaDeRetentativa retentativa,
                                        Clock relogio) {
        this.repositorio = repositorio;
        this.retentativa = retentativa;
        this.relogio = relogio;
    }

    public Desfecho aplicar(Comando comando) {
        ContratoId contrato = new ContratoId(comando.idContrato());
        Dinheiro limite = Dinheiro.de(comando.limite(), comando.moeda());
        return retentativa.executar("limite do contrato " + contrato.valor(), () -> {
            Optional<Posicao> atual = repositorio.buscarPosicao(contrato);
            return atual.isEmpty() ? criar(contrato, limite, comando) : atualizar(atual.get(), limite, comando);
        });
    }

    private Desfecho criar(ContratoId contrato, Dinheiro limite, Comando comando) {
        Posicao nova = Posicao.nova(contrato, comando.idConta(), comando.produto(), limite, comando.versaoOrigem());
        LimiteAlterado evento = new LimiteAlterado(contrato, Dinheiro.zero(limite.moeda()), limite, nova.disponivel(),
                comando.versaoOrigem(), relogio.instant());
        ResultadoDeEscrita escrita = repositorio.criar(nova, evento);
        return escrita == ResultadoDeEscrita.GRAVADO ? Desfecho.CRIADO : null;
    }

    private Desfecho atualizar(Posicao lida, Dinheiro limite, Comando comando) {
        Posicao nova;
        try {
            nova = lida.comNovoLimite(limite, comando.versaoOrigem());
        } catch (VersaoObsoletaException e) {
            return Desfecho.IGNORADO_VERSAO_OBSOLETA;
        }
        LimiteAlterado evento = new LimiteAlterado(lida.contratoId(), lida.limiteConcedido(), limite,
                nova.disponivel(), comando.versaoOrigem(), relogio.instant());
        ResultadoDeEscrita escrita = repositorio.atualizarLimite(lida, nova, evento);
        return escrita == ResultadoDeEscrita.GRAVADO ? Desfecho.ATUALIZADO : null;
    }
}
