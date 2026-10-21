"""Modelo de custo mensal reproduzível (US$, preços de lista on-demand, sa-east-1).

Fonte dos preços: AWS Price List API (https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/<serviço>/current/
sa-east-1/index.json), consultada em 2026-10-20. Sem descontos (EDP, Savings Plans, reserved capacity).
Premissas declaradas abaixo — mude e rode de novo: python docs/custos/calcular_custos.py
"""

SEGUNDOS_MES = 730 * 3600
HORAS_MES = 730

# ---- Premissas de volume (o PDF só define o pico) --------------------------------------------------------
TPS_MEDIO = 1_500                       # média assumida; pico do PDF = 5.000
AUTORIZACOES = TPS_MEDIO * SEGUNDOS_MES  # ≈ 3,94 bi/mês
UTILIZACAO_ALVO_DYNAMO = 0.70           # auto scaling target tracking

# ---- Preços sa-east-1 ------------------------------------------------------------------------------------
P = {
    "apigw_rest_ate_1bi": 4.25e-6, "apigw_rest_1_a_20bi": 3.61e-6,
    "waf_requisicao": 0.60e-6, "waf_acl": 5.0, "waf_regra": 1.0,
    "ddb_wru": 0.9375e-6, "ddb_rru": 0.1875e-6,
    "ddb_wcu_hora": 0.000975, "ddb_rcu_hora": 0.000195, "ddb_gb_mes": 0.375,
    "eventbridge_evento": 1.00e-6, "pipes_requisicao": 0.69e-6,
    "kinesis_shard_hora": 0.030, "kinesis_shard_estendido_hora": 0.040, "kinesis_put_unidade": 0.028e-6,
    "kinesis_efo_consumidor_shard_hora": 0.03,
    "sqs_padrao": 0.40e-6, "sqs_fifo": 0.50e-6,
    "firehose_gb": 0.058,
    "lambda_req": 0.20e-6, "lambda_gbs_arm": 0.0000133334,
    "fargate_vcpu_arm_hora": 0.0557, "fargate_gb_arm_hora": 0.00612,
    "aurora_r7g_2xl_iopt_hora": 2.28483, "aurora_iopt_gb_mes": 0.428, "aurora_io": 0.28e-6,
}


def api_gateway():
    primeira = min(AUTORIZACOES, 1e9) * P["apigw_rest_ate_1bi"]
    return primeira + max(0, AUTORIZACOES - 1e9) * P["apigw_rest_1_a_20bi"]


def waf():
    return AUTORIZACOES * P["waf_requisicao"] + P["waf_acl"] + 3 * P["waf_regra"]


def fargate(tasks, vcpu=1, gb=2):
    return tasks * (vcpu * P["fargate_vcpu_arm_hora"] + gb * P["fargate_gb_arm_hora"]) * HORAS_MES


def dynamo_on_demand(wru, rru):
    return AUTORIZACOES * (wru * P["ddb_wru"] + rru * P["ddb_rru"])


def dynamo_provisionado(wru, rru):
    wcu = TPS_MEDIO * wru / UTILIZACAO_ALVO_DYNAMO
    rcu = TPS_MEDIO * rru / UTILIZACAO_ALVO_DYNAMO
    return (wcu * P["ddb_wcu_hora"] + rcu * P["ddb_rcu_hora"]) * HORAS_MES


def lambda_lote(registros, tamanho_lote, segundos, gb):
    invocacoes = registros / tamanho_lote
    return invocacoes * P["lambda_req"] + invocacoes * segundos * gb * P["lambda_gbs_arm"]


def desenho_inicial():
    """v1 (antes da revisão de custo): PENDENTE + item de idempotência + GSI + outbox em toda transação,
    Pipes, 2 eventos/autorização no EventBridge com fan-out SQS/SNS, sem TTL, Lambda na contabilização."""
    eventos = 2 * AUTORIZACOES
    return {
        "API Gateway REST": api_gateway(),
        "WAF": waf(),
        "DynamoDB (18 WRU + 2 RRU, on-demand)": dynamo_on_demand(18, 2),
        "DynamoDB armazenamento (sem TTL, cresce todo mês)": AUTORIZACOES * 1.5e-6 * P["ddb_gb_mes"],
        "EventBridge Pipes (2 eventos/aut.)": eventos * P["pipes_requisicao"],
        "EventBridge eventos (2/aut.)": eventos * P["eventbridge_evento"],
        "SQS FIFO + padrão (fan-out, ~1,2 req/msg)": AUTORIZACOES * 1.2 * (P["sqs_fifo"] + P["sqs_padrao"]),
        "Firehose (registro arredondado a 5 KB)": eventos * 5e-6 * P["firehose_gb"],
        "Fargate síncrono (18 tasks)": fargate(18),
        "Lambda contabilização + RDS Proxy (estim.)": lambda_lote(AUTORIZACOES, 10, 0.2, 1.0) + 300,
        "Aurora (2× r7g.2xlarge, I/O avulso)": 2 * 1.75854 * HORAS_MES + AUTORIZACOES * 8 * P["aurora_io"],
    }


def desenho_final():
    """v2 (implementado): id determinístico (sem PENDENTE/idempotência/GSI), outbox embutido, relay Lambda,
    Kinesis para o fluxo transacional, EventBridge só para integração, TTL 35 d, PROVISIONED + auto scaling."""
    shards = 8
    return {
        "API Gateway REST": api_gateway(),
        "WAF": waf(),
        "DynamoDB (5 WRU + 3 RRU, provisionado 70%)": dynamo_provisionado(5, 3),
        "DynamoDB armazenamento (TTL 35 d, estável)": AUTORIZACOES * (35 / 30) * 1.05e-6 * P["ddb_gb_mes"],
        "Lambda relay do outbox (lote ~100)": lambda_lote(AUTORIZACOES, 100, 0.2, 1.0),
        "Kinesis (8 shards, retenção 7 d, PUT, EFO p/ KCL)": shards * HORAS_MES * (
            P["kinesis_shard_hora"] + P["kinesis_shard_estendido_hora"] + P["kinesis_efo_consumidor_shard_hora"])
            + AUTORIZACOES * P["kinesis_put_unidade"],
        "Firehose Kinesis → S3 (trilha)": AUTORIZACOES * 1e-6 * P["firehose_gb"],
        "EventBridge (integração, ~30 mi/mês)": 30e6 * P["eventbridge_evento"],
        "SQS (verificação incerta + limites concedidos)": 40e6 * 3 * P["sqs_padrao"],
        "Lambdas stub (antifraude/notificações, filtradas)": 2 * lambda_lote(AUTORIZACOES, 500, 0.3, 0.5),
        "Fargate (18 síncronas + 3 KCL, 2/3 Spot)": fargate(18) + fargate(1) + fargate(2) * 0.3,
        "Aurora I/O-Optimized (2× r7g.2xlarge + 90 d de razão)": 2 * P["aurora_r7g_2xl_iopt_hora"] * HORAS_MES
            + AUTORIZACOES * 3 * 0.4e-6 * P["aurora_iopt_gb_mes"],
    }


def imprimir(titulo, itens):
    print(f"\n{titulo}")
    print("-" * 78)
    for nome, valor in sorted(itens.items(), key=lambda item: -item[1]):
        print(f"  {nome:<60} US$ {valor:>10,.0f}")
    total = sum(itens.values())
    print(f"  {'TOTAL':<60} US$ {total:>10,.0f}")
    return total


if __name__ == "__main__":
    print(f"Premissa: {TPS_MEDIO} TPS médios ⇒ {AUTORIZACOES / 1e9:.2f} bi autorizações/mês (pico 5.000 TPS)")
    inicial = imprimir("Desenho inicial (v1)", desenho_inicial())
    final = imprimir("Desenho implementado (v2)", desenho_final())
    print(f"\nEconomia: US$ {inicial - final:,.0f}/mês ({(1 - final / inicial):.0%})")
    print(f"Custo por milhão de autorizações (v2): US$ {final / (AUTORIZACOES / 1e6):,.2f}")
