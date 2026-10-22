package br.com.itau.financas.relay.autorizacoes;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.relay.Destino;
import br.com.itau.financas.relay.EventoParaPublicar;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Republica fatos que a conciliação encontrou sem publicação ou sem contabilização (auditoria C03, ADR 034). Lê o
 * item atual da tabela {@code autorizacoes} e gera o CloudEvent pelo <b>mesmo mapeamento do relay</b>, com o
 * {@code evento_id} gravado: o evento republicado é idêntico ao original, e os consumidores deduplicam pelo id.
 * Cobre também o outbox parado por mais de 24 h (retenção do DynamoDB Streams), via janela manual da conciliação.
 */
public final class Republicador {

    /** Lê itens da tabela {@code autorizacoes} pela chave; ausentes (TTL expirado) não voltam. */
    public interface LeitorDeAutorizacoes {
        Map<String, Map<String, AttributeValue>> buscar(List<String> idsAutorizacao);
    }

    public record Resultado(int republicados, int naoEncontrados) {
    }

    private final LeitorDeAutorizacoes leitor;
    private final Destino destino;

    public Republicador(LeitorDeAutorizacoes leitor, Destino destino) {
        this.leitor = leitor;
        this.destino = destino;
    }

    public Resultado republicar(List<String> idsAutorizacao) {
        Map<String, Map<String, AttributeValue>> itens = leitor.buscar(idsAutorizacao);
        List<EventoParaPublicar> eventos = new ArrayList<>();
        for (String id : idsAutorizacao) {
            Optional.ofNullable(itens.get(id)).map(Republicador::evento).ifPresent(eventos::add);
        }
        int ausentes = idsAutorizacao.size() - eventos.size();
        // Publica todos os itens ainda presentes, mas não encerra a janela silenciosamente se algum
        // id já saiu pelo TTL: será preciso reidratar o CloudEvent original pela trilha S3 WORM.
        Set<String> falhas = destino.publicar(eventos);
        if (!falhas.isEmpty()) {
            throw new IllegalStateException(falhas.size() + " evento(s) não republicado(s); a máquina de estados retenta");
        }
        if (ausentes > 0) {
            throw new IllegalStateException(ausentes + " autorização(ões) ausentes no DynamoDB: "
                    + "não é possível reconstruir o evento após o TTL; replay deve usar a trilha S3 "
                    + "com o event_id original. A conciliação NÃO será marcada como concluída.");
        }
        return new Resultado(eventos.size(), 0);
    }

    private static EventoParaPublicar evento(Map<String, AttributeValue> item) {
        AttributeValue eventoId = item.get("evento_id");
        if (eventoId == null) {
            return null;
        }
        CloudEvent evento = ExtratorDeAutorizacoes.paraCloudEvent(item, eventoId.getS());
        return new EventoParaPublicar(evento.id(), evento, CodecDeEventos.paraJson(evento));
    }

    /** Converte o item do SDK v2 (BatchGetItem) para o formato do evento de Streams usado pelo mapeamento. */
    public static Map<String, AttributeValue> doSdk(
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item) {
        Function<software.amazon.awssdk.services.dynamodb.model.AttributeValue, AttributeValue> converter = valor -> {
            AttributeValue convertido = new AttributeValue();
            if (valor.s() != null) {
                convertido.setS(valor.s());
            } else if (valor.n() != null) {
                convertido.setN(valor.n());
            } else if (valor.bool() != null) {
                convertido.setBOOL(valor.bool());
            }
            return convertido;
        };
        Map<String, AttributeValue> convertido = new java.util.HashMap<>();
        item.forEach((nome, valor) -> convertido.put(nome, converter.apply(valor)));
        return convertido;
    }
}
