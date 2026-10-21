package br.com.itau.financas.reconciliador.conciliacao;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê o CSV de resultado do Athena (cabeçalho + valores entre aspas). Os valores desta conciliação são ids e códigos,
 * sem vírgulas nem aspas internas.
 */
public final class ResultadoAthena {

    private ResultadoAthena() {
    }

    public static List<Map<String, String>> linhas(String csv) {
        List<Map<String, String>> linhas = new ArrayList<>();
        String[] registros = csv.strip().split("\\R");
        if (registros.length == 0 || registros[0].isBlank()) {
            return linhas;
        }
        String[] cabecalho = valores(registros[0]);
        for (String registro : Arrays.copyOfRange(registros, 1, registros.length)) {
            if (registro.isBlank()) {
                continue;
            }
            String[] valores = valores(registro);
            Map<String, String> linha = new LinkedHashMap<>();
            for (int i = 0; i < cabecalho.length; i++) {
                linha.put(cabecalho[i], i < valores.length ? valores[i] : "");
            }
            linhas.add(linha);
        }
        return linhas;
    }

    /** {@code s3://bucket/chave} → [bucket, chave]. */
    public static String[] localizacao(String s3) {
        URI uri = URI.create(s3);
        return new String[]{uri.getHost(), uri.getPath().substring(1)};
    }

    private static String[] valores(String linha) {
        return Arrays.stream(linha.split(",", -1)).map(v -> v.strip().replaceAll("^\"|\"$", "")).toArray(String[]::new);
    }
}
