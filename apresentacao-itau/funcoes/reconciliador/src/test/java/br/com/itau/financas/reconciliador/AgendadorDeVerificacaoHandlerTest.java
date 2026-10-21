package br.com.itau.financas.reconciliador;

import br.com.itau.financas.reconciliador.AgendarVerificacoes.OperacaoGravada;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Auditoria C01: toda operação gravada no Limites agenda a própria verificação, sem depender da Autorização. */
class AgendadorDeVerificacaoHandlerTest {

    @Test
    void agendaSoOperacoesNovasEIgnoraPosicaoEOutbox() {
        List<OperacaoGravada> agendadas = new ArrayList<>();
        var handler = new AgendadorDeVerificacaoHandler(new AgendarVerificacoes(operacoes -> {
            agendadas.addAll(operacoes);
            return Set.of();
        }));

        StreamsEventResponse resposta = handler.handleRequest(lote(
                registro("1", "INSERT", "CTR-1", "OPERACAO#aut-1"),
                registro("2", "MODIFY", "CTR-1", "POSICAO"),
                registro("3", "INSERT", "CTR-1", "OUT#evt-1"),
                registro("4", "MODIFY", "CTR-1", "OPERACAO#aut-1"),
                registro("5", "INSERT", "CTR-2", "OPERACAO#aut-2")), null);

        assertThat(agendadas).containsExactly(new OperacaoGravada("1", "CTR-1", "aut-1"),
                new OperacaoGravada("5", "CTR-2", "aut-2"));
        assertThat(resposta.getBatchItemFailures()).isNullOrEmpty();
    }

    @Test
    void falhaParcialDevolveAPrimeiraSequenciaNaoAgendada() {
        var handler = new AgendadorDeVerificacaoHandler(new AgendarVerificacoes(operacoes -> Set.of("7", "9")));

        StreamsEventResponse resposta = handler.handleRequest(lote(
                registro("6", "INSERT", "CTR-1", "OPERACAO#a"),
                registro("7", "INSERT", "CTR-1", "OPERACAO#b"),
                registro("9", "INSERT", "CTR-1", "OPERACAO#c")), null);

        assertThat(resposta.getBatchItemFailures()).extracting(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("7");
    }

    private static DynamodbEvent lote(DynamodbStreamRecord... registros) {
        DynamodbEvent evento = new DynamodbEvent();
        evento.setRecords(List.of(registros));
        return evento;
    }

    private static DynamodbStreamRecord registro(String sequencia, String operacao, String contrato, String sk) {
        StreamRecord dados = new StreamRecord();
        dados.setSequenceNumber(sequencia);
        dados.setKeys(Map.of("id_contrato", new AttributeValue().withS(contrato), "sk", new AttributeValue().withS(sk)));
        DynamodbStreamRecord registro = new DynamodbStreamRecord();
        registro.setEventName(operacao);
        registro.setDynamodb(dados);
        return registro;
    }
}
