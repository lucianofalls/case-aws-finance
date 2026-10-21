package br.com.itau.financas.limites.application;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.apoio.PosicoesEmMemoria;
import br.com.itau.financas.limites.domain.Excecoes.ContratoSemLimiteException;
import br.com.itau.financas.limites.domain.Excecoes.LimiteInsuficienteException;
import br.com.itau.financas.limites.domain.Excecoes.OperacaoEncerradaException;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Posicao;
import br.com.itau.financas.limites.domain.TipoOperacao;
import br.com.itau.financas.limites.domain.RegraDeNegocioVioladaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AplicarOperacaoServiceTest {

    private static final ContratoId CONTRATO = new ContratoId("CTR-1");

    private PosicoesEmMemoria repositorio;
    private AplicarOperacaoService servico;

    @BeforeEach
    void preparar() {
        repositorio = new PosicoesEmMemoria();
        repositorio.com(Posicao.nova(CONTRATO, "0001-1", "CREDITO_PESSOAL", Dinheiro.de("100.00", "BRL"), 1));
        servico = new AplicarOperacaoService(repositorio, new PoliticaDeRetentativa(200, t -> Thread.yield()),
                Clock.systemUTC(), Duration.ofMinutes(10));
    }

    @Test
    void debitoDevolveAFotografiaExataDaPosicao() {
        var resultado = servico.aplicar(comando(UUID.randomUUID(), "DEBITO", "30.00"));

        assertThat(resultado.criadaAgora()).isTrue();
        assertThat(resultado.operacao().saldoReservadoApos().comoTexto()).isEqualTo("30.00");
        assertThat(resultado.operacao().disponivelApos().comoTexto()).isEqualTo("70.00");
    }

    @Test
    void debitoAcimaDoDisponivelERecusado() {
        assertThatThrownBy(() -> servico.aplicar(comando(UUID.randomUUID(), "DEBITO", "100.01")))
                .isInstanceOf(LimiteInsuficienteException.class);
    }

    @Test
    void creditoNaoPodeDevolverMaisQueOUtilizado() {
        servico.aplicar(comando(UUID.randomUUID(), "DEBITO", "30.00"));

        assertThatThrownBy(() -> servico.aplicar(comando(UUID.randomUUID(), "CREDITO", "40.00")))
                .isInstanceOf(RegraDeNegocioVioladaException.class);
    }

    @Test
    void repeticaoComOMesmoIdNaoAplicaDuasVezes() {
        UUID id = UUID.randomUUID();
        servico.aplicar(comando(id, "DEBITO", "30.00"));

        var repeticao = servico.aplicar(comando(id, "DEBITO", "30.00"));

        assertThat(repeticao.criadaAgora()).isFalse();
        assertThat(repositorio.buscarPosicao(CONTRATO).orElseThrow().disponivel().comoTexto()).isEqualTo("70.00");
    }

    @Test
    void contratoSemPosicaoEh404() {
        assertThatThrownBy(() -> servico.aplicar(new AplicarOperacaoService.Comando("CTR-X",
                UUID.randomUUID().toString(), "DEBITO", "1.00", "BRL", "h", null)))
                .isInstanceOf(ContratoSemLimiteException.class);
    }

    @Test
    void operacaoRevertidaNaoEhRessuscitadaPelaRepeticao() {
        UUID id = UUID.randomUUID();
        servico.aplicar(comando(id, "DEBITO", "30.00"));
        new ReverterOperacaoOrfaService(repositorio, new PoliticaDeRetentativa(3, t -> { }), Clock.systemUTC())
                .reverter(CONTRATO.valor(), id.toString());

        assertThatThrownBy(() -> servico.aplicar(comando(id, "DEBITO", "30.00")))
                .isInstanceOf(OperacaoEncerradaException.class);
        assertThat(repositorio.buscarPosicao(CONTRATO).orElseThrow().disponivel().comoTexto()).isEqualTo("100.00");
    }

    @Test
    void repeticaoForaDaJanelaEhRecusadaParaNaoDisputarComOReconciliador() {
        UUID id = UUID.randomUUID();
        servico.aplicar(comando(id, "DEBITO", "30.00"));
        AplicarOperacaoService onzeMinutosDepois = new AplicarOperacaoService(repositorio,
                new PoliticaDeRetentativa(3, t -> { }),
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(11)), Duration.ofMinutes(10));

        assertThatThrownBy(() -> onzeMinutosDepois.aplicar(comando(id, "DEBITO", "30.00")))
                .isInstanceOf(OperacaoEncerradaException.class)
                .hasMessageContaining("fora da janela");
    }

    @Test
    void cinquentaDebitosSimultaneosNuncaUltrapassamOLimite() throws Exception {
        AtomicInteger aprovados = new AtomicInteger();
        AtomicInteger recusados = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futuros.add(executor.submit(() -> {
                    try {
                        servico.aplicar(comando(UUID.randomUUID(), "DEBITO", "3.00"));
                        aprovados.incrementAndGet();
                    } catch (LimiteInsuficienteException e) {
                        recusados.incrementAndGet();
                    }
                }));
            }
            for (Future<?> futuro : futuros) {
                futuro.get();
            }
        }

        assertThat(aprovados.get()).isEqualTo(33);
        assertThat(recusados.get()).isEqualTo(17);
        assertThat(repositorio.buscarPosicao(CONTRATO).orElseThrow().disponivel().comoTexto()).isEqualTo("1.00");
    }

    @Test
    void reservaBloqueiaOValorSemUtilizar() {
        var resultado = servico.aplicar(comando(UUID.randomUUID(), "RESERVA", "40.00"));

        Posicao posicao = repositorio.buscarPosicao(CONTRATO).orElseThrow();
        assertThat(posicao.disponivel().comoTexto()).isEqualTo("60.00");
        assertThat(posicao.bloqueado().comoTexto()).isEqualTo("40.00");
        assertThat(posicao.utilizado().comoTexto()).isEqualTo("0.00");
        assertThat(resultado.operacao().saldoReservadoApos().comoTexto()).as("saldo_reservado = comprometido")
                .isEqualTo("40.00");
    }

    @Test
    void capturaParcialMoveDoBloqueadoParaOUtilizadoELiberacaoDevolveOResto() {
        servico.aplicar(comando(UUID.randomUUID(), "RESERVA", "40.00"));

        servico.aplicar(comando(UUID.randomUUID(), "CAPTURA", "25.00"));
        Posicao aposCaptura = repositorio.buscarPosicao(CONTRATO).orElseThrow();
        servico.aplicar(comando(UUID.randomUUID(), "LIBERACAO", "15.00"));
        Posicao aposLiberacao = repositorio.buscarPosicao(CONTRATO).orElseThrow();

        assertThat(aposCaptura.disponivel().comoTexto()).isEqualTo("60.00");
        assertThat(aposCaptura.bloqueado().comoTexto()).isEqualTo("15.00");
        assertThat(aposCaptura.utilizado().comoTexto()).isEqualTo("25.00");
        assertThat(aposLiberacao.disponivel().comoTexto()).isEqualTo("75.00");
        assertThat(aposLiberacao.bloqueado().comoTexto()).isEqualTo("0.00");
        assertThat(aposLiberacao.utilizado().comoTexto()).isEqualTo("25.00");
    }

    @Test
    void capturaOuLiberacaoAcimaDoBloqueadoSaoRecusadas() {
        servico.aplicar(comando(UUID.randomUUID(), "RESERVA", "10.00"));

        assertThatThrownBy(() -> servico.aplicar(comando(UUID.randomUUID(), "CAPTURA", "10.01")))
                .isInstanceOf(RegraDeNegocioVioladaException.class).hasMessageContaining("bloqueado");
        assertThatThrownBy(() -> servico.aplicar(comando(UUID.randomUUID(), "LIBERACAO", "10.01")))
                .isInstanceOf(RegraDeNegocioVioladaException.class).hasMessageContaining("bloqueado");
    }

    @Test
    void reservaNaoPodeUltrapassarODisponivel() {
        assertThatThrownBy(() -> servico.aplicar(comando(UUID.randomUUID(), "RESERVA", "100.01")))
                .isInstanceOf(LimiteInsuficienteException.class);
    }

    @Test
    void reversaoDeCadaEfeitoEhOInversoExato() {
        Posicao inicial = Posicao.nova(CONTRATO, "0001-1", "CREDITO_PESSOAL", Dinheiro.de("100.00", "BRL"), 1)
                .aplicar(TipoOperacao.RESERVA, Dinheiro.de("40.00", "BRL"))
                .aplicar(TipoOperacao.DEBITO, Dinheiro.de("10.00", "BRL"));
        for (TipoOperacao tipo : TipoOperacao.values()) {
            Dinheiro valor = Dinheiro.de("5.00", "BRL");
            Posicao revertida = inicial.aplicar(tipo, valor).reverter(tipo, valor);

            assertThat(revertida.disponivel()).as(tipo.name()).isEqualTo(inicial.disponivel());
            assertThat(revertida.bloqueado()).as(tipo.name()).isEqualTo(inicial.bloqueado());
        }
    }

    private static AplicarOperacaoService.Comando comando(UUID id, String tipo, String valor) {
        return new AplicarOperacaoService.Comando(CONTRATO.valor(), id.toString(), tipo, valor, "BRL",
                "impressao-" + tipo + valor, null);
    }
}
