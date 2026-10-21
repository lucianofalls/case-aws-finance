package br.com.itau.financas.limites.domain;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.domain.Excecoes.OperacaoEncerradaException;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Entidade Operação — Behavior Qualifier "Financial Transaction Capture" do BIAN Position Keeping.
 * Registra cada movimento aplicado na posição e guarda a fotografia da posição logo após aplicá-lo:
 * a repetição idempotente devolve exatamente o resultado original.
 */
public record Operacao(
        OperacaoId id,
        ContratoId contratoId,
        TipoOperacao tipo,
        Dinheiro valor,
        String impressao,
        OperacaoId idOperacaoOriginal,
        Estado estado,
        Instant registradaEm,
        Dinheiro saldoReservadoApos,
        Dinheiro disponivelApos) {

    public enum Estado { EFETIVADA, REVERTIDA }

    public Operacao {
        Objects.requireNonNull(id);
        Objects.requireNonNull(contratoId);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(valor);
        Objects.requireNonNull(impressao);
        Objects.requireNonNull(estado);
        Objects.requireNonNull(registradaEm);
        if (!valor.ehPositivo()) {
            throw new RegraDeNegocioVioladaException("valor-invalido", "valor deve ser maior que zero");
        }
    }

    public static Operacao efetivada(OperacaoId id, ContratoId contrato, TipoOperacao tipo, Dinheiro valor,
                                     String impressao, OperacaoId original, Posicao posicaoApos, Instant agora) {
        return new Operacao(id, contrato, tipo, valor, impressao, original, Estado.EFETIVADA, agora,
                posicaoApos.comprometido(), posicaoApos.disponivel());
    }

    /**
     * Repetição idempotente: mesmo conteúdo, dentro da janela, devolve o resultado original.
     * Operação revertida nunca é ressuscitada; repetição tardia é recusada (o reconciliador pode agir).
     */
    public Operacao garantirRepeticaoValida(String outraImpressao, Instant agora, Duration janelaDeRepeticao) {
        if (!impressao.equals(outraImpressao)) {
            throw new RegraDeNegocioVioladaException("operacao-divergente",
                    "id_operacao " + id + " já usado com outro conteúdo");
        }
        if (estado == Estado.REVERTIDA) {
            throw new OperacaoEncerradaException(id, "revertida pelo reconciliador");
        }
        if (registradaEm.plus(janelaDeRepeticao).isBefore(agora)) {
            throw new OperacaoEncerradaException(id,
                    "repetição fora da janela de " + janelaDeRepeticao.toMinutes() + " min");
        }
        return this;
    }

    public Operacao revertida() {
        return new Operacao(id, contratoId, tipo, valor, impressao, idOperacaoOriginal, Estado.REVERTIDA,
                registradaEm, saldoReservadoApos, disponivelApos);
    }

    public Optional<OperacaoId> original() {
        return Optional.ofNullable(idOperacaoOriginal);
    }
}
