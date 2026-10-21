package br.com.itau.financas.reconciliador;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Toda operação gravada no Limites agenda a própria verificação (auditoria C01, ADR 032). A detecção de órfãs
 * deixa de depender da task da Autorização que fez a chamada: o gatilho é o Streams da tabela {@code limites},
 * at-least-once, com retentativa e DLQ. O reconciliador confere 15 min depois e reverte se não houver decisão.
 */
public final class AgendarVerificacoes {

    /** Operação recém-gravada no Limites; {@code idOperacao} é o id da autorização (ADR 027). */
    public record OperacaoGravada(String sequencia, String idContrato, String idOperacao) {
    }

    /** Fila com atraso de 15 min. Devolve as sequências que não foram aceitas. */
    public interface FilaDeVerificacao {
        Set<String> enviar(List<OperacaoGravada> operacoes);
    }

    private final FilaDeVerificacao fila;

    public AgendarVerificacoes(FilaDeVerificacao fila) {
        this.fila = fila;
    }

    /**
     * @return a primeira sequência que falhou, na ordem do lote: o Lambda retoma o shard a partir dela, sem pular
     * nada (as seguintes podem ser reagendadas; o reconciliador é idempotente)
     */
    public Optional<String> agendar(List<OperacaoGravada> operacoes) {
        if (operacoes.isEmpty()) {
            return Optional.empty();
        }
        Set<String> falhas = fila.enviar(operacoes);
        return operacoes.stream().map(OperacaoGravada::sequencia).filter(falhas::contains).findFirst();
    }
}
