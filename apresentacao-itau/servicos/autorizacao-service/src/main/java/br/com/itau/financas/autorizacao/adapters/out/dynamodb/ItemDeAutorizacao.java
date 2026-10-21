package br.com.itau.financas.autorizacao.adapters.out.dynamodb;

import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import br.com.itau.financas.autorizacao.domain.EstadoAutorizacao;
import br.com.itau.financas.autorizacao.domain.FatoDeAutorizacao;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContaId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.autorizacao.domain.ImpressaoDigital;
import br.com.itau.financas.autorizacao.domain.MovimentosDaReserva;
import br.com.itau.financas.autorizacao.domain.PosicaoDeLimite;
import br.com.itau.financas.autorizacao.domain.Rastreio;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;
import br.com.itau.financas.kernel.Dinheiro;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mapeamento agregado ↔ item. Um item por autorização (~0,7 KB ⇒ 1 WRU por escrita).
 * Os atributos {@code evento_*} são o outbox embutido: o relay monta o CloudEvent a partir deles.
 * {@code expira_em} é o TTL (exclusão gratuita); o histórico longo fica no S3 (trilha de eventos).
 */
final class ItemDeAutorizacao {

    static final String PK = "id_autorizacao";
    static final String VERSAO = "versao";

    private ItemDeAutorizacao() {
    }

    static Map<String, AttributeValue> paraItem(Autorizacao a, Duration retencao) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(a.id().toString()));
        item.put("id_contrato", s(a.contratoId().valor()));
        item.put("id_conta", s(a.contaId().valor()));
        item.put("tipo_operacao", s(a.tipo().name()));
        item.put("valor", n(a.valor().comoTexto()));
        item.put("moeda", s(a.valor().codigoMoeda()));
        item.put("estado", s(a.estado().name()));
        item.put("impressao", s(a.impressao().hash()));
        item.put("saldo_reservado", n(a.posicao().saldoReservado().comoTexto()));
        item.put("limite_disponivel", n(a.posicao().limiteDisponivel().comoTexto()));
        item.put("valor_estornado", n(a.valorEstornado().comoTexto()));
        a.reserva().ifPresent(r -> {
            item.put("reserva_expira_em", s(r.expiraEm().toString()));
            item.put("valor_capturado", n(r.capturado().comoTexto()));
            item.put("valor_liberado", n(r.liberado().comoTexto()));
        });
        a.motivoRecusa().ifPresent(m -> item.put("motivo_recusa", s(m.name())));
        a.idAutorizacaoOriginal().ifPresent(o -> item.put("id_autorizacao_original", s(o.toString())));
        a.idEstabelecimento().ifPresent(e -> item.put("id_estabelecimento", s(e)));
        item.put("correlation_id", s(a.rastreio().correlationId()));
        if (a.rastreio().traceparent() != null) {
            item.put("traceparent", s(a.rastreio().traceparent()));
        }
        item.put("criada_em", s(a.criadaEm().toString()));
        item.put(VERSAO, n(Long.toString(a.versao())));
        item.put("evento_id", s(a.ultimoFato().id().toString()));
        item.put("evento_tipo", s(a.ultimoFato().tipo().name()));
        item.put("evento_ocorrido_em", s(a.ultimoFato().ocorridoEm().toString()));
        item.put("expira_em", n(Long.toString(a.criadaEm().plus(retencao).getEpochSecond())));
        return item;
    }

    static Autorizacao deItem(Map<String, AttributeValue> item) {
        String moeda = texto(item, "moeda");
        return Autorizacao.reconstituir(
                AutorizacaoId.de(texto(item, PK)),
                new ContratoId(texto(item, "id_contrato")),
                new ContaId(texto(item, "id_conta")),
                TipoOperacao.valueOf(texto(item, "tipo_operacao")),
                Dinheiro.de(numero(item, "valor"), moeda),
                EstadoAutorizacao.valueOf(texto(item, "estado")),
                new ImpressaoDigital(texto(item, "impressao")),
                new PosicaoDeLimite(Dinheiro.de(numero(item, "saldo_reservado"), moeda),
                        Dinheiro.de(numero(item, "limite_disponivel"), moeda)),
                opcional(item, "motivo_recusa") == null ? null
                        : Autorizacao.MotivoRecusa.valueOf(opcional(item, "motivo_recusa")),
                Dinheiro.de(numero(item, "valor_estornado"), moeda),
                opcional(item, "reserva_expira_em") == null ? null : new MovimentosDaReserva(
                        Instant.parse(opcional(item, "reserva_expira_em")),
                        Dinheiro.de(numero(item, "valor_capturado"), moeda),
                        Dinheiro.de(numero(item, "valor_liberado"), moeda)),
                opcional(item, "id_autorizacao_original") == null ? null
                        : AutorizacaoId.de(opcional(item, "id_autorizacao_original")),
                opcional(item, "id_estabelecimento"),
                new Rastreio(texto(item, "correlation_id"), opcional(item, "traceparent")),
                Instant.parse(texto(item, "criada_em")),
                Long.parseLong(numero(item, VERSAO)),
                new FatoDeAutorizacao(UUID.fromString(texto(item, "evento_id")),
                        FatoDeAutorizacao.Tipo.valueOf(texto(item, "evento_tipo")),
                        Instant.parse(texto(item, "evento_ocorrido_em"))));
    }

    static AttributeValue s(String valor) {
        return AttributeValue.fromS(valor);
    }

    static AttributeValue n(String valor) {
        return AttributeValue.fromN(valor);
    }

    private static String texto(Map<String, AttributeValue> item, String nome) {
        AttributeValue valor = item.get(nome);
        if (valor == null || valor.s() == null) {
            throw new IllegalStateException("item de autorização sem atributo " + nome);
        }
        return valor.s();
    }

    private static String numero(Map<String, AttributeValue> item, String nome) {
        AttributeValue valor = item.get(nome);
        if (valor == null || valor.n() == null) {
            throw new IllegalStateException("item de autorização sem atributo numérico " + nome);
        }
        return valor.n();
    }

    private static String opcional(Map<String, AttributeValue> item, String nome) {
        AttributeValue valor = item.get(nome);
        return valor == null ? null : valor.s();
    }
}
