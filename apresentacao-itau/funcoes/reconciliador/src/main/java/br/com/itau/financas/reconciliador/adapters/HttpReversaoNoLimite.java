package br.com.itau.financas.reconciliador.adapters;

import br.com.itau.financas.reconciliador.VerificarAutorizacaoIncerta.ReversaoNoLimite;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** API interna do Limites (NLB interno). Falha técnica propaga: a mensagem volta para a fila e é retentada. */
public final class HttpReversaoNoLimite implements ReversaoNoLimite {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI base;

    public HttpReversaoNoLimite(HttpClient http, URI base) {
        this.http = http;
        this.base = base;
    }

    @Override
    public String reverter(String idContrato, String idOperacao) {
        URI uri = base.resolve("/v1/contratos/" + URLEncoder.encode(idContrato, StandardCharsets.UTF_8)
                + "/operacoes/" + idOperacao + "/reversao");
        HttpRequest requisicao = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() != 200) {
                throw new IllegalStateException("Limites respondeu " + resposta.statusCode() + ": " + resposta.body());
            }
            return JSON.readTree(resposta.body()).path("desfecho").asText();
        } catch (IOException e) {
            throw new IllegalStateException("falha ao chamar o Limites", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido ao chamar o Limites", e);
        }
    }
}
