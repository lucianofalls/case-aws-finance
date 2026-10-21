package br.com.itau.financas.contabilizacao.application;

import br.com.itau.financas.contabilizacao.application.port.out.RegistroContabilRepository;
import br.com.itau.financas.contabilizacao.domain.FatoContabil;
import br.com.itau.financas.contabilizacao.domain.PlanoDeContas;
import br.com.itau.financas.contabilizacao.domain.RegistroContabil;

import java.util.Optional;

/** Caso de uso — BIAN Action Term "Capture" sobre Ledger Posting. Consumidor idempotente (PDF §2.4). */
public final class ContabilizarFatoService {

    public enum Desfecho { CONTABILIZADO, SEM_EFEITO_CONTABIL, DUPLICADO }

    private final PlanoDeContas planoDeContas;
    private final RegistroContabilRepository repositorio;

    public ContabilizarFatoService(PlanoDeContas planoDeContas, RegistroContabilRepository repositorio) {
        this.planoDeContas = planoDeContas;
        this.repositorio = repositorio;
    }

    public Desfecho contabilizar(FatoContabil fato) {
        Optional<RegistroContabil> registro = planoDeContas.contabilizar(fato);
        if (!repositorio.registrarSeInedito(fato.eventoId(), registro)) {
            return Desfecho.DUPLICADO;
        }
        return registro.isPresent() ? Desfecho.CONTABILIZADO : Desfecho.SEM_EFEITO_CONTABIL;
    }
}
