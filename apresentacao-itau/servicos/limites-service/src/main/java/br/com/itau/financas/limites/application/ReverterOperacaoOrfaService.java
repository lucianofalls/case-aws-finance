package br.com.itau.financas.limites.application;

import br.com.itau.financas.limites.application.port.out.PosicaoRepository;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository.ResultadoDeEscrita;
import br.com.itau.financas.limites.domain.EventoDeLimites.OperacaoRevertida;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;
import br.com.itau.financas.limites.domain.Operacao;
import br.com.itau.financas.limites.domain.Posicao;

import java.time.Clock;
import java.util.Optional;

/**
 * Chamado pelo reconciliador quando uma operação foi aplicada no limite mas a autorização nunca foi
 * registrada (resultado incerto + cliente não retentou dentro da janela). Idempotente.
 */
public final class ReverterOperacaoOrfaService {

    public enum Desfecho { REVERTIDA, JA_REVERTIDA, NAO_ENCONTRADA }

    private final PosicaoRepository repositorio;
    private final PoliticaDeRetentativa retentativa;
    private final Clock relogio;

    public ReverterOperacaoOrfaService(PosicaoRepository repositorio, PoliticaDeRetentativa retentativa,
                                       Clock relogio) {
        this.repositorio = repositorio;
        this.retentativa = retentativa;
        this.relogio = relogio;
    }

    public Desfecho reverter(String idContrato, String idOperacao) {
        ContratoId contrato = new ContratoId(idContrato);
        OperacaoId id = OperacaoId.de(idOperacao);
        return retentativa.executar("reversão " + id, () -> {
            Optional<Operacao> operacao = repositorio.buscarOperacao(contrato, id);
            if (operacao.isEmpty()) {
                return Desfecho.NAO_ENCONTRADA;
            }
            if (operacao.get().estado() == Operacao.Estado.REVERTIDA) {
                return Desfecho.JA_REVERTIDA;
            }
            Posicao lida = repositorio.buscarPosicao(contrato).orElseThrow();
            Posicao nova = lida.reverter(operacao.get().tipo(), operacao.get().valor());
            OperacaoRevertida evento = new OperacaoRevertida(contrato, id, operacao.get().tipo(),
                    operacao.get().valor(), nova.disponivel(), relogio.instant());
            ResultadoDeEscrita escrita = repositorio.reverter(lida, nova, operacao.get().revertida(), evento);
            return escrita == ResultadoDeEscrita.GRAVADO ? Desfecho.REVERTIDA : null;
        });
    }
}
