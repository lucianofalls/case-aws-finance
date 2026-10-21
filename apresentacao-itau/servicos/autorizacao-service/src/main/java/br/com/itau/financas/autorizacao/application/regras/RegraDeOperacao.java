package br.com.itau.financas.autorizacao.application.regras;

import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.NovaAutorizacao;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;

import java.util.Optional;
import java.util.Set;

/**
 * Strategy por {@code tipo_operacao} (OCP): um tipo novo é uma nova regra, sem tocar no caso de uso.
 */
public interface RegraDeOperacao {

    Set<TipoOperacao> tiposAtendidos();

    Decisao decidir(NovaAutorizacao pedido);

    /**
     * @param originalAtualizado presente apenas no estorno: o agregado original com o estorno aplicado,
     *                           gravado na mesma transação da autorização de estorno
     */
    record Decisao(Autorizacao autorizacao, Optional<Autorizacao> originalAtualizado) {

        public static Decisao simples(Autorizacao autorizacao) {
            return new Decisao(autorizacao, Optional.empty());
        }
    }
}
