package br.com.itau.financas.limites.application;

/** 409 — concorrência na reserva de limite não resolvida após as retentativas. */
public class ConflitoDeConcorrenciaException extends RuntimeException {

    public ConflitoDeConcorrenciaException(String mensagem) {
        super(mensagem);
    }
}
