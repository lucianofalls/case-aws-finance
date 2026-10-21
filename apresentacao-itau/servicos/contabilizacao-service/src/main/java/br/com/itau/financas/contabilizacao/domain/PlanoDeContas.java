package br.com.itau.financas.contabilizacao.domain;

import br.com.itau.financas.contabilizacao.domain.Lancamento.ContaContabil;
import br.com.itau.financas.contabilizacao.domain.Lancamento.Natureza;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Domain Service: regras de contabilização por tipo de fato (chart of accounts do BIAN Financial Accounting).
 * Plano de contas ilustrativo — o real segue o COSIF e é dono da área contábil.
 * <ul>
 *   <li>DEBITO autorizado: D clientes a receber / C obrigações com estabelecimentos</li>
 *   <li>CREDITO autorizado: D disponibilidades / C clientes a receber</li>
 *   <li>RESERVA e LIBERACAO: bloqueio e desbloqueio de limite não são fatos contábeis (sem lançamento)</li>
 *   <li>CAPTURA autorizada: a reserva vira consumo — mesmos lançamentos do DEBITO (ADR 035)</li>
 *   <li>ESTORNO: inverso do débito</li>
 * </ul>
 */
public final class PlanoDeContas {

    public static final ContaContabil CLIENTES_A_RECEBER =
            new ContaContabil("1.6.1.10", "Operações de crédito — clientes a receber");
    public static final ContaContabil OBRIGACOES_COM_ESTABELECIMENTOS =
            new ContaContabil("4.9.9.30", "Obrigações a liquidar com estabelecimentos");
    public static final ContaContabil DISPONIBILIDADES =
            new ContaContabil("1.1.1.10", "Disponibilidades");

    private static final ZoneId FUSO_CONTABIL = ZoneId.of("America/Sao_Paulo");

    public Optional<RegistroContabil> contabilizar(FatoContabil fato) {
        return switch (fato.tipo()) {
            case TRANSACAO_AUTORIZADA -> switch (fato.tipoOperacao()) {
                case "DEBITO", "CAPTURA" ->
                        Optional.of(registro(fato, CLIENTES_A_RECEBER, OBRIGACOES_COM_ESTABELECIMENTOS));
                case "CREDITO" -> Optional.of(registro(fato, DISPONIBILIDADES, CLIENTES_A_RECEBER));
                case "RESERVA", "LIBERACAO" -> Optional.empty();
                default -> throw new IllegalArgumentException("tipo de operação sem regra contábil: "
                        + fato.tipoOperacao());
            };
            case ESTORNO_REALIZADO -> Optional.of(registro(fato, OBRIGACOES_COM_ESTABELECIMENTOS, CLIENTES_A_RECEBER));
        };
    }

    private static RegistroContabil registro(FatoContabil fato, ContaContabil devedora, ContaContabil credora) {
        return new RegistroContabil(fato.eventoId(), fato.idAutorizacao(), fato.idContrato(),
                fato.ocorridoEm().atZone(FUSO_CONTABIL).toLocalDate(),
                List.of(new Lancamento(devedora, Natureza.DEVEDORA, fato.valor()),
                        new Lancamento(credora, Natureza.CREDORA, fato.valor())));
    }
}
