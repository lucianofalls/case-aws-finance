"""Analise ilustrativa C09: média TPS, pico e flag de eventos.

Usa somente as tarifas existentes em calcular_custos.py (snapshot de 2026-10-20).
NÃO estima TCO real ou garante que a capacidade dimensionada sustente o pico.
Execute: python3 docs/custos/sensibilidade.py
"""
import calcular_custos as c

# Valores marginais ilustrativos por evento para a rota opcional Kinesis→Pipes→EventBridge→SNS→SQS.
# SNS/SQS aproximados: 1 publish SNS, 1 send+receive+delete SQS por evento.
SNS_PUBLISH_POR_EVENTO = 0.50e-6
SQS_REQUESTS_POR_EVENTO = 3


def delta_ponte(total_eventos: int) -> dict[str, float]:
    return {
        "Pipes — requests (aproximação)": total_eventos * c.P["pipes_requisicao"],
        "EventBridge — publicação customizada": total_eventos * c.P["eventbridge_evento"],
        "SNS — publish (hipótese)": total_eventos * SNS_PUBLISH_POR_EVENTO,
        "SQS — 3 requests por entrega (hipótese)": total_eventos * SQS_REQUESTS_POR_EVENTO * c.P["sqs_padrao"],
    }


def estimar(tps_medio: int, publicar_transacao: bool):
    if not 0 < tps_medio <= 5_000:
        raise ValueError("TPS médio precisa ser positivo e não maior que o pico do estudo (5.000)")
    c.TPS_MEDIO = tps_medio
    c.AUTORIZACOES = tps_medio * c.SEGUNDOS_MES
    base = c.desenho_final()
    extras = delta_ponte(c.AUTORIZACOES) if publicar_transacao else {}
    return c.AUTORIZACOES, sum(base.values()), sum(extras.values())


if __name__ == "__main__":
    print("Premissas fixas do modelo do Claude, sa-east-1, ref. 2026-10-20.")
    print("Delta da ponte é aproximado e DESCONSIDERA DLQ, duplicidade, armazenamento, IAM e quotas.")
    print("Números NÃO representam fatura estimada completa nem confirmam 5.000 TPS.")
    print(f"{'Média TPS':>10} {'Aut./mês (M)':>16} {'Base US$/mês':>18} {'Delta ponte US$':>19} {'Total ilustr.':>18}")
    for tps in (100, 500, 1_000, 1_500, 3_000):
        n, base, delta = estimar(tps, True)
        print(f"{tps:10,d} {n/1_000_000:16,.0f} {base:18,.0f} {delta:19,.0f} {base+delta:18,.0f}")
