package br.com.itau.financas.autorizacao.application.regras;

import br.com.itau.financas.autorizacao.application.port.out.AutorizacaoRepository;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort.PedidoAoLimite;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import br.com.itau.financas.autorizacao.domain.NovaAutorizacao;
import br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * CAPTURA e LIBERACAO de uma RESERVA (BIAN AmountBlock pendente → final ou liberado; ISO 20022 completion e
 * cancellation). Mesmo molde do estorno: valida no agregado original sem tocar no limite (fail fast), move o
 * bloqueado no Limites e devolve a reserva atualizada para ser gravada na mesma transação (ADR 035).
 */
public final class RegraDeMovimentoDaReserva implements RegraDeOperacao {

    private final AutorizacaoRepository autorizacoes;
    private final OperacaoDeLimitePort limites;
    private final Clock relogio;

    public RegraDeMovimentoDaReserva(AutorizacaoRepository autorizacoes, OperacaoDeLimitePort limites, Clock relogio) {
        this.autorizacoes = autorizacoes;
        this.limites = limites;
        this.relogio = relogio;
    }

    @Override
    public Set<TipoOperacao> tiposAtendidos() {
        return Set.of(TipoOperacao.CAPTURA, TipoOperacao.LIBERACAO);
    }

    @Override
    public Decisao decidir(NovaAutorizacao pedido) {
        Instant agora = relogio.instant();
        AutorizacaoId idReserva = pedido.original().orElseThrow();
        Autorizacao reserva = autorizacoes.buscar(idReserva)
                .orElseThrow(() -> new RegraDeNegocioVioladaException("original-inexistente",
                        "reserva " + idReserva + " não encontrada"));
        Autorizacao reservaAtualizada = reserva.comMovimentoDaReserva(pedido.contratoId(), pedido.tipo(),
                pedido.valor(), agora);

        var resultado = limites.aplicar(new PedidoAoLimite(pedido.id(), pedido.contratoId(), pedido.tipo(),
                pedido.valor(), pedido.impressao(), idReserva, pedido.contaId().valor()));
        Autorizacao movimento = Autorizacao.aprovada(pedido, resultado.posicao(), agora);
        return new Decisao(movimento, Optional.of(reservaAtualizada));
    }
}
