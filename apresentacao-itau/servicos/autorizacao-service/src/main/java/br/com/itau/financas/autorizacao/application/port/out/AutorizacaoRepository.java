package br.com.itau.financas.autorizacao.application.port.out;

import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;

import java.util.Optional;

/**
 * Repository do agregado. Contrato que toda implementação deve respeitar (LSP):
 * <ul>
 *   <li>{@link #inserirSeAusente} é atômico e nunca sobrescreve: devolve false se o id já existe</li>
 *   <li>{@link #inserirComOriginalAtualizado} grava a autorização que referencia um original (ESTORNO, CAPTURA,
 *       LIBERACAO) e atualiza o original na mesma transação, com controle otimista
 *       de versão no original</li>
 * </ul>
 */
public interface AutorizacaoRepository {

    Optional<Autorizacao> buscar(AutorizacaoId id);

    boolean inserirSeAusente(Autorizacao nova);

    /**
     * @return false se o estorno já existia (repetição idempotente)
     * @throws br.com.itau.financas.autorizacao.application.ConflitoDeConcorrenciaException se o original mudou
     */
    boolean inserirComOriginalAtualizado(Autorizacao estorno, Autorizacao originalAtualizada);
}
