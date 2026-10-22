# ADR-041 — Recuperação de eventos após TTL e separação de evidências

**Data:** 2026-10-22  
**Status:** salvaguarda implementada, recuperação histórica **MANUAL**, automatização histórica pendente.  
**Pilares:** Reliability, Operational Excellence, Security.

## Problema

O `RepublicadorHandler` da conciliação diária consulta `autorizacoes` por `BatchGetItem`.
Após TTL de 35 dias, essa tabela pode não ter mais a autorização, embora o fluxo do
Kinesis/Firehose tenha encaminhado uma cópia imutável do CloudEvent ao S3 (WORM).
Antes desta ADR, a ausência poderia terminar como mera contagem `naoEncontrados` e a
Step Function encerrar a execução como sucesso.

## Decisão

1. Se alguma autorização ausente não puder ser republicada do DynamoDB, o
   `Republicador` **falha explicitamente**; a conciliação não é marcada como concluída.
   Esse estado exige triagem; não gera evento novo inventado.
2. Disponibilizar o utilitário somente manual `scripts/recuperar-evento-trilha.py`,
   com **dry-run por padrão**, arquivo de trilha previamente obtido, CloudEvent
   exatamente identificado por `--evento-id`, `source`, `time` e `data.id_contrato`.
   A publicação necessita de `--enviar`, stream e endpoint explícitos, e preserva
   `event_id` e a chave de particionamento do fato original.
3. Consumidores precisam manter Inbox idempotente; a republicação ao Kinesis é
   **at-least-once**, jamais exactly-once. Operadores devem registrar trilha de replay,
   justificativa e identificadores, limitando o acesso de publicação.
4. A trilha S3 deve ser verificada quanto a **completude real** antes de depender dela
   como fonte de recuperação. Se o evento não chegou ao Kinesis/Firehose inicialmente,
   o S3 pode não contê-lo. Portanto este utilitário não promete resolver todo outbox parado.

## Alternativa não adotada

Não desenvolver nesta fase um reconciliador que faça, automaticamente, consultas e
publicações a S3/Athena após TTL. A solução exige índice por event_id, tolerância a
arquivos não indexados, política de segurança da trilha WORM, DLQ, custo e homologação.

## Prova operacional pendente

- Falhar com id expirado pelo TTL e gerar alerta.
- Localizar o arquivo WORM correto, conferir checksums, assinatura e evento.
- Fazer `dry-run`, depois autorização explícita para `--enviar` e confirmar Inbox.
- Comprovar que o replay não gera lançamentos contábeis duplicados.
- Tratar também caso **evento nunca publicado no Kinesis**: reconstrução deve vir de
  registro financeiro preservado independentemente do caminho de eventos.

Nenhum comando desse documento autoriza execução de AWS real.
