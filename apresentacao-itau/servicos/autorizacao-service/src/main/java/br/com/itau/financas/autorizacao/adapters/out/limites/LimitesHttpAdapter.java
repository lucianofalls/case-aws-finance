package br.com.itau.financas.autorizacao.adapters.out.limites;

import br.com.itau.financas.autorizacao.application.ConflitoDeConcorrenciaException;
import br.com.itau.financas.autorizacao.application.LimitesIndisponivelException;
import br.com.itau.financas.autorizacao.application.OperacaoEncerradaException;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort;
import br.com.itau.financas.autorizacao.domain.PosicaoDeLimite;
import br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException;
import br.com.itau.financas.kernel.Dinheiro;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Adapter do contexto Limites (Customer/Supplier síncrono via ECS Service Connect).
 * <ul>
 *   <li>Retry: só em falha técnica, com backoff + jitter — seguro porque a operação é idempotente pelo id</li>
 *   <li>Circuit breaker: abre com falhas técnicas e devolve 503 fail-fast sem chamar o Limites</li>
 *   <li>402/409/422 são respostas de negócio: não contam como falha do circuito</li>
 * </ul>
 */
public final class LimitesHttpAdapter implements OperacaoDeLimitePort {

    private static final TypeReference<Map<String, Object>> CORPO = new TypeReference<>() {
    };
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    private static final String MDC_CORRELATION_ID = "correlation_id";

    private final RestClient http;
    private final ObjectMapper json;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public LimitesHttpAdapter(RestClient http, ObjectMapper json, CircuitBreaker circuitBreaker, Retry retry) {
        this.http = http;
        this.json = json;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public ResultadoNoLimite aplicar(PedidoAoLimite pedido) {
        Supplier<ResultadoNoLimite> chamada = Retry.decorateSupplier(retry, () -> enviar(pedido));
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, chamada).get();
        } catch (CallNotPermittedException e) {
            throw new LimitesIndisponivelException("circuit breaker do Limites aberto", false, e);
        }
    }

    private ResultadoNoLimite enviar(PedidoAoLimite pedido) {
        OperacaoRequest corpo = new OperacaoRequest(pedido.idOperacao().toString(), pedido.tipo().name(),
                pedido.valor().comoTexto(), pedido.valor().codigoMoeda(), pedido.impressao().hash(),
                pedido.idOperacaoOriginal() == null ? null : pedido.idOperacaoOriginal().toString());
        try {
            return http.post()
                    .uri("/v1/contratos/{idContrato}/operacoes", pedido.contratoId().valor())
                    .headers(cabecalhos -> propagarCorrelacao(cabecalhos))
                    .body(corpo)
                    .exchange((requisicao, resposta) -> interpretar(resposta, pedido.valor()));
        } catch (ResourceAccessException e) {
            throw new LimitesIndisponivelException("falha de comunicação com o Limites", !naoSaiu(e.getCause()), e);
        }
    }

    /** Rastreio ponta a ponta: o Limites repassa a correlação aos eventos que publicar (PDF §2.4). */
    private static void propagarCorrelacao(HttpHeaders cabecalhos) {
        String correlacao = MDC.get(MDC_CORRELATION_ID);
        if (correlacao != null) {
            cabecalhos.set(HEADER_CORRELATION_ID, correlacao);
        }
    }

    /** Falhas antes de a requisição sair: não há como o Limites ter aplicado a operação. */
    private static boolean naoSaiu(Throwable causa) {
        return causa instanceof ConnectException || causa instanceof UnknownHostException
                || causa instanceof HttpConnectTimeoutException;
    }

    private ResultadoNoLimite interpretar(ClientHttpResponse resposta, Dinheiro referencia) throws IOException {
        HttpStatusCode status = resposta.getStatusCode();
        Map<String, Object> corpo = lerCorpo(resposta);
        return switch (status.value()) {
            case 200, 201 -> new Efetivada(posicao(corpo, referencia));
            case 402 -> new Insuficiente(posicao(corpo, referencia));
            case 404 -> throw new RegraDeNegocioVioladaException("contrato-sem-limite",
                    "contrato sem limite cadastrado");
            case 409 -> throw conflito(corpo);
            case 422 -> throw new RegraDeNegocioVioladaException(texto(corpo, "codigo", "regra-limite"),
                    texto(corpo, "detail", "operação rejeitada pelo Limites"));
            default -> throw new LimitesIndisponivelException("Limites respondeu " + status.value(), true, null);
        };
    }

    private static RuntimeException conflito(Map<String, Object> corpo) {
        String codigo = texto(corpo, "codigo", "");
        if ("operacao-encerrada".equals(codigo)) {
            return new OperacaoEncerradaException("a operação desta Idempotency-Key foi encerrada (revertida ou fora da janela); use uma nova chave");
        }
        return new ConflitoDeConcorrenciaException("concorrência na reserva de limite");
    }

    private Map<String, Object> lerCorpo(ClientHttpResponse resposta) throws IOException {
        byte[] bytes = resposta.getBody().readAllBytes();
        return bytes.length == 0 ? Map.of() : json.readValue(bytes, CORPO);
    }

    private static PosicaoDeLimite posicao(Map<String, Object> corpo, Dinheiro referencia) {
        String moeda = referencia.codigoMoeda();
        return new PosicaoDeLimite(Dinheiro.de(texto(corpo, "saldo_reservado", "0"), moeda),
                Dinheiro.de(texto(corpo, "limite_disponivel", "0"), moeda));
    }

    private static String texto(Map<String, Object> corpo, String chave, String padrao) {
        Object valor = corpo.get(chave);
        return valor == null ? padrao : valor.toString();
    }

    record OperacaoRequest(String idOperacao, String tipoOperacao, String valor, String moeda,
                           String impressaoDigital, String idOperacaoOriginal) {
    }
}
