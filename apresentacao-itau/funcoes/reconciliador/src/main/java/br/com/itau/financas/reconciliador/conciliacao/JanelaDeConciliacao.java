package br.com.itau.financas.reconciliador.conciliacao;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conciliação diária (BIAN Account Reconciliation; auditoria C01, C03 e C06; ADR 034). Compara três fontes que não
 * dependem do transporte de eventos:
 * <ol>
 *   <li>exportação incremental da tabela {@code autorizacoes} (os fatos decididos)</li>
 *   <li>trilha S3 do stream {@code transacoes} (o que foi publicado) e o razão (o que foi contabilizado)</li>
 *   <li>exportação incremental da tabela {@code limites} (as operações aplicadas)</li>
 * </ol>
 * Esta classe monta a janela, os prefixos de exportação e as consultas Athena; a máquina de estados executa.
 * A janela padrão é o dia UTC anterior; uma janela manual (incidente) aceita no máximo 24 h, que é o limite da
 * exportação incremental do DynamoDB.
 */
public final class JanelaDeConciliacao {

    public record Configuracao(String bancoGlue, String bucket, String regiao) {
    }

    private static final DateTimeFormatter COMPACTO = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Duration MAXIMO = Duration.ofHours(24);
    private static final Duration MINIMO = Duration.ofMinutes(15);

    private final Configuracao configuracao;

    public JanelaDeConciliacao(Configuracao configuracao) {
        this.configuracao = configuracao;
    }

    /**
     * Exportações do razão em blocos de 1 hora, de {@code desde} até {@code agora}: os fatos da janela podem ter sido
     * contabilizados depois do fim dela, e cada bloco cabe no limite de tempo da Data API do Aurora.
     */
    public List<String> exportacoesDoRazao(Instant desde, Instant agora) {
        String dt = LocalDate.ofInstant(desde, ZoneOffset.UTC).toString();
        List<String> sqls = new ArrayList<>();
        for (Instant inicio = desde; inicio.isBefore(agora); inicio = inicio.plus(Duration.ofHours(1))) {
            Instant fim = inicio.plus(Duration.ofHours(1));
            String chave = "razao/dt=%s/%s.csv".formatted(dt, COMPACTO.format(inicio));
            sqls.add("""
                    SELECT * FROM aws_s3.query_export_to_s3(
                      'SELECT evento_id FROM evento_processado WHERE processado_em >= ''%s'' AND processado_em < ''%s''',
                      aws_commons.create_s3_uri('%s', '%s', '%s'),
                      options := 'format csv')""".formatted(inicio, fim, configuracao.bucket(), chave,
                    configuracao.regiao()));
        }
        return sqls;
    }

    /** Janela padrão: o dia UTC anterior a {@code agora}. */
    public Map<String, String> diaAnterior(Instant agora) {
        Instant ate = agora.truncatedTo(ChronoUnit.DAYS);
        return montar(ate.minus(Duration.ofDays(1)), ate, agora);
    }

    public Map<String, String> montar(Instant desde, Instant ate, Instant agora) {
        Duration tamanho = Duration.between(desde, ate);
        if (tamanho.compareTo(MINIMO) < 0 || tamanho.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("a janela deve ter entre 15 min e 24 h (exportação incremental): "
                    + desde + " → " + ate);
        }
        LocalDate dia = LocalDate.ofInstant(desde, ZoneOffset.UTC);
        LocalDate diaSeguinte = dia.plusDays(1);
        String dt = dia.toString();

        Map<String, String> janela = new LinkedHashMap<>();
        janela.put("desde", desde.toString());
        janela.put("ate", ate.toString());
        janela.put("dt", dt);
        janela.put("execucao", COMPACTO.format(agora));
        janela.put("prefixo_autorizacoes", "exports/autorizacoes/dt=" + dt + "/");
        janela.put("prefixo_limites", "exports/limites/dt=" + dt + "/");
        janela.put("saida_athena", "s3://" + configuracao.bucket() + "/athena/" + COMPACTO.format(agora) + "/");
        janela.put("consulta_republicar", consultaRepublicar(dt, diaSeguinte));
        janela.put("consulta_orfas", consultaOrfas(dt));
        return janela;
    }

    /**
     * Fatos decididos na janela que não aparecem na trilha (não publicados) ou, para os que geram lançamento,
     * não aparecem no razão (não contabilizados). A trilha e o razão são lidos também no dia seguinte, porque a
     * publicação e o consumo de um fato das 23h59 acontecem depois da meia-noite.
     */
    private String consultaRepublicar(String dt, LocalDate diaSeguinte) {
        String banco = configuracao.bancoGlue();
        return """
                WITH fatos AS (
                  SELECT DISTINCT nova_imagem.evento_id.s AS evento_id, nova_imagem.id_autorizacao.s AS id_autorizacao,
                         nova_imagem.evento_tipo.s AS evento_tipo
                  FROM "%1$s"."autorizacoes_exportadas"
                  WHERE dt = '%2$s' AND nova_imagem.evento_id.s IS NOT NULL
                ),
                publicados AS (
                  SELECT DISTINCT id FROM "%1$s"."trilha_transacoes"
                  WHERE (ano, mes, dia) IN (('%3$s', '%4$s', '%5$s'), ('%6$s', '%7$s', '%8$s'))
                ),
                contabilizados AS (
                  SELECT DISTINCT evento_id FROM "%1$s"."razao_processado" WHERE dt = '%2$s'
                )
                SELECT f.id_autorizacao, f.evento_id,
                       CASE WHEN p.id IS NULL THEN 'NAO_PUBLICADO' ELSE 'NAO_CONTABILIZADO' END AS motivo
                FROM fatos f
                LEFT JOIN publicados p ON p.id = f.evento_id
                LEFT JOIN contabilizados c ON c.evento_id = f.evento_id
                WHERE p.id IS NULL
                   OR (c.evento_id IS NULL AND f.evento_tipo IN ('TRANSACAO_AUTORIZADA', 'ESTORNO_REALIZADO'))"""
                .formatted(banco, dt, dt.substring(0, 4), dt.substring(5, 7), dt.substring(8, 10),
                        String.format("%04d", diaSeguinte.getYear()), String.format("%02d", diaSeguinte.getMonthValue()),
                        String.format("%02d", diaSeguinte.getDayOfMonth()));
    }

    /**
     * Operações efetivadas no Limites sem decisão gravada na Autorização. São candidatas: vão para a fila de
     * verificação, e o reconciliador confere na tabela viva antes de reverter (falso positivo não tem efeito).
     */
    private String consultaOrfas(String dt) {
        return """
                SELECT DISTINCT l.chaves.id_contrato.s AS id_contrato,
                       substr(l.chaves.sk.s, %3$d) AS id_operacao
                FROM "%1$s"."limites_exportados" l
                WHERE l.dt = '%2$s'
                  AND l.chaves.sk.s LIKE 'OPERACAO#%%'
                  AND coalesce(l.nova_imagem.estado.s, '') <> 'REVERTIDA'
                  AND NOT EXISTS (
                    SELECT 1 FROM "%1$s"."autorizacoes_exportadas" a
                    WHERE a.dt = '%2$s'
                      AND a.chaves.id_autorizacao.s = substr(l.chaves.sk.s, %3$d))"""
                .formatted(configuracao.bancoGlue(), dt, "OPERACAO#".length() + 1);
    }
}
