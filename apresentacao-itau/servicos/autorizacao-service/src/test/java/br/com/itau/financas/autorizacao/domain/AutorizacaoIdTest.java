package br.com.itau.financas.autorizacao.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutorizacaoIdTest {

    private final IdempotencyKey chave = IdempotencyKey.de(UUID.randomUUID().toString());

    @Test
    void mesmaChaveDoMesmoClienteGeraSempreOMesmoId() {
        assertThat(AutorizacaoId.deterministico("canal-app", chave))
                .isEqualTo(AutorizacaoId.deterministico("canal-app", chave));
    }

    @Test
    void mesmaChaveDeClientesDiferentesNaoColide() {
        assertThat(AutorizacaoId.deterministico("canal-app", chave))
                .isNotEqualTo(AutorizacaoId.deterministico("parceiro-x", chave));
    }

    @Test
    void geraUuidVersao5() {
        assertThat(AutorizacaoId.deterministico("canal-app", chave).valor().version()).isEqualTo(5);
    }
}
