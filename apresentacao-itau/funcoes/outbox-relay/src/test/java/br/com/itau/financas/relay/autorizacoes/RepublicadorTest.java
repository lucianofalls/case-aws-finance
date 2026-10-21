package br.com.itau.financas.relay.autorizacoes;

import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.relay.EventoParaPublicar;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Auditoria C03: o que a conciliação encontrou sem publicação é republicado com o mesmo id do evento original. */
class RepublicadorTest {

    private final List<EventoParaPublicar> publicados = new ArrayList<>();

    @Test
    void republicaComOMesmoIdEOMesmoTipoDoFatoOriginal() {
        var republicador = new Republicador(ids -> Map.of("aut-1", item("aut-1", "evt-1")), destinoQueAceita());

        Republicador.Resultado resultado = republicador.republicar(List.of("aut-1"));

        assertThat(resultado.republicados()).isEqualTo(1);
        assertThat(publicados).singleElement().satisfies(evento -> {
            assertThat(evento.evento().id()).isEqualTo("evt-1");
            assertThat(evento.evento().type()).isEqualTo(TiposDeEvento.TRANSACAO_AUTORIZADA);
            assertThat(evento.chaveDeParticao()).isEqualTo("CTR-1");
        });
    }

    @Test
    void itemQueNaoExisteMaisEhContadoESemFalha() {
        var republicador = new Republicador(ids -> Map.of(), destinoQueAceita());

        assertThat(republicador.republicar(List.of("aut-x")).naoEncontrados()).isEqualTo(1);
        assertThat(publicados).isEmpty();
    }

    @Test
    void falhaNaPublicacaoInterrompeParaAMaquinaDeEstadosRetentar() {
        var republicador = new Republicador(ids -> Map.of("aut-1", item("aut-1", "evt-1")),
                eventos -> Set.of(eventos.getFirst().sequencia()));

        assertThatThrownBy(() -> republicador.republicar(List.of("aut-1"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void csvDoAthenaViraListaDeIdsSemRepeticao() {
        String csv = "\"id_autorizacao\",\"evento_id\",\"motivo\"\n\"aut-1\",\"e1\",\"NAO_PUBLICADO\"\n"
                + "\"aut-1\",\"e1\",\"NAO_CONTABILIZADO\"\n\"aut-2\",\"e2\",\"NAO_PUBLICADO\"\n";

        assertThat(RepublicadorHandler.idsDoCsv(csv)).containsExactly("aut-1", "aut-2");
    }

    @Test
    void itemDoSdkV2EhConvertidoParaOFormatoDoStream() {
        var convertido = Republicador.doSdk(Map.of(
                "valor", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromN("10.00"),
                "moeda", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("BRL")));

        assertThat(convertido.get("valor").getN()).isEqualTo("10.00");
        assertThat(convertido.get("moeda").getS()).isEqualTo("BRL");
    }

    private br.com.itau.financas.relay.Destino destinoQueAceita() {
        return eventos -> {
            publicados.addAll(eventos);
            return Set.of();
        };
    }

    private static Map<String, AttributeValue> item(String idAutorizacao, String eventoId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id_autorizacao", s(idAutorizacao));
        item.put("id_contrato", s("CTR-1"));
        item.put("id_conta", s("0001"));
        item.put("evento_id", s(eventoId));
        item.put("evento_tipo", s("TRANSACAO_AUTORIZADA"));
        item.put("evento_ocorrido_em", s("2026-10-20T12:00:00Z"));
        item.put("tipo_operacao", s("DEBITO"));
        item.put("valor", n("10.00"));
        item.put("moeda", s("BRL"));
        item.put("saldo_reservado", n("10.00"));
        item.put("limite_disponivel", n("990.00"));
        item.put("correlation_id", s("corr-1"));
        return item;
    }

    private static AttributeValue s(String valor) {
        return new AttributeValue().withS(valor);
    }

    private static AttributeValue n(String valor) {
        return new AttributeValue().withN(valor);
    }
}
