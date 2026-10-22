package br.com.itau.financas.autorizacao.config;

import br.com.itau.financas.autorizacao.application.AutorizarTransacaoService;
import br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacao;
import br.com.itau.financas.autorizacao.application.port.out.AutorizacaoRepository;
import br.com.itau.financas.autorizacao.application.port.out.AcessoClienteContratoPort;
import br.com.itau.financas.autorizacao.application.port.in.AutorizarTransacaoInterna;
import br.com.itau.financas.autorizacao.application.port.out.OperacaoDeLimitePort;
import br.com.itau.financas.autorizacao.application.regras.RegraDeConsumoDeLimite;
import br.com.itau.financas.autorizacao.application.regras.RegraDeCredito;
import br.com.itau.financas.autorizacao.application.regras.RegraDeEstorno;
import br.com.itau.financas.autorizacao.application.regras.RegraDeMovimentoDaReserva;
import br.com.itau.financas.autorizacao.adapters.in.sqs.ExpiracaoDeReservaListener;
import br.com.itau.financas.autorizacao.application.ExpirarReservaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/** Wiring dos casos de uso: a camada de aplicação não tem anotações de framework. */
@Configuration
public class CasosDeUsoConfig {

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }

    @Bean
    AutorizarTransacaoService autorizarTransacao(AutorizacaoRepository autorizacoes, OperacaoDeLimitePort limites,
                                          Clock relogio, AutorizacaoProperties propriedades,
                                          AcessoClienteContratoPort acesso) {
        return new AutorizarTransacaoService(autorizacoes, List.of(
                new RegraDeConsumoDeLimite(limites, relogio, propriedades.reserva().validade()),
                new RegraDeCredito(limites, relogio),
                new RegraDeEstorno(autorizacoes, limites, relogio),
                new RegraDeMovimentoDaReserva(autorizacoes, limites, relogio)), acesso);
    }

    @Bean
    ExpirarReservaService expirarReservaService(AutorizacaoRepository autorizacoes, AutorizarTransacaoService autorizar) {
        return new ExpirarReservaService(autorizacoes, autorizar);
    }

    /** Só existe quando a fila de expiração está configurada (ADR 035). */
    @Bean
    @ConditionalOnExpression("!'${autorizacao.reserva.fila-expiracao:}'.isBlank()")
    ExpiracaoDeReservaListener expiracaoDeReservaListener(ExpirarReservaService expirar, ObjectMapper json) {
        return new ExpiracaoDeReservaListener(expirar, json);
    }
}
