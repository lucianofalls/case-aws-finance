package br.com.itau.financas.autorizacao.domain;

import br.com.itau.financas.kernel.Dinheiro;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/**
 * Estado do bloqueio de valor de uma RESERVA (BIAN Position Keeping {@code AmountBlock}: tipo pendente/final e data de
 * expiração). O saldo da reserva é o valor reservado menos o que já foi capturado ou liberado.
 */
public record MovimentosDaReserva(Instant expiraEm, Dinheiro capturado, Dinheiro liberado) {

    public MovimentosDaReserva {
        Objects.requireNonNull(expiraEm);
        Objects.requireNonNull(capturado);
        Objects.requireNonNull(liberado);
    }

    public static MovimentosDaReserva nova(Instant expiraEm, Currency moeda) {
        return new MovimentosDaReserva(expiraEm, Dinheiro.zero(moeda), Dinheiro.zero(moeda));
    }

    public Dinheiro movimentado() {
        return capturado.somar(liberado);
    }

    public boolean expirou(Instant agora) {
        return agora.isAfter(expiraEm);
    }

    MovimentosDaReserva com(TipoOperacao movimento, Dinheiro valor) {
        return switch (movimento) {
            case CAPTURA -> new MovimentosDaReserva(expiraEm, capturado.somar(valor), liberado);
            case LIBERACAO -> new MovimentosDaReserva(expiraEm, capturado, liberado.somar(valor));
            default -> throw new IllegalArgumentException(movimento + " não movimenta reserva");
        };
    }
}
