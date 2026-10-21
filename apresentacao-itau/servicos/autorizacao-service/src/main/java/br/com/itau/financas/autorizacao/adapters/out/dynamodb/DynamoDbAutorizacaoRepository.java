package br.com.itau.financas.autorizacao.adapters.out.dynamodb;

import br.com.itau.financas.autorizacao.application.ConflitoDeConcorrenciaException;
import br.com.itau.financas.autorizacao.application.port.out.AutorizacaoRepository;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Concorrência (docs/concorrencia.md):
 * <ul>
 *   <li>C4 — mesma Idempotency-Key em paralelo: {@code attribute_not_exists} na PK; só uma escrita vence</li>
 *   <li>C6 — estornos simultâneos do mesmo original: controle otimista de {@code versao} no original</li>
 * </ul>
 * Leitura fortemente consistente: a idempotência não pode ler uma réplica atrasada.
 */
public final class DynamoDbAutorizacaoRepository implements AutorizacaoRepository {

    private static final String NAO_EXISTE = "attribute_not_exists(" + ItemDeAutorizacao.PK + ")";
    private static final String CONDICAO_FALHOU = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final String tabela;
    private final Duration retencao;

    public DynamoDbAutorizacaoRepository(DynamoDbClient dynamo, String tabela, Duration retencao) {
        this.dynamo = dynamo;
        this.tabela = tabela;
        this.retencao = retencao;
    }

    @Override
    public Optional<Autorizacao> buscar(AutorizacaoId id) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(tabela)
                .key(Map.of(ItemDeAutorizacao.PK, ItemDeAutorizacao.s(id.toString())))
                .consistentRead(true)
                .build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(ItemDeAutorizacao.deItem(item));
    }

    @Override
    public boolean inserirSeAusente(Autorizacao nova) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tabela)
                    .item(ItemDeAutorizacao.paraItem(nova, retencao))
                    .conditionExpression(NAO_EXISTE)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public boolean inserirComOriginalAtualizado(Autorizacao estorno, Autorizacao originalAtualizado) {
        TransactWriteItem gravarEstorno = TransactWriteItem.builder().put(Put.builder()
                .tableName(tabela)
                .item(ItemDeAutorizacao.paraItem(estorno, retencao))
                .conditionExpression(NAO_EXISTE)
                .build()).build();
        TransactWriteItem atualizarOriginal = TransactWriteItem.builder().put(Put.builder()
                .tableName(tabela)
                .item(ItemDeAutorizacao.paraItem(originalAtualizado, retencao))
                .conditionExpression("#versao = :versaoLida")
                .expressionAttributeNames(Map.of("#versao", ItemDeAutorizacao.VERSAO))
                .expressionAttributeValues(Map.of(":versaoLida",
                        ItemDeAutorizacao.n(Long.toString(originalAtualizado.versao() - 1))))
                .build()).build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(gravarEstorno, atualizarOriginal)
                    .build());
            return true;
        } catch (TransactionCanceledException e) {
            List<CancellationReason> motivos = e.cancellationReasons();
            if (CONDICAO_FALHOU.equals(motivos.get(0).code())) {
                return false;
            }
            throw new ConflitoDeConcorrenciaException("autorização original " + originalAtualizado.id()
                    + " alterada concorrentemente (" + motivos.get(1).code() + ")");
        }
    }
}
