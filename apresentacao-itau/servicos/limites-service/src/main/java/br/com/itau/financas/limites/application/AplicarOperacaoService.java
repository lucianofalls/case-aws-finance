package br.com.itau.financas.limites.application;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository.ResultadoDeEscrita;
import br.com.itau.financas.limites.domain.Excecoes.ContratoSemLimiteException;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Identificadores.OperacaoId;
import br.com.itau.financas.limites.domain.Operacao;
import br.com.itau.financas.limites.domain.Posicao;
import br.com.itau.financas.limites.domain.RegraDeNegocioVioladaException;
import br.com.itau.financas.limites.domain.TipoOperacao;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Caso de uso "aplicar operação na posição" — BIAN Action Terms Control/Capture do Position Keeping.
 * <p>
 * Concorrência (docs/concorrencia.md, C1–C3): ler a posição → aplicar a invariante no agregado →
 * gravar posição + operação numa transação condicional à versão lida. Em disputa, relê e tenta de novo:
 * a invariante é sempre reavaliada sobre o saldo mais recente, então nunca se gasta além do limite.
 */
public final class AplicarOperacaoService {

    public record Comando(String idContrato, String idOperacao, String tipoOperacao, String valor, String moeda,
                          String impressao, String idOperacaoOriginal, String idConta) {
    }

    /** @param criadaAgora false quando é a repetição idempotente de uma operação já aplicada */
    public record Resultado(Operacao operacao, boolean criadaAgora) {
    }

    private final PosicaoRepository repositorio;
    private final PoliticaDeRetentativa retentativa;
    private final Clock relogio;
    private final Duration janelaDeRepeticao;

    public AplicarOperacaoService(PosicaoRepository repositorio, PoliticaDeRetentativa retentativa, Clock relogio,
                                  Duration janelaDeRepeticao) {
        this.repositorio = repositorio;
        this.retentativa = retentativa;
        this.relogio = relogio;
        this.janelaDeRepeticao = janelaDeRepeticao;
    }

    public Resultado aplicar(Comando comando) {
        ContratoId contrato = new ContratoId(comando.idContrato());
        OperacaoId id = OperacaoId.de(comando.idOperacao());
        TipoOperacao tipo = TipoOperacao.de(comando.tipoOperacao());
        Dinheiro valor = valorDe(comando);
        OperacaoId original = comando.idOperacaoOriginal() == null ? null : OperacaoId.de(comando.idOperacaoOriginal());

        Optional<Resultado> repeticao = repeticao(contrato, id, comando.impressao());
        if (repeticao.isPresent()) {
            return repeticao.get();
        }
        return retentativa.executar("operação " + id, () -> {
            Posicao lida = repositorio.buscarPosicao(contrato).orElseThrow(() -> new ContratoSemLimiteException(contrato));
            if (comando.idConta() == null || !lida.contaId().equals(comando.idConta())) {
                throw new br.com.itau.financas.limites.domain.RegraDeNegocioVioladaException(
                        "conta-nao-pertence-ao-contrato", "conta informada não corresponde ao titular do contrato");
            }
            Posicao nova = lida.aplicar(tipo, valor);
            Operacao operacao = Operacao.efetivada(id, contrato, tipo, valor, comando.impressao(), original, nova,
                    relogio.instant());
            ResultadoDeEscrita escrita = repositorio.registrar(lida, nova, operacao);
            if (escrita == ResultadoDeEscrita.GRAVADO) {
                return new Resultado(operacao, true);
            }
            if (escrita == ResultadoDeEscrita.OPERACAO_JA_EXISTE) {
                return repeticao(contrato, id, comando.impressao()).orElseThrow();
            }
            return null;
        });
    }

    private Optional<Resultado> repeticao(ContratoId contrato, OperacaoId id, String impressao) {
        return repositorio.buscarOperacao(contrato, id)
                .map(existente -> new Resultado(
                        existente.garantirRepeticaoValida(impressao, relogio.instant(), janelaDeRepeticao), false));
    }

    private static Dinheiro valorDe(Comando comando) {
        try {
            return Dinheiro.de(comando.valor(), comando.moeda());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RegraDeNegocioVioladaException("valor-invalido", "valor/moeda inválidos");
        }
    }
}
