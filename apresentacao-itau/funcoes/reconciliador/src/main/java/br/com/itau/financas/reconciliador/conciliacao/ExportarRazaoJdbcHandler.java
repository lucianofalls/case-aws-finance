package br.com.itau.financas.reconciliador.conciliacao;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/** Substitui RDS Data API (Aurora-only) por JDBC privado contra RDS PostgreSQL Multi-AZ.
 * O SQL gerado pela Lambda preparar-janela chama a extensão RDS aws_s3.query_export_to_s3.
 * O segredo é obtido do Secrets Manager; nunca aparece na resposta/log.
 */
public final class ExportarRazaoJdbcHandler implements RequestHandler<Map<String, String>, Map<String, Object>> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecretsManagerClient SECRETS = SecretsManagerClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build();

    @Override
    public Map<String, Object> handleRequest(Map<String, String> entrada, Context contexto) {
        String sql = entrada.get("sql");
        if (sql == null || !sql.stripLeading().startsWith("SELECT * FROM aws_s3.query_export_to_s3(")) {
            throw new IllegalArgumentException("somente SQL de exportação gerado pelo reconciliador é permitido");
        }
        try {
            String segredo = SECRETS.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(System.getenv("RDS_SECRET_ARN")).build()).secretString();
            JsonNode credenciais = JSON.readTree(segredo);
            String endpoint = System.getenv("RDS_ENDPOINT");
            String url = "jdbc:postgresql://" + endpoint + ":5432/contabilizacao"
                    + "?sslmode=require&connectTimeout=5&socketTimeout=180";
            try (Connection conexao = DriverManager.getConnection(url, credenciais.get("username").asText(),
                    credenciais.get("password").asText());
                 Statement statement = conexao.createStatement()) {
                statement.setQueryTimeout(180);
                try (ResultSet linhas = statement.executeQuery(sql)) {
                    int registros = 0;
                    while (linhas.next()) registros++;
                    return Map.of("exportacao_executada", true, "resultado_linhas", registros);
                }
            }
        } catch (Exception e) {
            // Nunca registrar segredo, SQL bruto ou dados financeiros no log.
            throw new IllegalStateException("exportação RDS PostgreSQL→S3 falhou: " + e.getClass().getSimpleName(), e);
        }
    }
}
