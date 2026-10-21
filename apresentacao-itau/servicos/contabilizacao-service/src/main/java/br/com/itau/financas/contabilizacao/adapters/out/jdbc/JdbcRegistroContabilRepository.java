package br.com.itau.financas.contabilizacao.adapters.out.jdbc;

import br.com.itau.financas.contabilizacao.application.port.out.RegistroContabilRepository;
import br.com.itau.financas.contabilizacao.domain.Lancamento;
import br.com.itau.financas.contabilizacao.domain.RegistroContabil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Aurora PostgreSQL. Inbox ({@code evento_processado}) e razão na MESMA transação:
 * ou o evento fica registrado como processado junto com os lançamentos, ou nada é gravado.
 * {@code ON CONFLICT DO NOTHING} resolve a corrida de duas entregas simultâneas do mesmo evento (C8).
 */
public final class JdbcRegistroContabilRepository implements RegistroContabilRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;

    public JdbcRegistroContabilRepository(JdbcTemplate jdbc, TransactionTemplate transacao) {
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    @Override
    public boolean registrarSeInedito(UUID eventoId, Optional<RegistroContabil> registro) {
        Boolean inedito = transacao.execute(status -> {
            int inseridos = jdbc.update(
                    "INSERT INTO evento_processado (evento_id) VALUES (?) ON CONFLICT (evento_id) DO NOTHING",
                    eventoId);
            if (inseridos == 0) {
                return false;
            }
            registro.ifPresent(this::gravar);
            return true;
        });
        return Boolean.TRUE.equals(inedito);
    }

    private void gravar(RegistroContabil registro) {
        jdbc.update("INSERT INTO registro_contabil (fato_id, id_autorizacao, id_contrato, data_contabil) "
                        + "VALUES (?, ?, ?, ?)",
                registro.fatoId(), registro.idAutorizacao(), registro.idContrato(), Date.valueOf(registro.dataContabil()));
        jdbc.batchUpdate("INSERT INTO lancamento (fato_id, conta_codigo, conta_nome, natureza, valor, moeda) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                registro.lancamentos(), registro.lancamentos().size(), (comando, lancamento) -> {
                    comando.setObject(1, registro.fatoId());
                    comando.setString(2, lancamento.conta().codigo());
                    comando.setString(3, lancamento.conta().nome());
                    comando.setString(4, lancamento.natureza() == Lancamento.Natureza.DEVEDORA ? "D" : "C");
                    comando.setBigDecimal(5, lancamento.valor().valor());
                    comando.setString(6, lancamento.valor().codigoMoeda());
                });
    }
}
