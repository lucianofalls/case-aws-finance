# Runbook — evento financeiro após TTL de 35 dias

**Não automatizado. Não usar em clientes reais sem trilha de aprovação e auditoria.**

## Gatilho

A conciliação/republicador falha por `autorização ausente no DynamoDB` e reporta
faltas em logs/execução da Step Functions. O job **não fecha como sucesso**.

## Procedimento controlado

1. Identificar `id_autorizacao` e o **event_id original** no resultado Athena e na
   trilha imutável WORM. Verificar se o evento realmente foi entregue à trilha.
2. Revisar com Operações/Risco que o evento correto é único e os dados não foram
   alterados; manter hash/checksum do objeto original e o change/ticket de autorização.
3. Baixar, via processo autorizado, a faixa correspondente da trilha S3 para arquivo
   local NDJSON ou NDJSON comprimido **sem editar as mensagens**.
4. Executar somente a inspeção, no ambiente **LocalStack**, por exemplo:

   ```bash
   python3 scripts/recuperar-evento-trilha.py \
     --arquivo ./trilha-eventos.jsonl.gz \
     --evento-id UUID_ORIGINAL \
     --stream transacoes --endpoint-url http://localhost:4566
   ```
5. Somente após validação dos identificadores, código e autorização, acrescentar
   `--enviar` para executar um único `PutRecord` **at-least-once**. Verificar a Inbox
   e a auditoria contábil; uma republicação legítima não deve duplicar lançamentos.
6. Se o evento não existir na trilha (por falha anterior ao Kinesis/Firehose),
   **não inventar payload**. Investigar item financeiro histórico e reconciliar fora
   do fluxo automático. Escalar incidente.

## Escopo

O utilitário não consulta S3, não tem credenciais embutidas e não envia eventos
sem `--enviar`; aceitar endpoint AWS real exigiria credenciais separadas e aprovação.
Rejeita zero ou múltiplos eventos com mesmo ID no arquivo.

## Limitação

A exportação do DynamoDB e a trilha de eventos são fontes diferentes. Sem processo
automatizado de reconstrução, a lacuna de >35 dias não está completamente resolvida.
