package br.com.itau.financas.reconciliador.conciliacao;

import br.com.itau.financas.reconciliador.AgendarVerificacoes;
import br.com.itau.financas.reconciliador.AgendarVerificacoes.OperacaoGravada;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Conciliação diária (BIAN Account Reconciliation; auditoria C01/C03/C06). */
class ConciliacaoTest {

    private static final JanelaDeConciliacao JANELA = new JanelaDeConciliacao(
            new JanelaDeConciliacao.Configuracao("financas_conciliacao", "bucket-conciliacao", "sa-east-1"));
    private static final Instant AGORA = Instant.parse("2026-10-21T02:00:00Z");

    @Test
    void agendamentoDiarioConciliaODiaUtcAnterior() {
        Map<String, Object> janela = new PrepararJanelaHandler(JANELA, Clock.fixed(AGORA, ZoneOffset.UTC))
                .handleRequest(Map.of(), null);

        assertThat(janela).containsEntry("desde", "2026-10-20T00:00:00Z").containsEntry("ate", "2026-10-21T00:00:00Z")
                .containsEntry("dt", "2026-10-20")
                .containsEntry("prefixo_autorizacoes", "exports/autorizacoes/dt=2026-10-20/");
        assertThat((List<?>) janela.get("exportacoes_razao")).as("26 blocos de 1 h: de 20/10 00h até 21/10 02h")
                .hasSize(26);
    }

    @Test
    void consultaDeRepublicacaoLeATrilhaDoDiaEDoDiaSeguinte() {
        String sql = JANELA.diaAnterior(AGORA).get("consulta_republicar");

        assertThat(sql).contains("\"financas_conciliacao\".\"autorizacoes_exportadas\"")
                .contains("(('2026', '10', '20'), ('2026', '10', '21'))")
                .contains("'NAO_PUBLICADO'").contains("'NAO_CONTABILIZADO'")
                .contains("('TRANSACAO_AUTORIZADA', 'ESTORNO_REALIZADO')");
    }

    @Test
    void consultaDeOrfasIgnoraOperacoesRevertidasEExtraiOIdDaChave() {
        String sql = JANELA.diaAnterior(AGORA).get("consulta_orfas");

        assertThat(sql).contains("LIKE 'OPERACAO#%'")
                .contains("substr(l.chaves.sk.s, 10)")
                .contains("<> 'REVERTIDA'")
                .contains("NOT EXISTS")
                .doesNotContain("NOT IN");
    }

    @Test
    void exportacaoDoRazaoEmBlocosDeUmaHoraEscapaAsAspasDoSqlInterno() {
        List<String> sqls = JANELA.exportacoesDoRazao(Instant.parse("2026-10-20T00:00:00Z"), AGORA);

        assertThat(sqls.getFirst())
                .contains("processado_em >= ''2026-10-20T00:00:00Z'' AND processado_em < ''2026-10-20T01:00:00Z''")
                .contains("'bucket-conciliacao', 'razao/dt=2026-10-20/20261020T000000Z.csv', 'sa-east-1'");
    }

    @Test
    void janelaManualDeIncidenteAceitaAte24Horas() {
        Map<String, String> janela = JANELA.montar(Instant.parse("2026-10-19T08:00:00Z"),
                Instant.parse("2026-10-20T08:00:00Z"), AGORA);

        assertThat(janela.get("dt")).isEqualTo("2026-10-19");
        assertThatThrownBy(() -> JANELA.montar(Instant.parse("2026-10-18T00:00:00Z"),
                Instant.parse("2026-10-20T00:00:00Z"), AGORA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("24 h");
    }

    @Test
    void resultadoDoAthenaViraLinhasPorColuna() {
        List<Map<String, String>> linhas = ResultadoAthena.linhas("""
                "id_contrato","id_operacao"
                "CTR-1","aut-1"
                "CTR-2","aut-2"
                """);

        assertThat(linhas).containsExactly(Map.of("id_contrato", "CTR-1", "id_operacao", "aut-1"),
                Map.of("id_contrato", "CTR-2", "id_operacao", "aut-2"));
        assertThat(ResultadoAthena.localizacao("s3://bucket/athena/x/y.csv")).containsExactly("bucket", "athena/x/y.csv");
    }

    @Test
    void operacoesSemDecisaoVaoParaAFilaDeVerificacao() {
        List<OperacaoGravada> enviadas = new ArrayList<>();
        var handler = new AgendarOrfasHandler(s3 -> "\"id_contrato\",\"id_operacao\"\n\"CTR-9\",\"aut-9\"\n",
                new AgendarVerificacoes(operacoes -> {
                    enviadas.addAll(operacoes);
                    return Set.of();
                }));

        Map<String, Object> resposta = handler.handleRequest(Map.of("resultado", "s3://b/r.csv"), null);

        assertThat(resposta).containsEntry("candidatas", 1);
        assertThat(enviadas).extracting(OperacaoGravada::idOperacao).containsExactly("aut-9");
    }

    @Test
    void resultadoVazioNaoAgendaNada() {
        var handler = new AgendarOrfasHandler(s3 -> "\"id_contrato\",\"id_operacao\"\n",
                new AgendarVerificacoes(operacoes -> {
                    throw new AssertionError("não deveria chamar a fila");
                }));

        assertThat(handler.handleRequest(Map.of("resultado", "s3://b/r.csv"), null)).containsEntry("candidatas", 0);
    }
}
