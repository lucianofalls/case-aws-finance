package br.com.itau.financas.autorizacao.application.regras;

import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort.PedidoAoLimite;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.NovaAutorizacao;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;

import java.time.Clock;
import java.util.Set;

/**
 * CREDITO: devolve limite (ex.: pagamento de fatura). O Limites rejeita (422) crédito maior que o utilizado,
 * então esta regra só registra o fato aprovado.
 */
public final class RegraDeCredito implements RegraDeOperacao {

    private final OperacaoDeLimitePort limites;
    private final Clock relogio;

    public RegraDeCredito(OperacaoDeLimitePort limites, Clock relogio) {
        this.limites = limites;
        this.relogio = relogio;
    }

    @Override
    public Set<TipoOperacao> tiposAtendidos() {
        return Set.of(TipoOperacao.CREDITO);
    }

    @Override
    public Decisao decidir(NovaAutorizacao pedido) {
        var resultado = limites.aplicar(new PedidoAoLimite(pedido.id(), pedido.contratoId(), pedido.tipo(),
                pedido.valor(), pedido.impressao(), null, pedido.contaId().valor()));
        return Decisao.simples(Autorizacao.aprovada(pedido, resultado.posicao(), relogio.instant()));
    }
}
