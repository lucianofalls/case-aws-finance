package br.com.itau.financas.autorizacao.application;

import br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacao;
import br.com.itau.financas.autorizacao.application.port.out.AutorizacaoRepository;
import br.com.itau.financas.autorizacao.application.port.out.AcessoClienteContratoPort;
import br.com.itau.financas.autorizacao.application.regras.RegraDeOperacao;
import br.com.itau.financas.autorizacao.application.regras.RegraDeOperacao.Decisao;
import br.com.itau.financas.autorizacao.domain.Autorizacao;
import br.com.itau.financas.autorizacao.domain.AutorizacaoId;
import br.com.itau.financas.autorizacao.domain.IdempotencyKey;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContaId;
import br.com.itau.financas.autorizacao.domain.Identificadores.ContratoId;
import br.com.itau.financas.autorizacao.domain.ImpressaoDigital;
import br.com.itau.financas.autorizacao.domain.NovaAutorizacao;
import br.com.itau.financas.autorizacao.domain.Rastreio;
import br.com.itau.financas.autorizacao.domain.RegraDeNegocioVioladaException;
import br.com.itau.financas.autorizacao.domain.TipoOperacao;
import br.com.itau.financas.kernel.Dinheiro;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orquestra a autorização (mini-saga sem estado PENDENTE persistido — ADR 027):
 * <ol>
 *   <li>id determinístico a partir da Idempotency-Key → repetição devolve a decisão original</li>
 *   <li>a regra do tipo de operação aplica no Limites (idempotente pelo mesmo id)</li>
 *   <li>grava a decisão com escrita condicional (a decisão e o evento numa única escrita)</li>
 *   <li>resultado incerto (timeout/falha após o limite) → propaga 503. A operação que o Limites tenha aplicado
 *       é verificada 15 min depois pelo agendamento do próprio Limites (ADR 032): esta task pode até cair.</li>
 * </ol>
 */
public final class AutorizarTransacaoService implements AutorizarTransacao, br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacaoInterna {

    private final AutorizacaoRepository autorizacoes;
    private final AcessoClienteContratoPort acessoCliente;
    private final Map<TipoOperacao, RegraDeOperacao> regras = new EnumMap<>(TipoOperacao.class);

    public AutorizarTransacaoService(AutorizacaoRepository autorizacoes, List<RegraDeOperacao> regras,
                                    AcessoClienteContratoPort acessoCliente) {
        this.acessoCliente = acessoCliente;
        this.autorizacoes = autorizacoes;
        regras.forEach(regra -> regra.tiposAtendidos().forEach(tipo -> this.regras.put(tipo, regra)));
        for (TipoOperacao tipo : TipoOperacao.values()) {
            if (!this.regras.containsKey(tipo)) {
                throw new IllegalStateException("nenhuma regra registrada para " + tipo);
            }
        }
    }

    @Override
    public ResultadoAutorizacao executar(SolicitacaoDeAutorizacao solicitacao) {
        // Checagem antes de cache idempotente e ANTES de qualquer operação do Limites.
        // O cliente é derivado do token validado no edge; nunca confiar no body.
        acessoCliente.exigirAcesso(solicitacao.clienteId(), solicitacao.idContrato(), solicitacao.idConta());
        return executarInternamente(solicitacao);
    }

    @Override
    public ResultadoAutorizacao executarInternamente(SolicitacaoDeAutorizacao solicitacao) {
        // Uso EXCLUSIVO pela expiração interna. A mensagem traz somente id da reserva;
        // ExpirarReservaService recupera contrato/conta da autorização já persistida.
        NovaAutorizacao pedido = interpretar(solicitacao);

        Optional<ResultadoAutorizacao> repeticao = repeticaoIdempotente(pedido);
        if (repeticao.isPresent()) {
            return repeticao.get();
        }

        Decisao decisao = regras.get(pedido.tipo()).decidir(pedido);
        return gravar(pedido, decisao);
    }

    private Optional<ResultadoAutorizacao> repeticaoIdempotente(NovaAutorizacao pedido) {
        return autorizacoes.buscar(pedido.id()).map(existente -> {
            existente.garantirMesmaRequisicao(pedido.impressao());
            return new ResultadoAutorizacao(existente, false);
        });
    }

    private ResultadoAutorizacao gravar(NovaAutorizacao pedido, Decisao decisao) {
        boolean gravou;
        try {
            gravou = decisao.originalAtualizado()
                    .map(original -> autorizacoes.inserirComOriginalAtualizado(decisao.autorizacao(), original))
                    .orElseGet(() -> autorizacoes.inserirSeAusente(decisao.autorizacao()));
        } catch (ConflitoDeConcorrenciaException e) {
            // Ex.: dois estornos simultâneos do mesmo original. O limite já foi aplicado: sem decisão gravada, o
            // reconciliador reverte a operação 15 min depois (agendada pelo Limites, ADR 032).
            throw e;
        } catch (RuntimeException e) {
            // O limite já foi aplicado e a decisão não foi gravada: a retentativa do cliente (mesma chave, até 10 min)
            // conclui; sem retentativa, o reconciliador reverte aos 15 min.
            throw new LimitesIndisponivelException("falha ao registrar a decisão", true, e);
        }
        if (gravou) {
            return new ResultadoAutorizacao(decisao.autorizacao(), true);
        }
        // Corrida com uma requisição concorrente de mesma Idempotency-Key: vale a que gravou primeiro.
        return repeticaoIdempotente(pedido)
                .orElseThrow(() -> new ConflitoDeConcorrenciaException("autorização " + pedido.id() + " em disputa"));
    }

    private static NovaAutorizacao interpretar(SolicitacaoDeAutorizacao s) {
        if (s.valor() == null || s.moeda() == null) {
            throw new RegraDeNegocioVioladaException("campo-obrigatorio", "valor e moeda são obrigatórios");
        }
        Dinheiro valor;
        try {
            valor = Dinheiro.de(s.valor(), s.moeda());
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocioVioladaException("valor-invalido", e.getMessage());
        }
        ContratoId contrato = new ContratoId(s.idContrato());
        ContaId conta = new ContaId(s.idConta());
        TipoOperacao tipo = TipoOperacao.de(s.tipoOperacao());
        AutorizacaoId original = s.idAutorizacaoOriginal() == null ? null : AutorizacaoId.de(s.idAutorizacaoOriginal());
        AutorizacaoId id = AutorizacaoId.deterministico(s.clienteId(), IdempotencyKey.de(s.idempotencyKey()));
        ImpressaoDigital impressao = ImpressaoDigital.de(contrato.valor(), conta.valor(), valor.comoTexto(),
                valor.codigoMoeda(), tipo.name(), s.idEstabelecimento(), s.idAutorizacaoOriginal());
        return new NovaAutorizacao(id, contrato, conta, tipo, valor, impressao, s.idEstabelecimento(), original,
                new Rastreio(s.correlationId(), s.traceparent()));
    }
}
