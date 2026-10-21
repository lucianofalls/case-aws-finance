package br.com.itau.financas.limites.adapters.out.dynamodb;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;
import br.com.itau.financas.limites.domain.Operacao;
import br.com.itau.financas.limites.domain.Posicao;
import br.com.itau.financas.limites.domain.TipoOperacao;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Modelo da tabela {@code limites} (PK {@code id_contrato}, SK {@code sk}) — uma partição por contrato:
 * <pre>
 *   POSICAO              limite_concedido, disponivel, versao, versao_origem   (~250 B → 1 WRU)
 *   OPERACAO#&lt;uuid&gt;      tipo, valor, impressao, estado, fotografia da posição (~350 B → 1 WRU, TTL)
 *   OUT#&lt;uuid&gt;           cloudevent (JSON)                                    (outbox explícito, TTL 7 d)
 * </pre>
 */
final class ItensDeLimites {

    static final String PK = "id_contrato";
    static final String SK = "sk";
    static final String SK_POSICAO = "POSICAO";
    static final String PREFIXO_OPERACAO = "OPERACAO#";
    static final String PREFIXO_OUTBOX = "OUT#";

    private ItensDeLimites() {
    }

    static Map<String, AttributeValue> chave(ContratoId contrato, String sk) {
        return Map.of(PK, s(contrato.valor()), SK, s(sk));
    }

    static String skOperacao(OperacaoId id) {
        return PREFIXO_OPERACAO + id;
    }

    static Map<String, AttributeValue> posicaoParaItem(Posicao p) {
        Map<String, AttributeValue> item = new HashMap<>(chave(p.contratoId(), SK_POSICAO));
        item.put("id_conta", s(p.contaId()));
        item.put("produto", s(p.produto()));
        item.put("moeda", s(p.limiteConcedido().codigoMoeda()));
        item.put("limite_concedido", n(p.limiteConcedido().comoTexto()));
        item.put("disponivel", n(p.disponivel().comoTexto()));
        item.put("bloqueado", n(p.bloqueado().comoTexto())); // BIAN AmountBlock (ADR 035)
        item.put("versao", n(Long.toString(p.versao())));
        item.put("versao_origem", n(Long.toString(p.versaoOrigem())));
        return item;
    }

    static Posicao posicaoDeItem(Map<String, AttributeValue> item) {
        String moeda = item.get("moeda").s();
        return new Posicao(new ContratoId(item.get(PK).s()), item.get("id_conta").s(), item.get("produto").s(),
                Dinheiro.de(item.get("limite_concedido").n(), moeda), Dinheiro.de(item.get("disponivel").n(), moeda),
                item.containsKey("bloqueado") ? Dinheiro.de(item.get("bloqueado").n(), moeda) : Dinheiro.de("0.00", moeda),
                Long.parseLong(item.get("versao").n()), Long.parseLong(item.get("versao_origem").n()));
    }

    static Map<String, AttributeValue> operacaoParaItem(Operacao o, Duration retencao) {
        Map<String, AttributeValue> item = new HashMap<>(chave(o.contratoId(), skOperacao(o.id())));
        item.put("tipo_operacao", s(o.tipo().name()));
        item.put("valor", n(o.valor().comoTexto()));
        item.put("moeda", s(o.valor().codigoMoeda()));
        item.put("impressao", s(o.impressao()));
        o.original().ifPresent(original -> item.put("id_operacao_original", s(original.toString())));
        item.put("estado", s(o.estado().name()));
        item.put("registrada_em", s(o.registradaEm().toString()));
        item.put("saldo_reservado_apos", n(o.saldoReservadoApos().comoTexto()));
        item.put("disponivel_apos", n(o.disponivelApos().comoTexto()));
        item.put("expira_em", n(Long.toString(o.registradaEm().plus(retencao).getEpochSecond())));
        return item;
    }

    static Operacao operacaoDeItem(Map<String, AttributeValue> item) {
        String moeda = item.get("moeda").s();
        String sk = item.get(SK).s();
        AttributeValue original = item.get("id_operacao_original");
        return new Operacao(OperacaoId.de(sk.substring(PREFIXO_OPERACAO.length())), new ContratoId(item.get(PK).s()),
                TipoOperacao.valueOf(item.get("tipo_operacao").s()), Dinheiro.de(item.get("valor").n(), moeda),
                item.get("impressao").s(), original == null ? null : OperacaoId.de(original.s()),
                Operacao.Estado.valueOf(item.get("estado").s()), Instant.parse(item.get("registrada_em").s()),
                Dinheiro.de(item.get("saldo_reservado_apos").n(), moeda),
                Dinheiro.de(item.get("disponivel_apos").n(), moeda));
    }

    static Map<String, AttributeValue> outboxParaItem(ContratoId contrato, String idEvento, String cloudEventJson,
                                                      Instant agora) {
        Map<String, AttributeValue> item = new HashMap<>(chave(contrato, PREFIXO_OUTBOX + idEvento));
        item.put("cloudevent", s(cloudEventJson));
        item.put("expira_em", n(Long.toString(agora.plus(Duration.ofDays(7)).getEpochSecond())));
        return item;
    }

    static AttributeValue s(String valor) {
        return AttributeValue.fromS(valor);
    }

    static AttributeValue n(String valor) {
        return AttributeValue.fromN(valor);
    }
}
