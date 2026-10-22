package br.com.itau.financas.contabilizacao.adapters.in.kinesis;

import br.com.itau.financas.contabilizacao.domain.FatoContabil;
import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import br.com.itau.financas.kernel.Dinheiro;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Anticorrupção na entrada: linguagem publicada (CloudEvents v1) → fato da Contabilização.
 * Tipos sem efeito contábil (ex.: TransacaoRecusada) são ignorados aqui, sem tocar no banco.
 */
public final class TradutorDeEventos {

    public Optional<FatoContabil> traduzir(String cloudEventJson) {
        CloudEvent evento = CodecDeEventos.deJson(cloudEventJson);
        return switch (evento.type()) {
            case TiposDeEvento.TRANSACAO_AUTORIZADA -> {
                var p = CodecDeEventos.payloadDe(evento, PayloadsV1.TransacaoAutorizada.class);
                yield Optional.of(fato(evento, FatoContabil.Tipo.TRANSACAO_AUTORIZADA, p.idAutorizacao(),
                        p.idContrato(), p.tipoOperacao(), p.valor(), p.moeda()));
            }
            case TiposDeEvento.ESTORNO_REALIZADO -> {
                var p = CodecDeEventos.payloadDe(evento, PayloadsV1.EstornoRealizado.class);
                yield Optional.of(fato(evento, FatoContabil.Tipo.ESTORNO_REALIZADO, p.idAutorizacao(),
                        p.idContrato(), "ESTORNO", p.valor(), p.moeda()));
            }
            // Tipo publicado conhecido, explicitamente sem efeito contábil.
            case TiposDeEvento.TRANSACAO_RECUSADA -> Optional.empty();
            // Versões/tipos novos não podem ser descartados silenciosamente: o KCL envia à quarentena.
            default -> throw new IllegalArgumentException("tipo CloudEvent não suportado pela Contabilização: "
                    + evento.type());
        };
    }

    private static FatoContabil fato(CloudEvent evento, FatoContabil.Tipo tipo, String idAutorizacao,
                                     String idContrato, String tipoOperacao, String valor, String moeda) {
        return new FatoContabil(UUID.fromString(evento.id()), tipo, idAutorizacao, idContrato, tipoOperacao,
                Dinheiro.de(valor, moeda), Instant.parse(evento.time()));
    }
}
