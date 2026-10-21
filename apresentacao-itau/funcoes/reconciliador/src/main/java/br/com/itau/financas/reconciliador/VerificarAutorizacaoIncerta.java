package br.com.itau.financas.reconciliador;

/**
 * Caso de uso do reconciliador (contexto Autorização), executado 15 min depois de uma autorização incerta:
 * <ul>
 *   <li>a autorização foi registrada (o cliente retentou) → nada a fazer</li>
 *   <li>não foi registrada → pede ao Limites para reverter a operação órfã (idempotente)</li>
 * </ul>
 * Sem corrida com a retentativa do cliente: o Limites recusa repetições após 10 min (janelas disjuntas, ADR 027).
 */
public final class VerificarAutorizacaoIncerta {

    public enum Desfecho { AUTORIZACAO_REGISTRADA, OPERACAO_REVERTIDA, NADA_A_REVERTER }

    public interface ConsultaDeAutorizacoes {
        boolean existe(String idAutorizacao);
    }

    public interface ReversaoNoLimite {
        /** @return desfecho informado pelo Limites: REVERTIDA, JA_REVERTIDA ou NAO_ENCONTRADA */
        String reverter(String idContrato, String idOperacao);
    }

    private final ConsultaDeAutorizacoes autorizacoes;
    private final ReversaoNoLimite limites;

    public VerificarAutorizacaoIncerta(ConsultaDeAutorizacoes autorizacoes, ReversaoNoLimite limites) {
        this.autorizacoes = autorizacoes;
        this.limites = limites;
    }

    public Desfecho verificar(String idAutorizacao, String idContrato) {
        if (autorizacoes.existe(idAutorizacao)) {
            return Desfecho.AUTORIZACAO_REGISTRADA;
        }
        String resposta = limites.reverter(idContrato, idAutorizacao);
        return switch (resposta) {
            case "REVERTIDA" -> Desfecho.OPERACAO_REVERTIDA;
            case "JA_REVERTIDA", "NAO_ENCONTRADA" -> Desfecho.NADA_A_REVERTER;
            default -> throw new IllegalStateException("desfecho inesperado ao reverter operação órfã: " + resposta);
        };
    }
}
