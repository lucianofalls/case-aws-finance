package br.com.itau.financas.autorizacao.application;

import br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacaoInterna;
import br.com.itau.financas.autorizacao.application.port.out.AutorizacaoRepository;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import br.com.itau.financas.autorizacao.domain.EstadoAutorizacao;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.time.Clock;
import java.util.UUID;

/**
 * Expiração da RESERVA (BIAN AmountBlock com data de expiração; ADR 035): na data de expiração, o EventBridge
 * Scheduler entrega o id da reserva e o saldo ainda bloqueado é liberado pelo caminho normal (LIBERACAO), com
 * auditoria, evento e contabilização iguais aos de uma liberação pedida pelo canal.
 * <p>
 * Idempotente: a Idempotency-Key é derivada do id da reserva, então reentregas da mensagem resultam na mesma
 * liberação (200). Se o canal capturou ou liberou tudo antes, não há o que liberar.
 */
public final class ExpirarReservaService {

    public enum Desfecho { LIBERADA, NADA_A_LIBERAR, INEXISTENTE }

    /** Cliente técnico das liberações por expiração (aparece na trilha no lugar do canal). */
    public static final String CLIENTE_EXPIRACAO = "sistema-expiracao-de-reservas";

    private final AutorizacaoRepository autorizacoes;
    private final AutorizarTransacaoInterna autorizar;
    private final Clock relogio;

    public ExpirarReservaService(AutorizacaoRepository autorizacoes, AutorizarTransacaoInterna autorizar, Clock relogio) {
        this.autorizacoes = autorizacoes;
        this.autorizar = autorizar;
        this.relogio = relogio;
    }

    public Desfecho expirar(String idReserva) {
        Optional<Autorizacao> encontrada = autorizacoes.buscar(AutorizacaoId.de(idReserva));
        if (encontrada.isEmpty()) {
            return Desfecho.INEXISTENTE;
        }
        Autorizacao reserva = encontrada.get();
        if (reserva.tipo() != TipoOperacao.RESERVA || reserva.estado() != EstadoAutorizacao.APROVADA
                || !reserva.saldoDaReserva().ehPositivo()) {
            return Desfecho.NADA_A_LIBERAR;
        }
        if (!reserva.reserva().orElseThrow().expirou(relogio.instant())) {
            // Não confirmar uma mensagem antecipada: o consumidor deve retentar.
            throw new IllegalStateException("reserva ainda não venceu");
        }
        UUID chave = UUID.nameUUIDFromBytes(("expiracao:" + idReserva + ":" + reserva.versao())
                .getBytes(StandardCharsets.UTF_8));
        autorizar.executarInternamente(new SolicitacaoDeAutorizacao(CLIENTE_EXPIRACAO, chave.toString(),
                reserva.contratoId().valor(), reserva.contaId().valor(), reserva.saldoDaReserva().valor(),
                reserva.valor().codigoMoeda(), TipoOperacao.LIBERACAO.name(), null, idReserva,
                reserva.rastreio().correlationId(), null));
        return Desfecho.LIBERADA;
    }
}
