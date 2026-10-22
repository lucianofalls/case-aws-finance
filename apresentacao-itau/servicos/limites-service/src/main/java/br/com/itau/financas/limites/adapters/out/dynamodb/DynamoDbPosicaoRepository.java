package br.com.itau.financas.limites.adapters.out.dynamodb;

import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository;
import br.com.itau.financas.limites.domain.EventoDeLimites;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;
import br.com.itau.financas.limites.domain.Operacao;
import br.com.itau.financas.limites.domain.Posicao;
import br.com.itau.financas.limites.domain.TipoOperacao;
import br.com.itau.financas.limites.domain.RegraDeNegocioVioladaException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Concorrência no DynamoDB, sem lock distribuído:
 * <ul>
 *   <li>a posição só é gravada se {@code versao} ainda for a lida (controle otimista ⇒ sem lost update);
 *       a resposta devolve o saldo exato desta operação</li>
 *   <li>defesa em profundidade: o débito também exige {@code disponivel >= :valor} na própria escrita</li>
 *   <li>a operação só é criada se não existir (idempotência por id_operacao)</li>
 *   <li>{@code TransactionConflict} (transação concorrente no mesmo item) vira retentativa na aplicação</li>
 * </ul>
 * Custo por reserva: 2 itens pequenos em transação = 4 WRU (docs/custos.md).
 */
public final class DynamoDbPosicaoRepository implements PosicaoRepository {

    private static final String CONDICAO_FALHOU = "ConditionalCheckFailed";
    private static final String CONFLITO = "TransactionConflict";

    private final DynamoDbClient dynamo;
    private final String tabela;
    private final Duration retencaoOperacoes;

    public DynamoDbPosicaoRepository(DynamoDbClient dynamo, String tabela, Duration retencaoOperacoes) {
        this.dynamo = dynamo;
        this.tabela = tabela;
        this.retencaoOperacoes = retencaoOperacoes;
    }

    @Override
    public Optional<Posicao> buscarPosicao(ContratoId contratoId) {
        return buscar(ItensDeLimites.chave(contratoId, ItensDeLimites.SK_POSICAO)).map(ItensDeLimites::posicaoDeItem);
    }

    @Override
    public Optional<Operacao> buscarOperacao(ContratoId contratoId, OperacaoId operacaoId) {
        return buscar(ItensDeLimites.chave(contratoId, ItensDeLimites.skOperacao(operacaoId)))
                .map(ItensDeLimites::operacaoDeItem);
    }

    @Override
    public ResultadoDeEscrita registrar(Posicao lida, Posicao nova, Operacao operacao) {
        boolean consome = nova.disponivel().menorQue(lida.disponivel());
        String condicao = consome ? "versao = :versaoLida AND disponivel >= :valor" : "versao = :versaoLida";
        Map<String, AttributeValue> valores = new HashMap<>(Map.of(
                ":versaoLida", ItensDeLimites.n(Long.toString(lida.versao())),
                ":novoDisponivel", ItensDeLimites.n(nova.disponivel().comoTexto()),
                ":novoBloqueado", ItensDeLimites.n(nova.bloqueado().comoTexto()),
                ":novaVersao", ItensDeLimites.n(Long.toString(nova.versao()))));
        if (consome) {
            valores.put(":valor", ItensDeLimites.n(operacao.valor().comoTexto()));
        }
        TransactWriteItem posicao = atualizarPosicao(lida.contratoId(),
                "SET disponivel = :novoDisponivel, bloqueado = :novoBloqueado, versao = :novaVersao",
                condicao, valores);
        TransactWriteItem operacaoNova = criarSeAusente(ItensDeLimites.operacaoParaItem(operacao, retencaoOperacoes));
        // Posição, idempotência e estado POR RESERVA são confirmados na mesma transação DynamoDB.
        // CAPTURA/LIBERACAO nunca poderão consumir o bloqueio de outra reserva do mesmo contrato.
        if (operacao.tipo() == TipoOperacao.RESERVA) {
            return executar(posicao, operacaoNova, criarSeAusente(ItensDeLimites.reservaParaItem(operacao)));
        }
        if (operacao.tipo() == TipoOperacao.CAPTURA || operacao.tipo() == TipoOperacao.LIBERACAO) {
            if (operacao.original().isEmpty()) {
                throw new RegraDeNegocioVioladaException("reserva-original-obrigatoria", "informe id_operacao_original");
            }
            return executar(posicao, operacaoNova, debitarReserva(operacao));
        }
        return executar(posicao, operacaoNova);
    }

    @Override
    public ResultadoDeEscrita criar(Posicao nova, EventoDeLimites evento) {
        return executar(criarSeAusente(ItensDeLimites.posicaoParaItem(nova)), outbox(evento));
    }

    @Override
    public ResultadoDeEscrita atualizarLimite(Posicao lida, Posicao nova, EventoDeLimites evento) {
        return executar(
                atualizarPosicao(lida.contratoId(),
                        "SET limite_concedido = :limite, disponivel = :novoDisponivel, versao = :novaVersao, "
                                + "versao_origem = :versaoOrigem",
                        "versao = :versaoLida",
                        Map.of(":limite", ItensDeLimites.n(nova.limiteConcedido().comoTexto()),
                                ":novoDisponivel", ItensDeLimites.n(nova.disponivel().comoTexto()),
                                ":novaVersao", ItensDeLimites.n(Long.toString(nova.versao())),
                                ":versaoOrigem", ItensDeLimites.n(Long.toString(nova.versaoOrigem())),
                                ":versaoLida", ItensDeLimites.n(Long.toString(lida.versao())))),
                outbox(evento));
    }

    @Override
    public ResultadoDeEscrita reverter(Posicao lida, Posicao nova, Operacao revertida, EventoDeLimites evento) {
        TransactWriteItem marcarRevertida = TransactWriteItem.builder().update(Update.builder()
                .tableName(tabela)
                .key(ItensDeLimites.chave(revertida.contratoId(), ItensDeLimites.skOperacao(revertida.id())))
                .updateExpression("SET estado = :revertida")
                .conditionExpression("estado = :efetivada")
                .expressionAttributeValues(Map.of(":revertida", ItensDeLimites.s(Operacao.Estado.REVERTIDA.name()),
                        ":efetivada", ItensDeLimites.s(Operacao.Estado.EFETIVADA.name())))
                .build()).build();
        TransactWriteItem posicao = atualizarPosicao(lida.contratoId(),
                "SET disponivel = :novoDisponivel, bloqueado = :novoBloqueado, versao = :novaVersao",
                "versao = :versaoLida",
                Map.of(":novoDisponivel", ItensDeLimites.n(nova.disponivel().comoTexto()),
                        ":novoBloqueado", ItensDeLimites.n(nova.bloqueado().comoTexto()),
                        ":novaVersao", ItensDeLimites.n(Long.toString(nova.versao())),
                        ":versaoLida", ItensDeLimites.n(Long.toString(lida.versao()))));
        if (revertida.tipo() == TipoOperacao.RESERVA) {
            return executar(posicao, marcarRevertida, outbox(evento), cancelarReserva(revertida));
        }
        if ((revertida.tipo() == TipoOperacao.CAPTURA || revertida.tipo() == TipoOperacao.LIBERACAO)
                && revertida.original().isPresent()) {
            return executar(posicao, marcarRevertida, outbox(evento), restaurarReserva(revertida));
        }
        return executar(posicao, marcarRevertida, outbox(evento));
    }

    private TransactWriteItem debitarReserva(Operacao operacao) {
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(tabela)
                .key(ItensDeLimites.chave(operacao.contratoId(), ItensDeLimites.skReserva(operacao.original().orElseThrow())))
                .updateExpression("SET restante = restante - :valor")
                .conditionExpression("attribute_exists(sk) AND estado = :ativa AND restante >= :valor AND moeda = :moeda")
                .expressionAttributeValues(Map.of(
                        ":valor", ItensDeLimites.n(operacao.valor().comoTexto()),
                        ":ativa", ItensDeLimites.s("ATIVA"),
                        ":moeda", ItensDeLimites.s(operacao.valor().codigoMoeda())))
                .build()).build();
    }

    private TransactWriteItem cancelarReserva(Operacao operacao) {
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(tabela)
                .key(ItensDeLimites.chave(operacao.contratoId(), ItensDeLimites.skReserva(operacao.id())))
                .updateExpression("SET estado = :cancelada, restante = :zero")
                .conditionExpression("estado = :ativa AND restante = :total")
                .expressionAttributeValues(Map.of(
                        ":cancelada", ItensDeLimites.s("CANCELADA"),
                        ":ativa", ItensDeLimites.s("ATIVA"),
                        ":zero", ItensDeLimites.n("0"),
                        ":total", ItensDeLimites.n(operacao.valor().comoTexto())))
                .build()).build();
    }

    private TransactWriteItem restaurarReserva(Operacao operacao) {
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(tabela)
                .key(ItensDeLimites.chave(operacao.contratoId(), ItensDeLimites.skReserva(operacao.original().orElseThrow())))
                .updateExpression("SET restante = restante + :valor")
                .conditionExpression("attribute_exists(sk) AND estado = :ativa")
                .expressionAttributeValues(Map.of(
                        ":valor", ItensDeLimites.n(operacao.valor().comoTexto()),
                        ":ativa", ItensDeLimites.s("ATIVA")))
                .build()).build();
    }

    private Optional<Map<String, AttributeValue>> buscar(Map<String, AttributeValue> chave) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(tabela).key(chave).consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    private TransactWriteItem atualizarPosicao(ContratoId contrato, String expressao, String condicao,
                                               Map<String, AttributeValue> valores) {
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(tabela)
                .key(ItensDeLimites.chave(contrato, ItensDeLimites.SK_POSICAO))
                .updateExpression(expressao)
                .conditionExpression(condicao)
                .expressionAttributeValues(valores)
                .build()).build();
    }

    private TransactWriteItem criarSeAusente(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tabela)
                .item(item)
                .conditionExpression("attribute_not_exists(" + ItensDeLimites.SK + ")")
                .build()).build();
    }

    private TransactWriteItem outbox(EventoDeLimites evento) {
        CloudEvent cloudEvent = EventosParaCloudEvent.converter(evento);
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(tabela)
                .item(ItensDeLimites.outboxParaItem(evento.contratoId(), cloudEvent.id(),
                        CodecDeEventos.paraJson(cloudEvent), evento.ocorridoEm()))
                .build()).build();
    }

    private ResultadoDeEscrita executar(TransactWriteItem... itens) {
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(itens).build());
            return ResultadoDeEscrita.GRAVADO;
        } catch (TransactionCanceledException e) {
            return interpretar(e.cancellationReasons());
        }
    }

    /** O índice do motivo corresponde à ordem dos itens: [0] posição, [1] operação/outbox. */
    private static ResultadoDeEscrita interpretar(List<CancellationReason> motivos) {
        if (motivos.stream().anyMatch(m -> CONFLITO.equals(m.code()))) {
            return ResultadoDeEscrita.CONFLITO_TRANSACIONAL;
        }
        if (motivos.size() > 1 && CONDICAO_FALHOU.equals(motivos.get(1).code())) {
            return ResultadoDeEscrita.OPERACAO_JA_EXISTE;
        }
        if (motivos.size() > 2 && CONDICAO_FALHOU.equals(motivos.get(2).code())) {
            throw new RegraDeNegocioVioladaException("reserva-insuficiente-ou-inativa",
                    "saldo específico da reserva original insuficiente ou reserva não encontrada/inativa");
        }
        return ResultadoDeEscrita.POSICAO_DESATUALIZADA;
    }
}
