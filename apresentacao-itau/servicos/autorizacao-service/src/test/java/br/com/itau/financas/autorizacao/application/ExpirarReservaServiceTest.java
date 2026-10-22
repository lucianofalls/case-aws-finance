package br.com.itau.financas.autorizacao.application;

import br.com.itau.financas.autorizacao.apoio.Fakes.AutorizacoesEmMemoria;
import br.com.itau.financas.autorizacao.apoio.Fakes.LimitesFalso;
import br.com.itau.financas.autorizacao.application.ExpirarReservaService.Desfecho;
import br.com.itau.financas.autorizacao.application.regras.RegraDeConsumoDeLimite;
import br.com.itau.financas.autorizacao.application.regras.RegraDeCredito;
import br.com.itau.financas.autorizacao.application.regras.RegraDeEstorno;
import br.com.itau.financas.autorizacao.application.regras.RegraDeMovimentoDaReserva;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.EstadoAutorizacao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 035: na expiração, o saldo ainda bloqueado da reserva volta ao disponível, uma única vez. */
class ExpirarReservaServiceTest {

    private final Clock relogio = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    private AutorizacoesEmMemoria autorizacoes;
    private LimitesFalso limites;
    private AutorizarTransacaoService autorizar;
    private ExpirarReservaService expirar;

    @BeforeEach
    void preparar() {
        autorizacoes = new AutorizacoesEmMemoria();
        limites = new LimitesFalso("100.00");
        autorizar = new AutorizarTransacaoService(autorizacoes, List.of(
                new RegraDeConsumoDeLimite(limites, relogio, Duration.ofDays(7)),
                new RegraDeCredito(limites, relogio),
                new RegraDeEstorno(autorizacoes, limites, relogio),
                new RegraDeMovimentoDaReserva(autorizacoes, limites, relogio)), (id, ctr, conta) -> {});
        expirar = new ExpirarReservaService(autorizacoes, autorizar, Clock.offset(relogio, Duration.ofDays(7)));
    }

    @Test
    void liberaOSaldoRestanteEEncerraAReserva() {
        Autorizacao reserva = autorizar.executar(pedido("40.00", "RESERVA", null)).autorizacao();
        autorizar.executar(pedido("25.00", "CAPTURA", reserva.id().toString()));

        Desfecho desfecho = expirar.expirar(reserva.id().toString());

        assertThat(desfecho).isEqualTo(Desfecho.LIBERADA);
        assertThat(autorizacoes.buscar(reserva.id()).orElseThrow().estado()).isEqualTo(EstadoAutorizacao.ENCERRADA);
        assertThat(limites.disponivel().comoTexto()).as("100 − 40 reservados + 15 liberados").isEqualTo("75.00");
    }

    @Test
    void reentregaDaMensagemNaoLiberaDuasVezes() {
        Autorizacao reserva = autorizar.executar(pedido("40.00", "RESERVA", null)).autorizacao();

        expirar.expirar(reserva.id().toString());
        Desfecho segunda = expirar.expirar(reserva.id().toString());

        assertThat(segunda).isEqualTo(Desfecho.NADA_A_LIBERAR);
        assertThat(limites.disponivel().comoTexto()).isEqualTo("100.00");
    }

    @Test
    void reservaJaTodaCapturadaNaoTemOQueLiberar() {
        Autorizacao reserva = autorizar.executar(pedido("40.00", "RESERVA", null)).autorizacao();
        autorizar.executar(pedido("40.00", "CAPTURA", reserva.id().toString()));

        assertThat(expirar.expirar(reserva.id().toString())).isEqualTo(Desfecho.NADA_A_LIBERAR);
    }

    @Test
    void reservaInexistenteEhIgnorada() {
        assertThat(expirar.expirar(UUID.randomUUID().toString())).isEqualTo(Desfecho.INEXISTENTE);
    }

    @Test
    void mensagemAntecipadaNaoLiberaNemConfirma() {
        var reserva = autorizar.executar(pedido("40.00", "RESERVA", null)).autorizacao();
        var antecipada = new ExpirarReservaService(autorizacoes, autorizar, relogio);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> antecipada.expirar(reserva.id().toString()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(limites.disponivel().comoTexto()).isEqualTo("60.00");
    }

    @Test
    void capturaConcorrenteEntreLeituraELiberacaoPermiteRetentarSaldoAtual() {
        var reserva = autorizar.executar(pedido("40.00", "RESERVA", null)).autorizacao();
        var primeira = new java.util.concurrent.atomic.AtomicBoolean(true);
        var concorrente = new ExpirarReservaService(autorizacoes, solicitacao -> {
            if (primeira.getAndSet(false)) {
                // Captura já admitida antes do prazo confirma entre leitura e escrita da expiração.
                autorizar.executar(pedido("25.00", "CAPTURA", reserva.id().toString()));
            }
            return autorizar.executarInternamente(solicitacao);
        }, Clock.offset(relogio, Duration.ofDays(7)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> concorrente.expirar(reserva.id().toString()))
                .isInstanceOf(br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException.class);
        assertThat(concorrente.expirar(reserva.id().toString())).isEqualTo(Desfecho.LIBERADA);
        assertThat(concorrente.expirar(reserva.id().toString())).isEqualTo(Desfecho.NADA_A_LIBERAR);
        assertThat(limites.disponivel().comoTexto()).isEqualTo("75.00");
    }

    private static SolicitacaoDeAutorizacao pedido(String valor, String tipo, String original) {
        return new SolicitacaoDeAutorizacao("canal-app", UUID.randomUUID().toString(), "CTR-1", "0001-123",
                new BigDecimal(valor), "BRL", tipo, null, original, "corr", null);
    }
}
