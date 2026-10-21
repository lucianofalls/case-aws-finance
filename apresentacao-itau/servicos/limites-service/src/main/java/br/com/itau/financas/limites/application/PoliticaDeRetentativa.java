package br.com.itau.financas.limites.application;

import java.util.function.Supplier;

/**
 * Retentativa com backoff exponencial + jitter para conflitos de escrita no mesmo contrato.
 * Esgotadas as tentativas → {@link ConflitoDeConcorrenciaException} (HTTP 409).
 */
public final class PoliticaDeRetentativa {

    @FunctionalInterface
    public interface Espera {
        void aguardar(int tentativa);
    }

    private final int maximoDeTentativas;
    private final Espera espera;

    public PoliticaDeRetentativa(int maximoDeTentativas, Espera espera) {
        if (maximoDeTentativas < 1) {
            throw new IllegalArgumentException("ao menos 1 tentativa");
        }
        this.maximoDeTentativas = maximoDeTentativas;
        this.espera = espera;
    }

    /**
     * Executa {@code tentativa} até ela devolver um resultado definitivo (não nulo).
     * A tentativa devolve null para pedir nova rodada (posição desatualizada ou conflito transacional).
     */
    public <T> T executar(String contexto, Supplier<T> tentativa) {
        for (int numero = 1; numero <= maximoDeTentativas; numero++) {
            T resultado = tentativa.get();
            if (resultado != null) {
                return resultado;
            }
            if (numero < maximoDeTentativas) {
                espera.aguardar(numero);
            }
        }
        throw new ConflitoDeConcorrenciaException(contexto + ": concorrência não resolvida após "
                + maximoDeTentativas + " tentativas");
    }
}
