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
import java.util.Optional;
import java.util.Set;

/**
 * ESTORNO: valida no agregado original (fail fast, sem tocar no limite), devolve o limite e
 * devolve o original atualizado para ser gravado na mesma transação.
 */
public final class RegraDeEstorno implements RegraDeOperacao {

    private final AutorizacaoRepository autorizacoes;
    private final OperacaoDeLimitePort limites;
    private final Clock relogio;

    public RegraDeEstorno(AutorizacaoRepository autorizacoes, OperacaoDeLimitePort limites, Clock relogio) {
        this.autorizacoes = autorizacoes;
        this.limites = limites;
        this.relogio = relogio;
    }

    @Override
    public Set<TipoOperacao> tiposAtendidos() {
        return Set.of(TipoOperacao.ESTORNO);
    }

    @Override
    public Decisao decidir(NovaAutorizacao pedido) {
        AutorizacaoId idOriginal = pedido.original().orElseThrow();
        Autorizacao original = autorizacoes.buscar(idOriginal)
                .orElseThrow(() -> new RegraDeNegocioVioladaException("original-inexistente",
                        "autorização original " + idOriginal + " não encontrada"));
        Autorizacao originalAtualizado = original.comEstorno(pedido.contratoId(), pedido.valor());

        var resultado = limites.aplicar(new PedidoAoLimite(pedido.id(), pedido.contratoId(), TipoOperacao.ESTORNO,
                pedido.valor(), pedido.impressao(), idOriginal, pedido.contaId().valor()));
        Autorizacao estorno = Autorizacao.aprovada(pedido, resultado.posicao(), relogio.instant());
        return new Decisao(estorno, Optional.of(originalAtualizado));
    }
}
