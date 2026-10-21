# Runbook — outbox parado (relay ou agendador sem consumir o DynamoDB Streams)

**Alarmes que disparam:** `outbox-atrasado-<função>` (idade do iterador > 1 min por 3 min) para `relay-autorizacoes`,
`relay-limites` ou `agendador-verificacao`. Se a correção demorar, depois também `conciliacao-fatosrepublicados`.

**Por que importa:** o DynamoDB Streams guarda os registros por **24 horas**. Dentro desse prazo, corrigir a causa
basta: o event source mapping retoma de onde parou e nada se perde. Depois de 24 h, os registros mais antigos
saem do stream, e a recuperação passa a ser pela conciliação (ADR 034).

## 1. Até 24 horas: corrigir e deixar o stream drenar

1. Ver o erro da função: CloudWatch Logs `/aws/lambda/<função>` e a fila `outbox-relay-falhas` (destino de falha
   do event source mapping).
2. Causas comuns:
   - destino com throttling (Kinesis `WriteProvisionedThroughputExceeded`, EventBridge `PutEvents`) → aumentar
     shards ou quota;
   - permissão IAM ou KMS → corrigir a política;
   - bug em deploy recente → reverter a versão (alias `atual`).
3. Conferir a idade do iterador caindo até zero. **Fim**: nenhum evento foi perdido.

## 2. Mais de 24 horas: republicar pela conciliação

Os fatos continuam gravados nas tabelas (TTL de 35 dias) e cobertos pelo PITR (35 dias). A conciliação exporta a
janela do incidente, encontra o que não chegou à trilha ou ao razão e republica com o **mesmo `id`** de evento.
Os consumidores deduplicam pelo `id`.

1. Corrigir a causa (passo 1). Sem isso, a republicação cai no mesmo erro.
2. Para cada intervalo de **até 24 h** do incidente (limite da exportação incremental), iniciar a máquina de estados:

   ```bash
   aws stepfunctions start-execution \
     --state-machine-arn "$(terraform -chdir=infra/terraform/ambientes/prod output -raw maquina_conciliacao_arn)" \
     --name "incidente-$(date -u +%Y%m%dT%H%M%S)" \
     --input '{"desde":"2026-10-19T08:00:00Z","ate":"2026-10-20T08:00:00Z"}'
   ```

3. Acompanhar no console do Step Functions: `ExportarAutorizacoes` / `ExportarLimites` (minutos) → consultas Athena
   → `Republicar` e `AgendarVerificacaoDasOrfas`.
4. Conferir:
   - a saída de `Republicar` mostra `republicados` e `nao_encontrados`. `nao_encontrados` > 0 significa item
     expirado pelo TTL (> 35 dias): escalar para a recuperação a partir do PITR;
   - a métrica `Financas/Conciliacao/FatosRepublicados` mostra o mesmo número;
   - executar de novo a mesma janela deve republicar **zero** (prova de que a lacuna fechou).

## 3. O que não fazer

- Não reprocessar o stream manualmente nem "reiniciar do TRIM_HORIZON" com outro mapeamento: duplica o tráfego sem
  cobrir o que já saiu do stream.
- Não apagar mensagens de `outbox-relay-falhas` antes da conciliação: elas são evidência do incidente.
