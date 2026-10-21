package br.com.itau.financas.autorizacao.application;

import br.com.itau.financas.autorizacao.apoio.Fakes.AutorizacoesEmMemoria;
import br.com.itau.financas.autorizacao.apoio.Fakes.LimitesFalso;
import br.com.itau.financas.autorizacao.application.regras.RegraDeConsumoDeLimite;
import br.com.itau.financas.autorizacao.application.regras.RegraDeCredito;
import br.com.itau.financas.autorizacao.application.regras.RegraDeEstorno;
import br.com.itau.financas.autorizacao.application.regras.RegraDeMovimentoDaReserva;
import br.com.itau.financas.autorizacao.domain.EstadoAutorizacao;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException;
import br.com.itau.financas.autorizacao.domain.RequisicaoIdempotenteDivergenteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutorizarTransacaoServiceTest {

    private static final String CONTRATO = "CTR-1";
    private final Clock relogio = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);

    private AutorizacoesEmMemoria autorizacoes;
    private LimitesFalso limites;
    private AutorizarTransacaoService servico;

    @BeforeEach
    void preparar() {
        autorizacoes = new AutorizacoesEmMemoria();
        limites = new LimitesFalso("100.00");
        servico = new AutorizarTransacaoService(autorizacoes, List.of(
                new RegraDeConsumoDeLimite(limites, relogio, Duration.ofDays(7)),
                new RegraDeCredito(limites, relogio),
                new RegraDeEstorno(autorizacoes, limites, relogio),
                new RegraDeMovimentoDaReserva(autorizacoes, limites, relogio)));
    }

    @Test
    void aprovaDebitoComLimiteDisponivel() {
        ResultadoAutorizacao resultado = servico.executar(debito(UUID.randomUUID(), "80.00"));

        assertThat(resultado.criadaAgora()).isTrue();
        assertThat(resultado.autorizacao().estado()).isEqualTo(EstadoAutorizacao.APROVADA);
        assertThat(resultado.autorizacao().posicao().limiteDisponivel().comoTexto()).isEqualTo("20.00");
    }

    @Test
    void recusaQuandoLimiteInsuficienteERegistraARecusa() {
        ResultadoAutorizacao resultado = servico.executar(debito(UUID.randomUUID(), "150.00"));

        assertThat(resultado.autorizacao().foiAprovada()).isFalse();
        assertThat(autorizacoes.quantidade()).isEqualTo(1);
    }

    @Nested
    class Idempotencia {

        @Test
        void repeticaoDevolveADecisaoOriginalSemChamarOLimiteDeNovo() {
            UUID chave = UUID.randomUUID();
            servico.executar(debito(chave, "80.00"));

            ResultadoAutorizacao repeticao = servico.executar(debito(chave, "80.00"));

            assertThat(repeticao.criadaAgora()).isFalse();
            assertThat(limites.chamadas()).isEqualTo(1);
        }

        @Test
        void mesmaChaveComOutroPayloadERejeitada() {
            UUID chave = UUID.randomUUID();
            servico.executar(debito(chave, "80.00"));

            assertThatThrownBy(() -> servico.executar(debito(chave, "10.00")))
                    .isInstanceOf(RequisicaoIdempotenteDivergenteException.class);
        }

        @Test
        void requisicoesSimultaneasComAMesmaChaveGeramUmaUnicaAutorizacao() throws Exception {
            UUID chave = UUID.randomUUID();
            List<ResultadoAutorizacao> resultados = emParalelo(20, () -> servico.executar(debito(chave, "80.00")));

            assertThat(autorizacoes.quantidade()).isEqualTo(1);
            assertThat(resultados).filteredOn(ResultadoAutorizacao::criadaAgora).hasSize(1);
            assertThat(limites.disponivel().comoTexto()).isEqualTo("20.00");
        }
    }

    @Nested
    class Concorrencia {

        @Test
        void autorizacoesParalelasNoMesmoContratoNuncaUltrapassamOLimite() throws Exception {
            List<ResultadoAutorizacao> resultados = emParalelo(50,
                    () -> servico.executar(debito(UUID.randomUUID(), "10.00")));

            assertThat(resultados).filteredOn(r -> r.autorizacao().foiAprovada()).hasSize(10);
            assertThat(limites.disponivel().comoTexto()).isEqualTo("0.00");
        }
    }

    @Nested
    class Estorno {

        @Test
        void estornoParcialDevolveLimiteEAtualizaOOriginal() {
            var original = servico.executar(debito(UUID.randomUUID(), "80.00")).autorizacao();

            var estorno = servico.executar(estorno(UUID.randomUUID(), original.id().toString(), "30.00"));

            assertThat(estorno.autorizacao().estado()).isEqualTo(EstadoAutorizacao.APROVADA);
            assertThat(autorizacoes.buscar(original.id()).orElseThrow().estado())
                    .isEqualTo(EstadoAutorizacao.ESTORNADA_PARCIALMENTE);
            assertThat(limites.disponivel().comoTexto()).isEqualTo("50.00");
        }

        @Test
        void estornoAcimaDoValorAprovadoERejeitadoSemTocarNoLimite() {
            var original = servico.executar(debito(UUID.randomUUID(), "80.00")).autorizacao();
            int chamadasAntes = limites.chamadas();

            assertThatThrownBy(() -> servico.executar(estorno(UUID.randomUUID(), original.id().toString(), "90.00")))
                    .isInstanceOf(RegraDeNegocioVioladaException.class);
            assertThat(limites.chamadas()).isEqualTo(chamadasAntes);
        }
    }

    @Nested
    class Reserva {

        @Test
        void reservaAprovadaFicaBloqueadaComValidade() {
            Autorizacao reserva = servico.executar(solicitacao(UUID.randomUUID(), "40.00", "RESERVA", null))
                    .autorizacao();

            assertThat(reserva.estado()).isEqualTo(EstadoAutorizacao.APROVADA);
            assertThat(reserva.saldoDaReserva().comoTexto()).isEqualTo("40.00");
            assertThat(reserva.reserva().orElseThrow().expiraEm()).isEqualTo(Instant.parse("2026-10-27T12:00:00Z"));
        }

        @Test
        void capturaParcialELiberacaoDoRestoEncerramAReserva() {
            Autorizacao reserva = servico.executar(solicitacao(UUID.randomUUID(), "40.00", "RESERVA", null))
                    .autorizacao();
            String original = reserva.id().toString();

            servico.executar(solicitacao(UUID.randomUUID(), "25.00", "CAPTURA", original));
            Autorizacao aposCaptura = autorizacoes.buscar(reserva.id()).orElseThrow();
            servico.executar(solicitacao(UUID.randomUUID(), "15.00", "LIBERACAO", original));
            Autorizacao aposLiberacao = autorizacoes.buscar(reserva.id()).orElseThrow();

            assertThat(aposCaptura.saldoDaReserva().comoTexto()).isEqualTo("15.00");
            assertThat(aposCaptura.estado()).isEqualTo(EstadoAutorizacao.APROVADA);
            assertThat(aposLiberacao.saldoDaReserva().comoTexto()).isEqualTo("0.00");
            assertThat(aposLiberacao.estado()).isEqualTo(EstadoAutorizacao.ENCERRADA);
            assertThat(limites.disponivel().comoTexto()).as("100 − 40 reservados + 15 liberados").isEqualTo("75.00");
        }

        @Test
        void movimentoAcimaDoSaldoDaReservaERejeitadoSemTocarNoLimite() {
            String original = servico.executar(solicitacao(UUID.randomUUID(), "40.00", "RESERVA", null))
                    .autorizacao().id().toString();
            int chamadasAntes = limites.chamadas();

            assertThatThrownBy(() -> servico.executar(solicitacao(UUID.randomUUID(), "40.01", "CAPTURA", original)))
                    .isInstanceOf(RegraDeNegocioVioladaException.class).hasMessageContaining("saldo da reserva");
            assertThat(limites.chamadas()).isEqualTo(chamadasAntes);
        }

        @Test
        void capturaDepoisDaExpiracaoERejeitadaMasALiberacaoContinuaPossivel() {
            String original = servico.executar(solicitacao(UUID.randomUUID(), "40.00", "RESERVA", null))
                    .autorizacao().id().toString();
            AutorizarTransacaoService oitoDiasDepois = new AutorizarTransacaoService(autorizacoes, List.of(
                    new RegraDeMovimentoDaReserva(autorizacoes, limites,
                            Clock.offset(relogio, Duration.ofDays(8))),
                    new RegraDeConsumoDeLimite(limites, relogio, Duration.ofDays(7)),
                    new RegraDeCredito(limites, relogio), new RegraDeEstorno(autorizacoes, limites, relogio)));

            assertThatThrownBy(() -> oitoDiasDepois.executar(solicitacao(UUID.randomUUID(), "10.00", "CAPTURA", original)))
                    .isInstanceOf(RegraDeNegocioVioladaException.class).hasMessageContaining("expirou");
            assertThat(oitoDiasDepois.executar(solicitacao(UUID.randomUUID(), "40.00", "LIBERACAO", original))
                    .autorizacao().estado()).isEqualTo(EstadoAutorizacao.APROVADA);
        }

        @Test
        void reservaNaoAceitaEstornoMasACapturaAceita() {
            String original = servico.executar(solicitacao(UUID.randomUUID(), "40.00", "RESERVA", null))
                    .autorizacao().id().toString();
            String captura = servico.executar(solicitacao(UUID.randomUUID(), "30.00", "CAPTURA", original))
                    .autorizacao().id().toString();

            assertThatThrownBy(() -> servico.executar(solicitacao(UUID.randomUUID(), "10.00", "ESTORNO", original)))
                    .isInstanceOf(RegraDeNegocioVioladaException.class).hasMessageContaining("não aceita estorno");
            assertThat(servico.executar(solicitacao(UUID.randomUUID(), "10.00", "ESTORNO", captura))
                    .autorizacao().estado()).isEqualTo(EstadoAutorizacao.APROVADA);
        }

        @Test
        void capturaDeUmDebitoERejeitada() {
            String debito = servico.executar(solicitacao(UUID.randomUUID(), "10.00", "DEBITO", null))
                    .autorizacao().id().toString();

            assertThatThrownBy(() -> servico.executar(solicitacao(UUID.randomUUID(), "5.00", "CAPTURA", debito)))
                    .isInstanceOf(RegraDeNegocioVioladaException.class).hasMessageContaining("exige uma RESERVA");
        }
    }

    @Nested
    class ResultadoIncerto {

        @Test
        void timeoutNoLimitePropaga503SemGravarDecisao() {
            UUID chave = UUID.randomUUID();
            limites.falharCom(p -> new LimitesIndisponivelException("timeout", true, null));

            assertThatThrownBy(() -> servico.executar(debito(chave, "10.00")))
                    .isInstanceOf(LimitesIndisponivelException.class)
                    .matches(e -> ((LimitesIndisponivelException) e).resultadoIncerto());
            assertThat(autorizacoes.quantidade()).isZero();
        }

        @Test
        void circuitoAbertoEhResultadoCerto() {
            limites.falharCom(p -> new LimitesIndisponivelException("circuit breaker aberto", false, null));

            assertThatThrownBy(() -> servico.executar(debito(UUID.randomUUID(), "10.00")))
                    .isInstanceOf(LimitesIndisponivelException.class)
                    .matches(e -> !((LimitesIndisponivelException) e).resultadoIncerto());
        }

        @Test
        void falhaAoGravarDepoisDeAplicarOLimiteEhResultadoIncerto() {
            autorizacoes.falharGravacaoCom(new IllegalStateException("DynamoDB indisponível"));

            assertThatThrownBy(() -> servico.executar(debito(UUID.randomUUID(), "10.00")))
                    .isInstanceOf(LimitesIndisponivelException.class)
                    .matches(e -> ((LimitesIndisponivelException) e).resultadoIncerto());
        }

        @Test
        void retentativaComAMesmaChaveConcluiDepoisDeUmResultadoIncerto() {
            UUID chave = UUID.randomUUID();
            autorizacoes.falharGravacaoCom(new IllegalStateException("DynamoDB indisponível"));
            assertThatThrownBy(() -> servico.executar(debito(chave, "10.00")))
                    .isInstanceOf(LimitesIndisponivelException.class);

            autorizacoes.falharGravacaoCom(null);
            var resultado = servico.executar(debito(chave, "10.00"));

            assertThat(resultado.criadaAgora()).isTrue();
            assertThat(limites.disponivel().comoTexto()).as("o Limites aplicou uma única vez (idempotente)")
                    .isEqualTo("90.00");
        }
    }

    private static SolicitacaoDeAutorizacao debito(UUID chave, String valor) {
        return solicitacao(chave, valor, "DEBITO", null);
    }

    private static SolicitacaoDeAutorizacao estorno(UUID chave, String original, String valor) {
        return solicitacao(chave, valor, "ESTORNO", original);
    }

    private static SolicitacaoDeAutorizacao solicitacao(UUID chave, String valor, String tipo, String original) {
        return new SolicitacaoDeAutorizacao("canal-app", chave.toString(), CONTRATO, "0001-123", new BigDecimal(valor),
                "BRL", tipo, null, original, "corr-" + chave, null);
    }

    private static <T> List<T> emParalelo(int quantidade, Callable<T> tarefa) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futuros = new ArrayList<>();
            for (int i = 0; i < quantidade; i++) {
                futuros.add(executor.submit(tarefa));
            }
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get());
            }
            return resultados;
        }
    }
}
