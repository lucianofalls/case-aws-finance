package br.com.itau.financas.limites.application;

import br.com.itau.financas.limites.apoio.PosicoesEmMemoria;
import br.com.itau.financas.limites.application.AplicarLimiteDoLegadoService.Desfecho;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AplicarLimiteDoLegadoServiceTest {

    private final PosicoesEmMemoria repositorio = new PosicoesEmMemoria();
    private final PoliticaDeRetentativa retentativa = new PoliticaDeRetentativa(3, t -> { });
    private final AplicarLimiteDoLegadoService servico =
            new AplicarLimiteDoLegadoService(repositorio, retentativa, Clock.systemUTC());

    @Test
    void criaPosicaoDoContratoNovoEPublicaLimiteAlterado() {
        assertThat(servico.aplicar(comando("1000.00", 1))).isEqualTo(Desfecho.CRIADO);

        assertThat(repositorio.outbox()).hasSize(1);
    }

    @Test
    void reducaoDeLimitePreservaOQueJaFoiConsumido() {
        servico.aplicar(comando("1000.00", 1));
        new AplicarOperacaoService(repositorio, retentativa, Clock.systemUTC(), Duration.ofMinutes(10)).aplicar(
                new AplicarOperacaoService.Comando("CTR-1", UUID.randomUUID().toString(), "DEBITO", "700.00", "BRL",
                        "h", null, "0001-1"));

        servico.aplicar(comando("500.00", 2));

        var posicao = repositorio.buscarPosicao(new ContratoId("CTR-1")).orElseThrow();
        assertThat(posicao.disponivel().comoTexto()).isEqualTo("-200.00");
        assertThat(posicao.utilizado().comoTexto()).isEqualTo("700.00");
    }

    @Test
    void eventoForaDeOrdemComVersaoAntigaEIgnorado() {
        servico.aplicar(comando("1000.00", 5));

        assertThat(servico.aplicar(comando("10.00", 4))).isEqualTo(Desfecho.IGNORADO_VERSAO_OBSOLETA);
        assertThat(repositorio.buscarPosicao(new ContratoId("CTR-1")).orElseThrow().limiteConcedido().comoTexto())
                .isEqualTo("1000.00");
    }

    private static AplicarLimiteDoLegadoService.Comando comando(String limite, long versao) {
        return new AplicarLimiteDoLegadoService.Comando("CTR-1", "0001-1", "CREDITO_PESSOAL", limite, "BRL", versao);
    }
}
