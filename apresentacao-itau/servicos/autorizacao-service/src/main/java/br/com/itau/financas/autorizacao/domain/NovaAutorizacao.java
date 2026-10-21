package br.com.itau.financas.autorizacao.domain;

import br.com.itau.financas.autorizacao.domain.Identificadores.ContaId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.kernel.Dinheiro;

import java.util.Objects;
import java.util.Optional;

/** Intenção de autorização já validada (todos os value objects construídos). */
public record NovaAutorizacao(
        AutorizacaoId id,
        ContratoId contratoId,
        ContaId contaId,
        TipoOperacao tipo,
        Dinheiro valor,
        ImpressaoDigital impressao,
        String idEstabelecimento,
        AutorizacaoId idAutorizacaoOriginal,
        Rastreio rastreio) {

    public NovaAutorizacao {
        Objects.requireNonNull(id);
        Objects.requireNonNull(contratoId);
        Objects.requireNonNull(contaId);
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(valor);
        Objects.requireNonNull(impressao);
        Objects.requireNonNull(rastreio);
        if (!valor.ehPositivo()) {
            throw new RegraDeNegocioVioladaException("valor-invalido", "valor deve ser maior que zero");
        }
        if (tipo.exigeOriginal() && idAutorizacaoOriginal == null) {
            throw new RegraDeNegocioVioladaException(tipo == TipoOperacao.ESTORNO ? "estorno-sem-original" : "sem-original",
                    tipo + " exige metadata.id_autorizacao_original");
        }
        if (!tipo.exigeOriginal() && idAutorizacaoOriginal != null) {
            throw new RegraDeNegocioVioladaException("original-indevido",
                    "id_autorizacao_original só se aplica a ESTORNO, CAPTURA e LIBERACAO");
        }
    }

    public Optional<AutorizacaoId> original() {
        return Optional.ofNullable(idAutorizacaoOriginal);
    }
}
