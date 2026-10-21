package br.com.itau.financas.acl;

import br.com.itau.financas.acl.RegistroDeContratoLegado.RegistroLegadoInvalidoException;
import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradutorDeContratoLegadoTest {

    //                        NUM-CONTRATO(20)    NUM-CONTA(12)PR VLR-LIMITE(15)  MOE NUM-SEQ(10)DAT-HOR-ATU(14)
    private static final String REGISTRO = "CTR-000123          0001-1234567CS000000001500000BRL000000004220261020103000";

    private final TradutorDeContratoLegado tradutor = new TradutorDeContratoLegado();

    @Test
    void traduzLayoutPosicionalParaLimiteConcedido() {
        CloudEvent evento = tradutor.traduzir(RegistroDeContratoLegado.ler(REGISTRO));

        PayloadsV1.LimiteConcedido payload = CodecDeEventos.payloadDe(evento, PayloadsV1.LimiteConcedido.class);
        assertThat(evento.type()).isEqualTo(TiposDeEvento.LIMITE_CONCEDIDO);
        assertThat(payload.idContrato()).isEqualTo("CTR-000123");
        assertThat(payload.produto()).isEqualTo("CONSORCIO");
        assertThat(payload.limiteConcedido()).isEqualTo("15000.00");
        assertThat(payload.versaoOrigem()).isEqualTo(42);
        assertThat(evento.time()).isEqualTo("2026-10-20T13:30:00Z");
    }

    @Test
    void reenvioDoMesmoRegistroGeraOMesmoIdDeEvento() {
        assertThat(tradutor.traduzir(RegistroDeContratoLegado.ler(REGISTRO)).id())
                .isEqualTo(tradutor.traduzir(RegistroDeContratoLegado.ler(REGISTRO)).id());
    }

    @Test
    void produtoDesconhecidoEhRejeitadoNaFronteira() {
        String invalido = REGISTRO.substring(0, 32) + "XX" + REGISTRO.substring(34);

        assertThatThrownBy(() -> tradutor.traduzir(RegistroDeContratoLegado.ler(invalido)))
                .isInstanceOf(RegistroLegadoInvalidoException.class);
    }

    @Test
    void registroComTamanhoErradoEhRejeitado() {
        assertThatThrownBy(() -> RegistroDeContratoLegado.ler("curto"))
                .isInstanceOf(RegistroLegadoInvalidoException.class);
    }
}
