package br.com.itau.financas.autorizacao.adapters.out.dynamodb;

import br.com.itau.financas.autorizacao.application.AcessoNegadoException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AcessoClienteContratoTest {
    @Test void revogacaoEhObservadaSemCacheEAcessoCruzadoEhNegado() {
        var dynamo = mock(DynamoDbClient.class);
        var dados = new HashMap<String, AttributeValue>();
        dados.put("ativo", AttributeValue.fromBool(true));
        dados.put("id_conta", AttributeValue.fromS("conta-a"));
        dados.put("valido_ate", AttributeValue.fromN("2000"));
        when(dynamo.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            GetItemRequest req = inv.getArgument(0);
            assertThat(req.consistentRead()).isTrue();
            boolean permitido = "cliente-a".equals(req.key().get("id_cliente").s())
                    && "contrato-a".equals(req.key().get("id_contrato").s());
            return GetItemResponse.builder().item(permitido ? dados : Map.of()).build();
        });
        var acesso = new DynamoDbAcessoClienteContratoAdapter(dynamo, "permissoes", Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
        acesso.exigirAcesso("cliente-a", "contrato-a", "conta-a");
        assertThatThrownBy(() -> acesso.exigirAcesso("cliente-b", "contrato-a", "conta-a")).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> acesso.exigirAcesso("cliente-a", "contrato-b", "conta-a")).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> acesso.exigirAcesso("cliente-a", "contrato-a", "conta-b")).isInstanceOf(AcessoNegadoException.class);
        dados.put("ativo", AttributeValue.fromBool(false));
        assertThatThrownBy(() -> acesso.exigirAcesso("cliente-a", "contrato-a", "conta-a")).isInstanceOf(AcessoNegadoException.class);
    }
    @Test void validadeAusenteOuExpiradaNegaMesmoSeDynamoAindaNaoRemoveuItem() {
        var dynamo = mock(DynamoDbClient.class);
        var dados = new HashMap<>(Map.of("ativo", AttributeValue.fromBool(true), "id_conta", AttributeValue.fromS("conta")));
        when(dynamo.getItem(any(GetItemRequest.class))).thenAnswer(i -> GetItemResponse.builder().item(dados).build());
        var acesso = new DynamoDbAcessoClienteContratoAdapter(dynamo, "permissoes", Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
        assertThatThrownBy(() -> acesso.exigirAcesso("cliente", "contrato", "conta")).isInstanceOf(AcessoNegadoException.class);
        dados.put("valido_ate", AttributeValue.fromN("1000"));
        assertThatThrownBy(() -> acesso.exigirAcesso("cliente", "contrato", "conta")).isInstanceOf(AcessoNegadoException.class);
    }
}
