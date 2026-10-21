package br.com.itau.financas.reconciliador.adapters;

import br.com.itau.financas.reconciliador.VerificarAutorizacaoIncerta.ConsultaDeAutorizacoes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.Map;

/** Mesmo contexto (Autorização) e mesmo banco: leitura consistente só da chave. */
public final class DynamoDbConsultaDeAutorizacoes implements ConsultaDeAutorizacoes {

    private final DynamoDbClient dynamo;
    private final String tabela;

    public DynamoDbConsultaDeAutorizacoes(DynamoDbClient dynamo, String tabela) {
        this.dynamo = dynamo;
        this.tabela = tabela;
    }

    @Override
    public boolean existe(String idAutorizacao) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(tabela)
                .key(Map.of("id_autorizacao", AttributeValue.fromS(idAutorizacao)))
                .projectionExpression("id_autorizacao")
                .consistentRead(true)
                .build()).item();
        return item != null && !item.isEmpty();
    }
}
