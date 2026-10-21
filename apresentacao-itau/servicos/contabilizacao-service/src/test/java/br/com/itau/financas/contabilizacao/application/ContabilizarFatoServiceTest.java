package br.com.itau.financas.contabilizacao.application;

import br.com.itau.financas.contabilizacao.adapters.in.kinesis.TradutorDeEventos;
import br.com.itau.financas.contabilizacao.application.ContabilizarFatoService.Desfecho;
import br.com.itau.financas.contabilizacao.application.port.out.RegistroContabilRepository;
import br.com.itau.financas.contabilizacao.domain.Lancamento;
import br.com.itau.financas.contabilizacao.domain.PlanoDeContas;
import br.com.itau.financas.contabilizacao.domain.RegistroContabil;
import br.com.itau.financas.eventos.CodecDeEventos;
import br.com.itau.financas.eventos.TiposDeEvento;
import br.com.itau.financas.eventos.v1.PayloadsV1;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Consumidor idempotente e tolerante a reentrega e a reordenação (PDF §2.4), do CloudEvent até o razão. */
class ContabilizarFatoServiceTest {

    private final RazaoEmMemoria razao = new RazaoEmMemoria();
    private final ContabilizarFatoService servico = new ContabilizarFatoService(new PlanoDeContas(), razao);
    private final TradutorDeEventos tradutor = new TradutorDeEventos();

    @Test
    void reentregaDoMesmoEventoNaoDuplicaLancamentos() {
        String evento = transacaoAutorizada("DEBITO");

        Desfecho primeira = servico.contabilizar(tradutor.traduzir(evento).orElseThrow());
        Desfecho segunda = servico.contabilizar(tradutor.traduzir(evento).orElseThrow());

        assertThat(primeira).isEqualTo(Desfecho.CONTABILIZADO);
        assertThat(segunda).isEqualTo(Desfecho.DUPLICADO);
        assertThat(razao.registros).hasSize(1);
    }

    @Test
    void reservaEhMarcadaComoProcessadaSemLancamento() {
        Desfecho desfecho = servico.contabilizar(tradutor.traduzir(transacaoAutorizada("RESERVA")).orElseThrow());

        assertThat(desfecho).isEqualTo(Desfecho.SEM_EFEITO_CONTABIL);
        assertThat(razao.registros).isEmpty();
    }

    @Test
    void transacaoRecusadaNaoChegaAoRazao() {
        String recusada = CodecDeEventos.paraJson(CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO,
                TiposDeEvento.TRANSACAO_RECUSADA, Instant.now(), "contrato/CTR-1", "CTR-1", "c", null,
                new PayloadsV1.TransacaoRecusada("AUT", "CTR-1", "0001", "DEBITO", "10.00", "BRL",
                        "LIMITE_INSUFICIENTE", "0.00")));

        assertThat(tradutor.traduzir(recusada)).isEmpty();
    }

    @Test
    void estornoQueChegaAntesDoDebitoOriginalContabilizaOsDoisFatos() {
        String original = UUID.randomUUID().toString();
        String debito = transacaoAutorizada(original, "DEBITO", "150.00");
        String estorno = estornoRealizado(original, "150.00");

        servico.contabilizar(tradutor.traduzir(estorno).orElseThrow());
        servico.contabilizar(tradutor.traduzir(debito).orElseThrow());

        assertThat(razao.registros).hasSize(2);
        assertThat(saldoPorConta(razao.registros)).allSatisfy((conta, saldo) ->
                assertThat(saldo).as("saldo da conta %s", conta).isEqualByComparingTo("0.00"));
    }

    @Test
    void ordemDeChegadaNaoAlteraORazao() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        List<String> eventos = List.of(transacaoAutorizada(a, "DEBITO", "100.00"), estornoRealizado(a, "40.00"),
                transacaoAutorizada(b, "DEBITO", "70.00"), transacaoAutorizada(b, "CREDITO", "30.00"));
        List<String> invertidos = new ArrayList<>(eventos);
        Collections.reverse(invertidos);

        RazaoEmMemoria outroRazao = new RazaoEmMemoria();
        ContabilizarFatoService outroServico = new ContabilizarFatoService(new PlanoDeContas(), outroRazao);
        eventos.forEach(evento -> servico.contabilizar(tradutor.traduzir(evento).orElseThrow()));
        invertidos.forEach(evento -> outroServico.contabilizar(tradutor.traduzir(evento).orElseThrow()));

        assertThat(saldoPorConta(outroRazao.registros)).isEqualTo(saldoPorConta(razao.registros));
    }

    private static Map<String, BigDecimal> saldoPorConta(List<RegistroContabil> registros) {
        Map<String, BigDecimal> saldos = new TreeMap<>();
        for (RegistroContabil registro : registros) {
            for (Lancamento lancamento : registro.lancamentos()) {
                BigDecimal valor = lancamento.valor().valor();
                saldos.merge(lancamento.conta().codigo(),
                        lancamento.natureza() == Lancamento.Natureza.DEVEDORA ? valor : valor.negate(), BigDecimal::add);
            }
        }
        saldos.replaceAll((conta, saldo) -> saldo.setScale(2));
        return saldos;
    }

    private static String transacaoAutorizada(String idAutorizacao, String tipoOperacao, String valor) {
        return CodecDeEventos.paraJson(CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO,
                TiposDeEvento.TRANSACAO_AUTORIZADA, Instant.parse("2026-10-20T15:00:00Z"), "contrato/CTR-1",
                "CTR-1", "corr", null, new PayloadsV1.TransacaoAutorizada(idAutorizacao, "CTR-1",
                        "0001", tipoOperacao, valor, "BRL", valor, "850.00", null)));
    }

    private static String estornoRealizado(String idAutorizacaoOriginal, String valor) {
        return CodecDeEventos.paraJson(CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO,
                TiposDeEvento.ESTORNO_REALIZADO, Instant.parse("2026-10-20T15:05:00Z"), "contrato/CTR-1",
                "CTR-1", "corr", null, new PayloadsV1.EstornoRealizado(UUID.randomUUID().toString(),
                        idAutorizacaoOriginal, "CTR-1", "0001", valor, "BRL", "1000.00")));
    }

    private static String transacaoAutorizada(String tipoOperacao) {
        return CodecDeEventos.paraJson(CodecDeEventos.novo(TiposDeEvento.FONTE_AUTORIZACAO,
                TiposDeEvento.TRANSACAO_AUTORIZADA, Instant.parse("2026-10-20T15:00:00Z"), "contrato/CTR-1",
                "CTR-1", "corr", null, new PayloadsV1.TransacaoAutorizada(UUID.randomUUID().toString(), "CTR-1",
                        "0001", tipoOperacao, "150.00", "BRL", "150.00", "850.00", null)));
    }

    private static final class RazaoEmMemoria implements RegistroContabilRepository {
        private final Set<UUID> processados = ConcurrentHashMap.newKeySet();
        private final List<RegistroContabil> registros = new CopyOnWriteArrayList<>();

        @Override
        public boolean registrarSeInedito(UUID eventoId, Optional<RegistroContabil> registro) {
            if (!processados.add(eventoId)) {
                return false;
            }
            registro.ifPresent(registros::add);
            return true;
        }
    }
}
