package br.com.itau.financas.relay.autorizacoes;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import br.com.itau.financas.relay.EventoParaPublicar;
import br.com.itau.financas.relay.ExtratorDeEventos;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Outbox embutido da tabela {@code autorizacoes}: cada escrita que gera um fato novo troca {@code evento_id}.
 * Atualizações sem fato novo (ex.: o original recebendo um estorno) mantêm o mesmo {@code evento_id} e são
 * ignoradas. Este mapeador é a fronteira entre o item (detalhe interno) e o contrato público (CloudEvents v1).
 */
public final class ExtratorDeAutorizacoes implements ExtratorDeEventos {

    @Override
    public Optional<EventoParaPublicar> extrair(DynamodbStreamRecord registro) {
        String operacao = registro.getEventName();
        if (!"INSERT".equals(operacao) && !"MODIFY".equals(operacao)) {
            return Optional.empty();
        }
        Map<String, AttributeValue> nova = registro.getDynamodb().getNewImage();
        Map<String, AttributeValue> antiga = registro.getDynamodb().getOldImage();
        String eventoId = texto(nova, "evento_id");
        if (eventoId == null || (antiga != null && Objects.equals(eventoId, texto(antiga, "evento_id")))) {
            return Optional.empty();
        }
        CloudEvent evento = paraCloudEvent(nova, eventoId);
        return Optional.of(new EventoParaPublicar(registro.getDynamodb().getSequenceNumber(), evento,
                CodecDeEventos.paraJson(evento)));
    }

    static CloudEvent paraCloudEvent(Map<String, AttributeValue> item, String eventoId) {
        String contrato = texto(item, "id_contrato");
        String tipoDoFato = texto(item, "evento_tipo");
        Object payload;
        String tipo;
        switch (tipoDoFato) {
            case "TRANSACAO_AUTORIZADA" -> {
                tipo = TiposDeEvento.TRANSACAO_AUTORIZADA;
                payload = new PayloadsV1.TransacaoAutorizada(texto(item, "id_autorizacao"), contrato,
                        texto(item, "id_conta"), texto(item, "tipo_operacao"), numero(item, "valor"),
                        texto(item, "moeda"), numero(item, "saldo_reservado"), numero(item, "limite_disponivel"),
                        texto(item, "id_estabelecimento"), texto(item, "id_autorizacao_original"),
                        texto(item, "reserva_expira_em"));
            }
            case "TRANSACAO_RECUSADA" -> {
                tipo = TiposDeEvento.TRANSACAO_RECUSADA;
                payload = new PayloadsV1.TransacaoRecusada(texto(item, "id_autorizacao"), contrato,
                        texto(item, "id_conta"), texto(item, "tipo_operacao"), numero(item, "valor"),
                        texto(item, "moeda"), texto(item, "motivo_recusa"), numero(item, "limite_disponivel"));
            }
            case "ESTORNO_REALIZADO" -> {
                tipo = TiposDeEvento.ESTORNO_REALIZADO;
                payload = new PayloadsV1.EstornoRealizado(texto(item, "id_autorizacao"),
                        texto(item, "id_autorizacao_original"), contrato, texto(item, "id_conta"),
                        numero(item, "valor"), texto(item, "moeda"), numero(item, "limite_disponivel"));
            }
            default -> throw new IllegalStateException("tipo de fato desconhecido: " + tipoDoFato);
        }
        return CodecDeEventos.novo(eventoId, TiposDeEvento.FONTE_AUTORIZACAO, tipo,
                Instant.parse(texto(item, "evento_ocorrido_em")), "contrato/" + contrato, contrato,
                texto(item, "correlation_id"), texto(item, "traceparent"), payload);
    }

    private static String texto(Map<String, AttributeValue> item, String nome) {
        AttributeValue valor = item == null ? null : item.get(nome);
        return valor == null ? null : valor.getS();
    }

    private static String numero(Map<String, AttributeValue> item, String nome) {
        AttributeValue valor = item.get(nome);
        return valor == null ? null : valor.getN();
    }
}
