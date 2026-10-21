package br.com.itau.financas.autorizacao.domain;

import br.com.itau.financas.autorizacao.domain.Identificadores.ContaId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.kernel.Dinheiro;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutorizacaoTest {

    private static final ContratoId CONTRATO = new ContratoId("CTR-1");
    private static final Instant AGORA = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void aprovacaoGeraFatoTransacaoAutorizada() {
        Autorizacao aprovada = Autorizacao.aprovada(pedido(TipoOperacao.DEBITO, "100.00"), posicao(), AGORA);

        assertThat(aprovada.ultimoFato().tipo()).isEqualTo(FatoDeAutorizacao.Tipo.TRANSACAO_AUTORIZADA);
        assertThat(aprovada.versao()).isEqualTo(1);
    }

    @Test
    void estornoTotalMudaEstadoParaEstornadaENaoGeraNovoFatoNoOriginal() {
        Autorizacao aprovada = Autorizacao.aprovada(pedido(TipoOperacao.DEBITO, "100.00"), posicao(), AGORA);

        Autorizacao estornada = aprovada.comEstorno(CONTRATO, Dinheiro.de("100.00", "BRL"));

        assertThat(estornada.estado()).isEqualTo(EstadoAutorizacao.ESTORNADA);
        assertThat(estornada.versao()).isEqualTo(2);
        assertThat(estornada.ultimoFato()).isEqualTo(aprovada.ultimoFato());
    }

    @Test
    void somaDosEstornosNaoPodeUltrapassarOValorAprovado() {
        Autorizacao parcial = Autorizacao.aprovada(pedido(TipoOperacao.DEBITO, "100.00"), posicao(), AGORA)
                .comEstorno(CONTRATO, Dinheiro.de("60.00", "BRL"));

        assertThatThrownBy(() -> parcial.comEstorno(CONTRATO, Dinheiro.de("50.00", "BRL")))
                .isInstanceOf(RegraDeNegocioVioladaException.class)
                .hasMessageContaining("excede");
    }

    @Test
    void recusadaNaoAceitaEstorno() {
        Autorizacao recusada = Autorizacao.recusada(pedido(TipoOperacao.DEBITO, "100.00"),
                Autorizacao.MotivoRecusa.LIMITE_INSUFICIENTE, posicao(), AGORA);

        assertThatThrownBy(() -> recusada.validarEstorno(CONTRATO, Dinheiro.de("1.00", "BRL")))
                .isInstanceOf(RegraDeNegocioVioladaException.class);
    }

    @Test
    void creditoNaoAceitaEstorno() {
        Autorizacao credito = Autorizacao.aprovada(pedido(TipoOperacao.CREDITO, "100.00"), posicao(), AGORA);

        assertThatThrownBy(() -> credito.validarEstorno(CONTRATO, Dinheiro.de("1.00", "BRL")))
                .isInstanceOf(RegraDeNegocioVioladaException.class);
    }

    @Test
    void valorDeveSerPositivo() {
        assertThatThrownBy(() -> pedido(TipoOperacao.DEBITO, "0.00"))
                .isInstanceOf(RegraDeNegocioVioladaException.class);
    }

    private static NovaAutorizacao pedido(TipoOperacao tipo, String valor) {
        return new NovaAutorizacao(new AutorizacaoId(UUID.randomUUID()), CONTRATO, new ContaId("0001-1"), tipo,
                Dinheiro.de(valor, "BRL"), ImpressaoDigital.de(valor), null, null, new Rastreio("corr", null));
    }

    private static PosicaoDeLimite posicao() {
        return new PosicaoDeLimite(Dinheiro.de("100.00", "BRL"), Dinheiro.de("900.00", "BRL"));
    }
}
