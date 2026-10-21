package br.com.itau.financas.autorizacao.application;

/** Concorrência não resolvida após as retentativas → HTTP 409 (PDF: "concorrência na reserva de limite"). */
public class ConflitoDeConcorrenciaException extends RuntimeException {

    public ConflitoDeConcorrenciaException(String mensagem) {
        super(mensagem);
    }
}
