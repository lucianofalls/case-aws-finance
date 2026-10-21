package br.com.itau.financas.eventos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Envelope CloudEvents 1.0 (CNCF), modo estruturado JSON.
 * Nomes de atributos seguem a especificação: minúsculas, sem underscore.
 * {@code data} carrega o payload versionado do evento (ver pacote {@code v1}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudEvent(
        String specversion,
        String id,
        String source,
        String type,
        String time,
        String subject,
        String datacontenttype,
        String dataschema,
        String traceparent,
        String correlationid,
        String partitionkey,
        JsonNode data) {

    public static final String SPEC_VERSION = "1.0";
    public static final String JSON = "application/json";

    public CloudEvent {
        Objects.requireNonNull(id, "id é obrigatório (deduplicação at-least-once)");
        Objects.requireNonNull(source, "source é obrigatório");
        Objects.requireNonNull(type, "type é obrigatório");
        Objects.requireNonNull(time, "time é obrigatório");
        Objects.requireNonNull(data, "data é obrigatório");
        specversion = specversion == null ? SPEC_VERSION : specversion;
        datacontenttype = datacontenttype == null ? JSON : datacontenttype;
    }
}
