package br.com.itau.financas.autorizacao.application.regras;

import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort.Efetivada;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort.Insuficiente;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort.PedidoAoLimite;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.NovaAutorizacao;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * DEBITO e RESERVA: aprova se o Limites conseguiu retirar o valor do disponível; senão recusa (402).
 * A RESERVA aprovada fica bloqueada até a captura, a liberação ou a expiração ({@code validadeDaReserva}).
 */
public final class RegraDeConsumoDeLimite implements RegraDeOperacao {

    private final OperacaoDeLimitePort limites;
    private final Clock relogio;
    private final Duration validadeDaReserva;

    public RegraDeConsumoDeLimite(OperacaoDeLimitePort limites, Clock relogio, Duration validadeDaReserva) {
        this.limites = limites;
        this.relogio = relogio;
        this.validadeDaReserva = validadeDaReserva;
    }

    @Override
    public Set<TipoOperacao> tiposAtendidos() {
        return Set.of(TipoOperacao.DEBITO, TipoOperacao.RESERVA);
    }

    @Override
    public Decisao decidir(NovaAutorizacao pedido) {
        var resultado = limites.aplicar(new PedidoAoLimite(pedido.id(), pedido.contratoId(), pedido.tipo(),
                pedido.valor(), pedido.impressao(), null));
        Autorizacao autorizacao = switch (resultado) {
            case Efetivada efetivada -> pedido.tipo() == TipoOperacao.RESERVA
                    ? Autorizacao.reservaAprovada(pedido, efetivada.posicao(), relogio.instant(), validadeDaReserva)
                    : Autorizacao.aprovada(pedido, efetivada.posicao(), relogio.instant());
            case Insuficiente insuficiente -> Autorizacao.recusada(pedido,
                    Autorizacao.MotivoRecusa.LIMITE_INSUFICIENTE, insuficiente.posicao(), relogio.instant());
        };
        return Decisao.simples(autorizacao);
    }
}
