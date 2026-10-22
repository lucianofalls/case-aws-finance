package br.com.itau.financas.autorizacao.adapters.in.web;

import jakarta.validation.constraints.Digits;
import br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/** Corpo do POST /v1/contratos/{id_contrato}/autorizacoes (contracts/openapi/autorizacoes-v1.yaml). */
public record AutorizacaoRequest(
        @Size(max = 64) String idContrato,
        @NotBlank @Size(max = 64) String idConta,
        @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal valor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String moeda,
        @NotBlank @Pattern(regexp = "DEBITO|CREDITO|RESERVA|CAPTURA|LIBERACAO|ESTORNO") String tipoOperacao,
        @Size(max = 64) String idEstabelecimento,
        Map<String, Object> metadata) {

    static final String CHAVE_ORIGINAL = "id_autorizacao_original";

    String idAutorizacaoOriginal() {
        if (metadata == null || metadata.get(CHAVE_ORIGINAL) == null) {
            return null;
        }
        Object original = metadata.get(CHAVE_ORIGINAL);
        if (!(original instanceof String texto) || !texto.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new RegraDeNegocioVioladaException("original-invalido",
                    "metadata.id_autorizacao_original deve ser uma string UUID");
        }
        return texto;
    }
}
