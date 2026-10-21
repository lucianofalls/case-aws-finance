package br.com.itau.financas.autorizacao.domain;

import br.com.itau.financas.autorizacao.domain.Identificadores.ContaId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.kernel.Dinheiro;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Agregado Autorização — Control Record "Interactive Transaction Assessment" do BIAN.
 * <p>
 * Invariantes:
 * <ul>
 *   <li>só DEBITO e CAPTURA APROVADOS (ou parcialmente estornados) aceitam estorno; a soma dos estornos nunca
 *       ultrapassa o valor aprovado</li>
 *   <li>uma RESERVA aprovada só aceita CAPTURA e LIBERACAO até o seu saldo; a captura não é aceita depois da
 *       expiração; com o saldo zerado, a reserva fica ENCERRADA (ADR 035)</li>
 *   <li>uma Idempotency-Key nunca é reaproveitada com outro conteúdo</li>
 * </ul>
 * Imutável: toda mudança devolve uma nova instância com {@code versao + 1} (controle otimista no repositório).
 */
public final class Autorizacao {

    public enum MotivoRecusa { LIMITE_INSUFICIENTE }

    private final AutorizacaoId id;
    private final ContratoId contratoId;
    private final ContaId contaId;
    private final TipoOperacao tipo;
    private final Dinheiro valor;
    private final EstadoAutorizacao estado;
    private final ImpressaoDigital impressao;
    private final PosicaoDeLimite posicao;
    private final MotivoRecusa motivoRecusa;
    private final Dinheiro valorEstornado;
    private final MovimentosDaReserva reserva;
    private final AutorizacaoId idAutorizacaoOriginal;
    private final String idEstabelecimento;
    private final Rastreio rastreio;
    private final Instant criadaEm;
    private final long versao;
    private final FatoDeAutorizacao ultimoFato;

    private Autorizacao(AutorizacaoId id, ContratoId contratoId, ContaId contaId, TipoOperacao tipo, Dinheiro valor,
                        EstadoAutorizacao estado, ImpressaoDigital impressao, PosicaoDeLimite posicao,
                        MotivoRecusa motivoRecusa, Dinheiro valorEstornado, MovimentosDaReserva reserva,
                        AutorizacaoId idAutorizacaoOriginal, String idEstabelecimento, Rastreio rastreio,
                        Instant criadaEm, long versao, FatoDeAutorizacao ultimoFato) {
        this.id = Objects.requireNonNull(id);
        this.contratoId = Objects.requireNonNull(contratoId);
        this.contaId = Objects.requireNonNull(contaId);
        this.tipo = Objects.requireNonNull(tipo);
        this.valor = Objects.requireNonNull(valor);
        this.estado = Objects.requireNonNull(estado);
        this.impressao = Objects.requireNonNull(impressao);
        this.posicao = Objects.requireNonNull(posicao);
        this.motivoRecusa = motivoRecusa;
        this.valorEstornado = Objects.requireNonNull(valorEstornado);
        if ((tipo == TipoOperacao.RESERVA) != (reserva != null)) {
            throw new IllegalArgumentException("movimentos da reserva só existem (e sempre existem) numa RESERVA");
        }
        this.reserva = reserva;
        this.idAutorizacaoOriginal = idAutorizacaoOriginal;
        this.idEstabelecimento = idEstabelecimento;
        this.rastreio = Objects.requireNonNull(rastreio);
        this.criadaEm = Objects.requireNonNull(criadaEm);
        this.versao = versao;
        this.ultimoFato = Objects.requireNonNull(ultimoFato);
    }

    /** Aprovação de qualquer tipo, exceto RESERVA (que precisa da validade: {@link #reservaAprovada}). */
    public static Autorizacao aprovada(NovaAutorizacao pedido, PosicaoDeLimite posicao, Instant agora) {
        FatoDeAutorizacao.Tipo fato = pedido.tipo() == TipoOperacao.ESTORNO
                ? FatoDeAutorizacao.Tipo.ESTORNO_REALIZADO
                : FatoDeAutorizacao.Tipo.TRANSACAO_AUTORIZADA;
        return criar(pedido, EstadoAutorizacao.APROVADA, posicao, null, null, FatoDeAutorizacao.novo(fato, agora),
                agora);
    }

    /** RESERVA aprovada: o valor fica bloqueado até a captura, a liberação ou {@code agora + validade}. */
    public static Autorizacao reservaAprovada(NovaAutorizacao pedido, PosicaoDeLimite posicao, Instant agora,
                                              Duration validade) {
        return criar(pedido, EstadoAutorizacao.APROVADA, posicao, null,
                MovimentosDaReserva.nova(agora.plus(validade), pedido.valor().moeda()),
                FatoDeAutorizacao.novo(FatoDeAutorizacao.Tipo.TRANSACAO_AUTORIZADA, agora), agora);
    }

    public static Autorizacao recusada(NovaAutorizacao pedido, MotivoRecusa motivo, PosicaoDeLimite posicao,
                                       Instant agora) {
        MovimentosDaReserva reserva = pedido.tipo() == TipoOperacao.RESERVA
                ? MovimentosDaReserva.nova(agora, pedido.valor().moeda())
                : null;
        return criar(pedido, EstadoAutorizacao.RECUSADA, posicao, Objects.requireNonNull(motivo), reserva,
                FatoDeAutorizacao.novo(FatoDeAutorizacao.Tipo.TRANSACAO_RECUSADA, agora), agora);
    }

    public static Autorizacao reconstituir(AutorizacaoId id, ContratoId contratoId, ContaId contaId, TipoOperacao tipo,
                                           Dinheiro valor, EstadoAutorizacao estado, ImpressaoDigital impressao,
                                           PosicaoDeLimite posicao, MotivoRecusa motivoRecusa, Dinheiro valorEstornado,
                                           MovimentosDaReserva reserva, AutorizacaoId idAutorizacaoOriginal,
                                           String idEstabelecimento, Rastreio rastreio, Instant criadaEm, long versao,
                                           FatoDeAutorizacao ultimoFato) {
        return new Autorizacao(id, contratoId, contaId, tipo, valor, estado, impressao, posicao, motivoRecusa,
                valorEstornado, reserva, idAutorizacaoOriginal, idEstabelecimento, rastreio, criadaEm, versao,
                ultimoFato);
    }

    private static Autorizacao criar(NovaAutorizacao pedido, EstadoAutorizacao estado, PosicaoDeLimite posicao,
                                     MotivoRecusa motivo, MovimentosDaReserva reserva, FatoDeAutorizacao fato,
                                     Instant agora) {
        return new Autorizacao(pedido.id(), pedido.contratoId(), pedido.contaId(), pedido.tipo(), pedido.valor(),
                estado, pedido.impressao(), posicao, motivo, Dinheiro.zero(pedido.valor().moeda()), reserva,
                pedido.idAutorizacaoOriginal(), pedido.idEstabelecimento(), pedido.rastreio(), agora, 1, fato);
    }

    /** Valida, antes de mexer no limite, se este estorno é permitido (fail fast). */
    public void validarEstorno(ContratoId contratoDoEstorno, Dinheiro valorDoEstorno) {
        if (!contratoId.equals(contratoDoEstorno)) {
            throw new RegraDeNegocioVioladaException("estorno-contrato-divergente",
                    "a autorização original pertence a outro contrato");
        }
        if (!tipo.aceitaEstorno() || !estado.aceitaEstorno()) {
            throw new RegraDeNegocioVioladaException("estorno-nao-permitido",
                    "autorização " + id + " em estado " + estado + " (" + tipo + ") não aceita estorno");
        }
        if (valorEstornado.somar(valorDoEstorno).maiorQue(valor)) {
            throw new RegraDeNegocioVioladaException("estorno-excede-valor",
                    "estorno excede o valor aprovado: aprovado " + valor + ", já estornado " + valorEstornado);
        }
    }

    /** Aplica o estorno no agregado original. Não gera fato: o fato é da autorização de ESTORNO. */
    public Autorizacao comEstorno(ContratoId contratoDoEstorno, Dinheiro valorDoEstorno) {
        validarEstorno(contratoDoEstorno, valorDoEstorno);
        Dinheiro totalEstornado = valorEstornado.somar(valorDoEstorno);
        EstadoAutorizacao novoEstado = totalEstornado.compareTo(valor) == 0
                ? EstadoAutorizacao.ESTORNADA
                : EstadoAutorizacao.ESTORNADA_PARCIALMENTE;
        return new Autorizacao(id, contratoId, contaId, tipo, valor, novoEstado, impressao, posicao, motivoRecusa,
                totalEstornado, reserva, idAutorizacaoOriginal, idEstabelecimento, rastreio, criadaEm, versao + 1,
                ultimoFato);
    }

    /** Saldo ainda bloqueado de uma RESERVA (valor reservado − capturado − liberado). */
    public Dinheiro saldoDaReserva() {
        if (reserva == null) {
            throw new IllegalStateException("autorização " + id + " não é uma RESERVA");
        }
        return valor.subtrair(reserva.movimentado());
    }

    /** Valida, antes de mexer no limite, se a CAPTURA ou a LIBERACAO é permitida nesta reserva (fail fast). */
    public void validarMovimentoDaReserva(ContratoId contratoDoMovimento, TipoOperacao movimento,
                                          Dinheiro valorDoMovimento, Instant agora) {
        if (!contratoId.equals(contratoDoMovimento)) {
            throw new RegraDeNegocioVioladaException("reserva-contrato-divergente",
                    "a reserva original pertence a outro contrato");
        }
        if (tipo != TipoOperacao.RESERVA) {
            throw new RegraDeNegocioVioladaException("original-nao-e-reserva",
                    movimento + " exige uma RESERVA como original; " + id + " é " + tipo);
        }
        if (estado != EstadoAutorizacao.APROVADA) {
            throw new RegraDeNegocioVioladaException("reserva-encerrada", "reserva " + id + " em estado " + estado);
        }
        if (movimento == TipoOperacao.CAPTURA && reserva.expirou(agora)) {
            throw new RegraDeNegocioVioladaException("reserva-expirada",
                    "reserva " + id + " expirou em " + reserva.expiraEm() + "; não aceita captura");
        }
        if (valorDoMovimento.maiorQue(saldoDaReserva())) {
            throw new RegraDeNegocioVioladaException("movimento-excede-reserva",
                    movimento + " de " + valorDoMovimento + " excede o saldo da reserva " + saldoDaReserva());
        }
    }

    /** Aplica o movimento na reserva original. Não gera fato: o fato é da autorização de CAPTURA/LIBERACAO. */
    public Autorizacao comMovimentoDaReserva(ContratoId contratoDoMovimento, TipoOperacao movimento,
                                             Dinheiro valorDoMovimento, Instant agora) {
        validarMovimentoDaReserva(contratoDoMovimento, movimento, valorDoMovimento, agora);
        MovimentosDaReserva movimentos = reserva.com(movimento, valorDoMovimento);
        EstadoAutorizacao novoEstado = valor.subtrair(movimentos.movimentado()).ehPositivo()
                ? EstadoAutorizacao.APROVADA
                : EstadoAutorizacao.ENCERRADA;
        return new Autorizacao(id, contratoId, contaId, tipo, valor, novoEstado, impressao, posicao, motivoRecusa,
                valorEstornado, movimentos, idAutorizacaoOriginal, idEstabelecimento, rastreio, criadaEm, versao + 1,
                ultimoFato);
    }

    public void garantirMesmaRequisicao(ImpressaoDigital outra) {
        if (!impressao.equals(outra)) {
            throw new RequisicaoIdempotenteDivergenteException(id);
        }
    }

    public boolean foiAprovada() {
        return estado != EstadoAutorizacao.RECUSADA;
    }

    public AutorizacaoId id() { return id; }
    public ContratoId contratoId() { return contratoId; }
    public ContaId contaId() { return contaId; }
    public TipoOperacao tipo() { return tipo; }
    public Dinheiro valor() { return valor; }
    public EstadoAutorizacao estado() { return estado; }
    public ImpressaoDigital impressao() { return impressao; }
    public PosicaoDeLimite posicao() { return posicao; }
    public Optional<MotivoRecusa> motivoRecusa() { return Optional.ofNullable(motivoRecusa); }
    public Dinheiro valorEstornado() { return valorEstornado; }
    public Optional<MovimentosDaReserva> reserva() { return Optional.ofNullable(reserva); }
    public Optional<AutorizacaoId> idAutorizacaoOriginal() { return Optional.ofNullable(idAutorizacaoOriginal); }
    public Optional<String> idEstabelecimento() { return Optional.ofNullable(idEstabelecimento); }
    public Rastreio rastreio() { return rastreio; }
    public Instant criadaEm() { return criadaEm; }
    public long versao() { return versao; }
    public FatoDeAutorizacao ultimoFato() { return ultimoFato; }
}
