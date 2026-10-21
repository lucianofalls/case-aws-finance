package br.com.itau.financas.autorizacao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Configuração externa (variáveis de ambiente na task ECS, definidas pelo Terraform). */
@ConfigurationProperties(prefix = "autorizacao")
public record AutorizacaoProperties(Aws aws, Limites limites, Reserva reserva) {

    public record Aws(String regiao, String endpoint, String tabelaAutorizacoes, Duration retencao) {

        /** Endpoint alternativo (LocalStack); vazio na AWS. */
        public boolean temEndpointAlternativo() {
            return endpoint != null && !endpoint.isBlank();
        }
    }

    public record Limites(URI url, Duration timeoutConexao, Duration timeoutLeitura, int tentativas,
                          Duration esperaInicial, float limiarFalhasPercentual, Duration esperaCircuitoAberto) {
    }

    /** Validade da RESERVA (pré-autorização) e fila em que o EventBridge Scheduler entrega a expiração (ADR 035). */
    public record Reserva(Duration validade, String filaExpiracao) {
    }
}
