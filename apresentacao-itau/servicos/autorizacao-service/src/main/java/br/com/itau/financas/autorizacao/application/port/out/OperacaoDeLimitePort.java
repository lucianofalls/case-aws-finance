package br.com.itau.financas.autorizacao.application.port.out;

import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.autorizacao.domain.ImpressaoDigital;
import br.com.itau.financas.autorizacao.domain.PosicaoDeLimite;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;
import br.com.itau.financas.kernel.Dinheiro;

/**
 * Port para o contexto Limites (relação Customer/Supplier, síncrona).
 * A operação é idempotente pelo {@code idOperacao}: reenviar nunca aplica duas vezes.
 */
public interface OperacaoDeLimitePort {

    ResultadoNoLimite aplicar(PedidoAoLimite pedido);

    record PedidoAoLimite(AutorizacaoId idOperacao, ContratoId contratoId, TipoOperacao tipo, Dinheiro valor,
                          ImpressaoDigital impressao, AutorizacaoId idOperacaoOriginal) {
    }

    sealed interface ResultadoNoLimite {
        PosicaoDeLimite posicao();
    }

    record Efetivada(PosicaoDeLimite posicao) implements ResultadoNoLimite {
    }

    record Insuficiente(PosicaoDeLimite posicao) implements ResultadoNoLimite {
    }
}
