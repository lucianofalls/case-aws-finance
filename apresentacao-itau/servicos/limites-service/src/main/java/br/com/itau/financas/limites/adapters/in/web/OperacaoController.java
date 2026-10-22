package br.com.itau.financas.limites.adapters.in.web;

import br.com.itau.financas.limites.application.AplicarOperacaoService;
import br.com.itau.financas.limites.application.ReverterOperacaoOrfaService;
import br.com.itau.financas.limites.application.port.out.PosicaoRepository;
import br.com.itau.financas.limites.domain.Excecoes.ContratoSemLimiteException;
import br.com.itau.financas.limites.domain.Identificadores.ContratoId;
import br.com.itau.financas.limites.domain.Operacao;
import br.com.itau.financas.limites.domain.Posicao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API interna (leste-oeste via ECS Service Connect; não exposta no API Gateway).
 * Contrato: contracts/openapi/limites-interno-v1.yaml.
 */
@RestController
public class OperacaoController {

    private final AplicarOperacaoService aplicarOperacao;
    private final ReverterOperacaoOrfaService reverterOperacao;
    private final PosicaoRepository posicoes;

    public OperacaoController(AplicarOperacaoService aplicarOperacao, ReverterOperacaoOrfaService reverterOperacao,
                              PosicaoRepository posicoes) {
        this.aplicarOperacao = aplicarOperacao;
        this.reverterOperacao = reverterOperacao;
        this.posicoes = posicoes;
    }

    @PostMapping("/v1/contratos/{idContrato}/operacoes")
    public ResponseEntity<PosicaoResponse> aplicar(@PathVariable String idContrato,
                                                   @Valid @RequestBody OperacaoRequest corpo) {
        AplicarOperacaoService.Resultado resultado = aplicarOperacao.aplicar(new AplicarOperacaoService.Comando(
                idContrato, corpo.idOperacao(), corpo.tipoOperacao(), corpo.valor(), corpo.moeda(),
                corpo.impressaoDigital(), corpo.idOperacaoOriginal(), corpo.idConta()));
        HttpStatus status = resultado.criadaAgora() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(PosicaoResponse.de(resultado.operacao()));
    }

    @PostMapping("/v1/contratos/{idContrato}/operacoes/{idOperacao}/reversao")
    public ReversaoResponse reverter(@PathVariable String idContrato, @PathVariable String idOperacao) {
        return new ReversaoResponse(reverterOperacao.reverter(idContrato, idOperacao).name());
    }

    @GetMapping("/v1/contratos/{idContrato}/posicao")
    public PosicaoResponse consultar(@PathVariable String idContrato) {
        ContratoId contrato = new ContratoId(idContrato);
        return PosicaoResponse.de(posicoes.buscarPosicao(contrato)
                .orElseThrow(() -> new ContratoSemLimiteException(contrato)));
    }

    public record OperacaoRequest(
            @NotBlank String idOperacao,
            @NotBlank @Pattern(regexp = "DEBITO|CREDITO|RESERVA|CAPTURA|LIBERACAO|ESTORNO") String tipoOperacao,
            @NotBlank @Pattern(regexp = "\\d{1,16}(\\.\\d{1,2})?") String valor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String moeda,
            @NotBlank @Size(max = 128) String impressaoDigital,
            String idOperacaoOriginal, @NotBlank String idConta) {
    }

    /** {@code saldo_reservado} = comprometido (utilizado + bloqueado); {@code saldo_bloqueado} = reservas pendentes. */
    public record PosicaoResponse(String saldoReservado, String limiteDisponivel, String limiteConcedido,
                                  String saldoBloqueado) {

        static PosicaoResponse de(Operacao operacao) {
            return new PosicaoResponse(operacao.saldoReservadoApos().comoTexto(),
                    operacao.disponivelApos().comoTexto(), null, null);
        }

        static PosicaoResponse de(Posicao posicao) {
            return new PosicaoResponse(posicao.comprometido().comoTexto(), posicao.disponivel().comoTexto(),
                    posicao.limiteConcedido().comoTexto(), posicao.bloqueado().comoTexto());
        }
    }

    public record ReversaoResponse(String desfecho) {
    }
}
