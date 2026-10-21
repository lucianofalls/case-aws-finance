package br.com.itau.financas.reconciliador.conciliacao;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1º passo da máquina de estados {@code conciliacao}. Entrada vazia (agendamento diário) → dia UTC anterior.
 * Entrada {@code {"desde": "...", "ate": "..."}} → janela manual, usada no runbook de outbox parado (> 24 h).
 */
public final class PrepararJanelaHandler implements RequestHandler<Map<String, String>, Map<String, Object>> {

    private final JanelaDeConciliacao janela;
    private final Clock relogio;

    public PrepararJanelaHandler() {
        this(new JanelaDeConciliacao(new JanelaDeConciliacao.Configuracao(System.getenv("BANCO_GLUE"),
                System.getenv("BUCKET_CONCILIACAO"), System.getenv("AWS_REGION"))), Clock.systemUTC());
    }

    PrepararJanelaHandler(JanelaDeConciliacao janela, Clock relogio) {
        this.janela = janela;
        this.relogio = relogio;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, String> entrada, Context contexto) {
        Instant agora = relogio.instant();
        Map<String, String> valores = entrada != null && entrada.containsKey("desde") && entrada.containsKey("ate")
                ? janela.montar(Instant.parse(entrada.get("desde")), Instant.parse(entrada.get("ate")), agora)
                : janela.diaAnterior(agora);
        Map<String, Object> saida = new LinkedHashMap<>(valores);
        saida.put("exportacoes_razao", janela.exportacoesDoRazao(Instant.parse(valores.get("desde")), agora));
        return saida;
    }
}
