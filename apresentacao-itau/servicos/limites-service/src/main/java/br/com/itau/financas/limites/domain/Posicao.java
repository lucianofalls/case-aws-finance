package br.com.itau.financas.limites.domain;

import br.com.itau.financas.kernel.Dinheiro;
import br.com.itau.financas.limites.domain.Excecoes.LimiteInsuficienteException;
import br.com.itau.financas.limites.domain.Excecoes.VersaoObsoletaException;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;

import java.util.Objects;

/**
 * Agregado Posição do contrato — Control Record "Financial Position Log" do BIAN Position Keeping.
 * Atributos oficiais: Position Limit Value = {@code limiteConcedido}; Amount Block = {@code bloqueado} (reservas
 * pendentes, ADR 035).
 * <pre>
 *   limiteConcedido = utilizado + bloqueado + disponivel
 * </pre>
 * Invariantes:
 * <ul>
 *   <li>débito e reserva só são aceitos se {@code disponivel >= valor}</li>
 *   <li>captura e liberação nunca movem mais que o bloqueado</li>
 *   <li>crédito e estorno nunca devolvem mais que o utilizado</li>
 *   <li>alterações do legado só são aplicadas se a versão de origem for mais nova (tolerância a reordenação)</li>
 * </ul>
 * Uma redução de limite abaixo do comprometido deixa o disponível negativo: bloqueia novas operações sem
 * desfazer as aprovadas (ADR 021). Imutável; {@code versao} é usada no controle otimista do repositório.
 */
public final class Posicao {

    private final ContratoId contratoId;
    private final String contaId;
    private final String produto;
    private final Dinheiro limiteConcedido;
    private final Dinheiro disponivel;
    private final Dinheiro bloqueado;
    private final long versao;
    private final long versaoOrigem;

    public Posicao(ContratoId contratoId, String contaId, String produto, Dinheiro limiteConcedido,
                   Dinheiro disponivel, Dinheiro bloqueado, long versao, long versaoOrigem) {
        this.contratoId = Objects.requireNonNull(contratoId);
        this.contaId = Objects.requireNonNull(contaId);
        this.produto = Objects.requireNonNull(produto);
        this.limiteConcedido = Objects.requireNonNull(limiteConcedido);
        this.disponivel = Objects.requireNonNull(disponivel);
        this.bloqueado = Objects.requireNonNull(bloqueado);
        this.versao = versao;
        this.versaoOrigem = versaoOrigem;
    }

    public static Posicao nova(ContratoId contratoId, String contaId, String produto, Dinheiro limiteConcedido,
                               long versaoOrigem) {
        if (limiteConcedido.ehNegativo()) {
            throw new RegraDeNegocioVioladaException("limite-negativo", "limite concedido não pode ser negativo");
        }
        return new Posicao(contratoId, contaId, produto, limiteConcedido, limiteConcedido,
                Dinheiro.zero(limiteConcedido.moeda()), 1, versaoOrigem);
    }

    public Posicao aplicar(TipoOperacao tipo, Dinheiro valor) {
        return switch (tipo.efeito()) {
            case CONSOME -> comSaldos(retirarDoDisponivel(valor), bloqueado);
            case BLOQUEIA -> comSaldos(retirarDoDisponivel(valor), bloqueado.somar(valor));
            case CAPTURA -> comSaldos(disponivel, retirarDoBloqueado(valor));
            case LIBERA -> comSaldos(disponivel.somar(valor), retirarDoBloqueado(valor));
            case DEVOLVE -> comSaldos(devolverAoDisponivel(valor), bloqueado);
        };
    }

    /** Desfaz uma operação órfã: efeito inverso, sem checar invariante (é correção, não autorização). */
    public Posicao reverter(TipoOperacao tipo, Dinheiro valor) {
        return switch (tipo.efeito()) {
            case CONSOME -> comSaldos(disponivel.somar(valor), bloqueado);
            case BLOQUEIA -> comSaldos(disponivel.somar(valor), bloqueado.subtrair(valor));
            case CAPTURA -> comSaldos(disponivel, bloqueado.somar(valor));
            case LIBERA -> comSaldos(disponivel.subtrair(valor), bloqueado.somar(valor));
            case DEVOLVE -> comSaldos(disponivel.subtrair(valor), bloqueado);
        };
    }

    public Posicao comNovoLimite(Dinheiro novoLimite, long novaVersaoOrigem) {
        if (novaVersaoOrigem <= versaoOrigem) {
            throw new VersaoObsoletaException(novaVersaoOrigem, versaoOrigem);
        }
        if (novoLimite.ehNegativo()) {
            throw new RegraDeNegocioVioladaException("limite-negativo", "limite concedido não pode ser negativo");
        }
        Dinheiro delta = novoLimite.subtrair(limiteConcedido);
        return new Posicao(contratoId, contaId, produto, novoLimite, disponivel.somar(delta), bloqueado, versao + 1,
                novaVersaoOrigem);
    }

    /** Limite comprometido (utilizado + bloqueado): é o {@code saldo_reservado} do contrato da API (PDF §2.3). */
    public Dinheiro comprometido() {
        return limiteConcedido.subtrair(disponivel);
    }

    /** Parte efetivamente consumida (débitos e capturas, menos créditos e estornos). */
    public Dinheiro utilizado() {
        return comprometido().subtrair(bloqueado);
    }

    private Dinheiro retirarDoDisponivel(Dinheiro valor) {
        if (disponivel.menorQue(valor)) {
            throw new LimiteInsuficienteException(this);
        }
        return disponivel.subtrair(valor);
    }

    private Dinheiro retirarDoBloqueado(Dinheiro valor) {
        if (valor.maiorQue(bloqueado)) {
            throw new RegraDeNegocioVioladaException("movimento-excede-bloqueado",
                    "movimento de " + valor + " excede o bloqueado " + bloqueado);
        }
        return bloqueado.subtrair(valor);
    }

    private Dinheiro devolverAoDisponivel(Dinheiro valor) {
        if (valor.maiorQue(utilizado())) {
            throw new RegraDeNegocioVioladaException("devolucao-excede-utilizado",
                    "devolução de " + valor + " excede o utilizado " + utilizado());
        }
        return disponivel.somar(valor);
    }

    private Posicao comSaldos(Dinheiro novoDisponivel, Dinheiro novoBloqueado) {
        return new Posicao(contratoId, contaId, produto, limiteConcedido, novoDisponivel, novoBloqueado, versao + 1,
                versaoOrigem);
    }

    public ContratoId contratoId() { return contratoId; }
    public String contaId() { return contaId; }
    public String produto() { return produto; }
    public Dinheiro limiteConcedido() { return limiteConcedido; }
    public Dinheiro disponivel() { return disponivel; }
    public Dinheiro bloqueado() { return bloqueado; }
    public long versao() { return versao; }
    public long versaoOrigem() { return versaoOrigem; }
}
