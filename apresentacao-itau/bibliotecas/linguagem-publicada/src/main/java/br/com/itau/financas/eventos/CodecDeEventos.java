package br.com.itau.financas.eventos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.UUID;

/**
 * Serialização única dos eventos da linguagem publicada.
 * Payloads usam snake_case (contrato do PDF); o envelope usa os nomes da especificação CloudEvents.
 * Campos desconhecidos são ignorados: um consumidor antigo tolera evolução retrocompatível.
 */
public final class CodecDeEventos {

    private static final ObjectMapper ENVELOPE = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Campos opcionais ausentes são omitidos (nunca {@code null}): é o que os JSON Schemas descrevem. */
    private static final ObjectMapper PAYLOAD = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CodecDeEventos() {
    }

    /**
     * {@code correlationid} nunca é nulo (PDF §2.4): sem correlação recebida, o evento abre uma cadeia nova e
     * usa o próprio {@code id} como correlação.
     */
    public static CloudEvent novo(String id, String source, String tipo, Instant ocorridoEm, String subject,
                                  String partitionKey, String correlationId, String traceparent, Object payload) {
        String correlacao = correlationId == null || correlationId.isBlank() ? id : correlationId;
        return new CloudEvent(CloudEvent.SPEC_VERSION, id, source, tipo, ocorridoEm.toString(), subject,
                CloudEvent.JSON, TiposDeEvento.schemaDe(tipo), traceparent, correlacao, partitionKey,
                PAYLOAD.valueToTree(payload));
    }

    public static CloudEvent novo(String source, String tipo, Instant ocorridoEm, String subject,
                                  String partitionKey, String correlationId, String traceparent, Object payload) {
        return novo(UUID.randomUUID().toString(), source, tipo, ocorridoEm, subject, partitionKey,
                correlationId, traceparent, payload);
    }

    public static String paraJson(CloudEvent evento) {
        try {
            return ENVELOPE.writeValueAsString(evento);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("falha ao serializar CloudEvent " + evento.id(), e);
        }
    }

    public static CloudEvent deJson(String json) {
        try {
            return ENVELOPE.readValue(json, CloudEvent.class);
        } catch (JsonProcessingException e) {
            throw new EventoInvalidoException("CloudEvent malformado", e);
        }
    }

    public static <T> T payloadDe(CloudEvent evento, Class<T> tipoDoPayload) {
        try {
            return PAYLOAD.treeToValue(evento.data(), tipoDoPayload);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new EventoInvalidoException("payload incompatível com " + tipoDoPayload.getSimpleName(), e);
        }
    }

    public static final class EventoInvalidoException extends RuntimeException {
        public EventoInvalidoException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
