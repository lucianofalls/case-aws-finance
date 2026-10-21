package br.com.itau.financas.acl;

import br.com.itau.financas.acl.RegistroDeContratoLegado.RegistroLegadoInvalidoException;
import br.com.itau.financas.eventos.CloudEvent;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-Corruption Layer: traduz o modelo do legado para a linguagem publicada ({@code LimiteConcedido} v1).
 * <ul>
 *   <li>centavos com zeros à esquerda → string decimal</li>
 *   <li>códigos de produto do legado → nomes do domínio (consórcio não tem SD no BIAN — lacuna documentada)</li>
 *   <li>data local de Brasília → instante UTC</li>
 *   <li>id do evento determinístico (contrato + sequência): reenvio do legado gera o mesmo id e é deduplicado</li>
 * </ul>
 */
public final class TradutorDeContratoLegado {

    private static final Map<String, String> PRODUTOS = Map.of(
            "CP", "CREDITO_PESSOAL",
            "CC", "CARTAO_DE_CREDITO",
            "CE", "CHEQUE_ESPECIAL",
            "IN", "INVESTIMENTO",
            "SG", "SEGURO",
            "CS", "CONSORCIO");
    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    public CloudEvent traduzir(RegistroDeContratoLegado registro) {
        String produto = PRODUTOS.get(registro.codigoProduto());
        if (produto == null) {
            throw new RegistroLegadoInvalidoException("código de produto desconhecido: " + registro.codigoProduto());
        }
        String limite = centavosParaDecimal(registro.valorLimiteEmCentavos());
        long versao = numero(registro.sequenciaAtualizacao(), "NUM-SEQ-ATU");
        var ocorridoEm = dataHora(registro.dataHoraAtualizacao()).atZone(BRASILIA).toInstant();
        String idEvento = UUID.nameUUIDFromBytes((registro.numeroContrato() + ":" + versao)
                .getBytes(StandardCharsets.UTF_8)).toString();

        return CodecDeEventos.novo(idEvento, TiposDeEvento.FONTE_CORE_BANKING_ACL, TiposDeEvento.LIMITE_CONCEDIDO,
                ocorridoEm, "contrato/" + registro.numeroContrato(), registro.numeroContrato(), idEvento, null,
                new PayloadsV1.LimiteConcedido(registro.numeroContrato(), registro.numeroConta(), produto, limite,
                        registro.codigoMoeda(), versao));
    }

    private static String centavosParaDecimal(String centavos) {
        return BigDecimal.valueOf(numero(centavos, "VLR-LIMITE"), 2).toPlainString();
    }

    private static long numero(String texto, String campo) {
        try {
            return Long.parseLong(texto);
        } catch (NumberFormatException e) {
            throw new RegistroLegadoInvalidoException(campo + " não numérico: '" + texto + "'");
        }
    }

    private static LocalDateTime dataHora(String texto) {
        try {
            return LocalDateTime.parse(texto, FORMATO_DATA);
        } catch (DateTimeParseException e) {
            throw new RegistroLegadoInvalidoException("DAT-HOR-ATU inválida: '" + texto + "'");
        }
    }
}
