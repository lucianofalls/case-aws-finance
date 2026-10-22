package br.com.itau.financas.autorizacao.adapters.out.dynamodb;

import br.com.itau.financas.autorizacao.application.AcessoNegadoException;
import br.com.itau.financas.autorizacao.application.port.out.AcessoClienteContratoPort;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.Map;
import java.time.Clock;

/** Projeção de entitlements provisionada por fonte confiável; consulta STRONGLY CONSISTENT.
 * Falhas da AWS NÃO são convertidas em autorização (fail-closed).
 */
public final class DynamoDbAcessoClienteContratoAdapter implements AcessoClienteContratoPort {
    private final DynamoDbClient dynamo;
    private final String tabela;
    private final Clock relogio;

    public DynamoDbAcessoClienteContratoAdapter(DynamoDbClient dynamo, String tabela) {
        this(dynamo, tabela, Clock.systemUTC());
    }

    public DynamoDbAcessoClienteContratoAdapter(DynamoDbClient dynamo, String tabela, Clock relogio) {
        this.relogio = relogio;
        this.dynamo = dynamo;
        this.tabela = tabela;
    }

    @Override
    public void exigirAcesso(String clienteId, String contratoId, String contaId) {
        if (vazio(clienteId) || vazio(contratoId) || vazio(contaId)) {
            throw new AcessoNegadoException();
        }
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(tabela)
                .consistentRead(true)
                .key(Map.of("id_cliente", AttributeValue.fromS(clienteId),
                        "id_contrato", AttributeValue.fromS(contratoId)))
                .build()).item();
        if (item == null || item.isEmpty()
                || !Boolean.TRUE.equals(item.getOrDefault("ativo", AttributeValue.fromBool(false)).bool())
                || !contaId.equals(item.getOrDefault("id_conta", AttributeValue.fromS("")).s())
                || !vigente(item)) {
            throw new AcessoNegadoException();
        }
    }

    private boolean vigente(Map<String, AttributeValue> item) {
        try {
            var validade = item.get("valido_ate");
            return validade != null && Long.parseLong(validade.n()) > relogio.instant().getEpochSecond();
        } catch (RuntimeException invalido) {
            return false;
        }
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
