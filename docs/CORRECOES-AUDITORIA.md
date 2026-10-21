# Plano de correções — auditoria técnica de 21/10/2026

**Objeto:** `itau-pod99-solucao.zip` (entrega do Claude Code para o desafio Itaú POD99).
**Parecer da auditoria:** aprovar a continuidade com ressalvas; não declarar conformidade integral ainda.
**Este documento:** registra, para **cada** achado, o que foi verificado no código, a correção, os arquivos, os
testes e o critério de aceite. Nenhuma correção deste plano foi executada ainda (status inicial: *a fazer*).

Legenda de esforço: **P** até meio dia · **M** 1 dia · **G** 2 dias ou mais.

---

## 0. Resumo

| ID | Achado | Prioridade | Veredito após verificação | Esforço | Status |
|---|---|---|---|---|---|
| C01 | Compensação de operação órfã sem garantia (SQS best-effort; "reconciliação diária" inexistente) | **P0** | Procede | M | a fazer |
| C02 | `PutRecords` e o relay paralelo não garantem ordem por contrato | **P0** | Procede; correção é retirar a promessa e provar a independência de ordem | P | a fazer |
| C03 | Outbox da Autorização sem recuperação acima de 24 h (retenção do DynamoDB Streams) | P1 | Procede → **D1**: exportação incremental diária + conciliação + republicador | G | a fazer |
| C04 | Divergência da arquitetura de referência e do exemplo de EventBridge | P1 | Procede em parte → **D3**: ADR comparativa + caminho derivado via Pipes | M | a fazer |
| C05 | RESERVA e DÉBITO com o mesmo efeito; sem ciclo de vida da reserva | P1 | Procede em parte → **D2**: implementar `AmountBlock` (BIAN) | G | a fazer |
| C06 | Quarentena da Contabilização avança o checkpoint; falha da quarentena perde o fato | P1 | **Procede, e é mais grave do que o relatado** | M | a fazer |
| C07 | Falta a role de CloudWatch do API Gateway (`aws_api_gateway_account`) | P2 | **Procede; é bug de `apply` em produção** | P | a fazer |
| C08 | Ambiente local não demonstrado; LocalStack 4.0 legado | P2 | Procede | P (código) + execução na máquina de teste | a fazer |
| C09 | Modelo FinOps ilustrativo, sem sensibilidade | P2 | Procede | P | a fazer |
| C10 | Diagramas sem ícones AWS; faltam deployment, compensação e estados | P3 | Procede (melhoria) | M | a fazer |
| C11 | Roteiro de aceitação executável (seção 6 da auditoria) | — | Procede; o `demo.sh` não cobre estorno nem falhas induzidas | M | a fazer |
| C12 | Documentação que promete o que não existe | — | Achado transversal (C01, C02, C03) | P | a fazer |

**Ordem de execução:** C07 → C02 → C12 → C06 → C01 → C03 → C05 → C04 → C11 → C09 → C08 → C10.
Começa pelo que é rápido e já elimina afirmações falsas; depois a confiabilidade (C06, C01, C03); por fim, a
demonstração e a apresentação.

**Decisões D1–D3 — tomadas em 21/10/2026.** O critério definido pelo responsável foi **o que a AWS recomenda e o
padrão do mercado financeiro (BIAN, ISO 20022)**, e não o menor custo. Todas as afirmações abaixo foram conferidas
na documentação oficial (fontes no fim desta seção).

| | Decisão | Em uma frase |
|---|---|---|
| **D1** | **DynamoDB Streams no caminho normal + exportação incremental diária para o S3 + conciliação independente (BIAN Account Reconciliation) + republicador por janela** | o transporte rápido continua o que a AWS indica para gatilho de Lambda; a recuperação e o controle passam a ter uma fonte durável e independente do transporte |
| **D2** | **Implementar o bloqueio de valor (BIAN `AmountBlock`) com ciclo reserva → captura/liberação/expiração** | é como o BIAN e a indústria de cartões modelam a pré-autorização |
| **D3** | **Manter o Kinesis no `TransacaoAutorizada` e implementar o caminho derivado Kinesis → EventBridge Pipes (filtrado) → barramento** para os fatos de baixo volume entre domínios | cada serviço faz o papel que a AWS indica para ele, e o EventBridge passa a receber, de fato, fatos do domínio de transações |

### D1 — Por que não o Kinesis Data Streams for DynamoDB (e por que mudei a recomendação)

A tabela oficial da AWS ("Streaming options for change data capture") compara os dois modelos:

| Propriedade | DynamoDB Streams | Kinesis Data Streams for DynamoDB |
|---|---|---|
| Retenção | 24 horas | até 1 ano |
| Ordem | exata, por item | **não garantida** |
| Duplicatas | **nenhuma** | podem aparecer |
| Consumidores | 2 por shard | 5 por shard (20 com EFO) |
| Custo em sa-east-1 | leitura pela Lambda gratuita | US$ 0,15 por milhão de unidades de captura + shards |

1. **O relay depende de ordem por item e de ausência de duplicata.** Ele compara o `evento_id` novo com o antigo
   do mesmo item. É exatamente o que o DynamoDB Streams garante e o KDS não. Com o KDS, o relay precisaria de uma
   deduplicação extra.
2. **Retenção longa no transporte não resolve o que a auditoria pediu.** O ponto da auditoria é ter uma **fonte
   independente** para recuperar e conferir. O KDS ainda é o mesmo caminho de propagação: se a lógica do relay
   tiver um bug, a retenção longa só guarda o erro por mais tempo.
3. **No mercado financeiro, a garantia final é a conciliação.** A conciliação diária é um controle obrigatório
   em banco: confronta o sistema transacional, as posições e o razão por uma fonte que não é o transporte. O BIAN
   tem um Service Domain próprio para isso, o **Account Reconciliation**, que também trata a conciliação de contas
   ("This Service Domain handles account reconciliation tasks", v12).
4. **A AWS indica a exportação para o S3 para isso.** É assíncrona, **não consome capacidade da tabela**, aceita
   **janela incremental** e filtro, e foi feita para Athena/Glue. A mesma exportação serve à conciliação **e** à
   republicação de qualquer janela, mesmo depois de 24 h.
5. **Custo:** a exportação incremental diária das duas tabelas sai por ~US$ 1,2 mil/mês (US$ 0,15/GB). O KDS sairia
   por ~US$ 3,5 mil/mês **sem** substituir a conciliação, que continuaria necessária.

**Mudança de recomendação.** Eu tinha recomendado "republicador a partir de exportação PITR" pensando só em custo.
Pelo novo critério, a decisão fica mais forte: a exportação vira **diária e incremental**, alimenta uma
**conciliação** de verdade e a republicação passa a ser automática quando a conciliação encontra uma lacuna.

### D2 — Por que implementar o bloqueio de valor

1. **O BIAN modela isso explicitamente.** No Position Keeping v12, a posição tem `AmountBlock`, com **tipo do
   bloqueio** ("pending, final", ISO 20022 `TypeOfAmountCode`), **prioridade** e **datas-chave** ("start, expiry").
   No Current Account, o equivalente é `AccountLien`.
2. **A indústria de cartões funciona assim.** A pré-autorização retém o valor; depois vem a **captura** (pode ser
   parcial, e o restante é liberado) ou o **cancelamento**; sem captura, o bloqueio **expira**. Na família de
   mensagens ISO 20022 de autorização de cartão (`caaa`), a autorização, a conclusão (completion) e o
   cancelamento/estorno são mensagens distintas.
3. **O contrato do PDF não quebra.** `tipo_operacao = RESERVA` continua criando a reserva. Captura e liberação
   entram como recursos **aditivos** (`/autorizacoes/{id}/capturas` e `/liberacoes`), sem mudar o `/v1`.
4. **AWS para a expiração:** um agendamento único do **EventBridge Scheduler** por reserva, com exclusão
   automática após a execução. Em sa-east-1 a quota padrão é de 10 milhões de agendamentos e 5.000 criações por
   segundo, ambas ajustáveis. Não há varredura nem índice extra.

### D3 — Por que manter o Kinesis e implementar o caminho derivado

1. **Orientação da AWS para escolher o serviço:** o EventBridge é para **roteamento e integração entre aplicações e
   contas**; o Kinesis Data Streams é para **streaming de alto volume** com replay por consumidor. O
   `TransacaoAutorizada`, a até 5.000/s com quatro consumidores, é o segundo caso (custo e fan-out na §6.1 da visão).
2. **O mercado financeiro faz o mesmo:** um **log de eventos** (Kafka ou Kinesis) como espinha dorsal transacional,
   e um **barramento/roteador** para integração entre domínios e times.
3. **O caminho derivado atende o exemplo do PDF onde faz sentido.** O EventBridge Pipes tem o Kinesis como origem,
   com filtro, e o barramento EventBridge como destino. Os fatos de baixo volume e interesse entre domínios
   (`EstornoRealizado`, `ReservaLiberada`, `ReservaExpirada`) passam a ser publicados **também** no barramento
   `financas`, roteáveis por regra e com archive, sem pagar o EventBridge no alto volume.
4. **Plano B documentado:** se a governança exigir todo fato no barramento central, o mesmo Pipe passa a incluir o
   `TransacaoAutorizada`, com aumento de quota e o custo da §6.1. Os consumidores não mudam, porque o CloudEvent é o
   mesmo.

### Fontes oficiais consultadas

- AWS — DynamoDB, "Streaming options for change data capture": `docs.aws.amazon.com/amazondynamodb/latest/developerguide/streamsmain.html`
- AWS — DynamoDB, exportação para S3 (full, incremental, filtrada, sem RCU): `.../S3DataExport.HowItWorks.html`
- AWS — Kinesis `PutRecords` (ordem não garantida): `docs.aws.amazon.com/kinesis/latest/APIReference/API_PutRecords.html`
- AWS — EventBridge Scheduler, quotas: `docs.aws.amazon.com/scheduler/latest/UserGuide/scheduler-quotas.html`
- AWS — Price List API, `AmazonDynamoDB`, sa-east-1: `SAE1-ChangeDataCaptureUnits-Kinesis` US$ 0,15/milhão; `SAE1-IncrementalExportDataSize-Bytes` US$ 0,15/GB
- BIAN — repositório oficial `github.com/bian-official/public`, release 12.0.0: `PositionKeeping.yaml` (`AmountBlock`, `AmountBlockType`, datas de expiração), `CurrentAccount.yaml` (`AccountLien`), `AccountReconciliation.yaml`

---

## C01 — Compensação de operação órfã sem garantia (P0)

**Verificado.** `servicos/autorizacao-service/.../adapters/out/sqs/SqsVerificacaoPosteriorAdapter.java:38-41`
captura a falha do `sendMessage` e só registra no log. O comentário e a ADR 027 citam uma "reconciliação diária
(trilha S3 × operações)" que **não existe** em código nem em Terraform. Há três caminhos sem cobertura:

| Cenário | Hoje |
|---|---|
| Limites aplicou o débito; a resposta se perdeu; o SQS falha ao agendar | limite preso para sempre |
| Limites aplicou; a task da Autorização cai antes de agendar | limite preso para sempre |
| Limites aplicou; a Autorização não consegue gravar a decisão | depende do mesmo agendamento best-effort |

**Correção: detectar a órfã pelo lado do Limites, que é a fonte independente.** Toda operação gravada no Limites
agenda a própria verificação, de forma durável, sem depender da task que fez a chamada.

```
limites-service ──TransactWrite──▶ DynamoDB limites (OPERACAO#… novo)
                                        │ Streams (já existe), filtro: INSERT de OPERACAO#
                                        ▼
                              Lambda agendador-de-verificacao ──SendMessageBatch──▶ SQS verificacao (atraso 15 min)
                                                                                         │
                                                                                         ▼
                                                     reconciliador (já existe): decisão gravada? sim → nada
                                                                                 não → reverter no Limites
```

- O agendamento passa a ser **at-least-once**, com as retentativas do event source mapping e a DLQ, e não depende
  da Autorização.
- `SqsVerificacaoPosteriorAdapter` e o port `VerificacaoPosteriorPort` **saem** da Autorização. Um único caminho
  evita mensagens em dobro; o reconciliador já é idempotente.
- As janelas disjuntas continuam valendo: repetição aceita por 10 min, verificação aos 15 min.
- O reconciliador passa a usar `BatchGetItem` (até 100 chaves) e lotes SQS maiores com janela de agrupamento.

**Arquivos.**
- `infra/terraform/modulos/funcoes/main.tf`: nova função `agendador-verificacao`; o event source mapping da
  tabela `limites` com filtro `INSERT` + `sk` com prefixo `OPERACAO#` (o filtro atual, de `OUT#`, continua no
  relay).
- `funcoes/reconciliador`: novo handler `AgendadorDeVerificacaoHandler`; `DynamoDbConsultaDeAutorizacoes` com
  `BatchGetItem`.
- `servicos/autorizacao-service`: remover `SqsVerificacaoPosteriorAdapter`, `VerificacaoPosteriorPort`, a fila
  na configuração e as chamadas em `AutorizarTransacaoService` (linhas 76-95).
- `local/docker-compose.yml`: retirar `FILA_VERIFICACAO_URL` da Autorização.

**Custo.** ~**US$ 1,1 mil/mês** (+3% sobre os ~US$ 35 mil): SQS em lote de 10 (~US$ 0,6 mil), Lambdas (~US$
0,2 mil) e uma leitura por operação na verificação (~US$ 0,3 mil). Atualizar `docs/custos/calcular_custos.py`.

**Testes.**
- Unitário: o agendador extrai `id_operacao`/`id_contrato` dos registros e ignora o que não é `OPERACAO#`.
- Unitário: com falha parcial no `SendMessageBatch`, devolve `batchItemFailures` só dos registros afetados.
- Integração (`-Pintegracao`): operação sem decisão → revertida; operação com decisão → intocada.
- Demo local (C11): Limites lento induzido → 503 → operação revertida pelo reconciliador.

**Aceite.** Nenhum caminho deixa limite preso por mais de 15 min + tempo de processamento, inclusive com a task
da Autorização derrubada no meio da chamada.

**Documentação.** Nova ADR 032 (substitui o agendamento da ADR 027); corrigir ADR 027, `visao-da-solucao.md`
§2.3 e §6.3, `roteiro-apresentacao.md` e o diagrama do passo 12.

---

## C02 — Ordem por contrato não é garantida (P0)

**Verificado.**
- A AWS documenta em `API_PutRecords`: *"PutRecords doesn't guarantee the ordering of records"*.
- **Agravante que a auditoria não citou:** o relay usa `parallelization_factor = 10`
  (`infra/terraform/modulos/funcoes/main.tf:175`), que preserva a ordem **por item** do DynamoDB. Duas
  autorizações do mesmo contrato são itens diferentes e podem ser publicadas fora de ordem.
- **Mas o PDF (§2.4) exige que os consumidores tolerem reordenação**, e a Contabilização do estorno usa o próprio
  payload, sem depender do original (`PlanoDeContas.java:40`). Ordem por contrato **não é requisito**.

**Correção: retirar a promessa e provar que os consumidores não dependem de ordem.** Não construir ordenação:
isso exigiria `PutRecord` serial por shard e sem paralelismo, e custaria throughput sem atender requisito nenhum.

- Texto correto: "o Kinesis particiona por `id_contrato` (distribuição e afinidade); **a ordem não é garantida**;
  os consumidores são independentes de ordem e deduplicam pelo `id`".
- O relay mantém `PutRecords` em lote e o `parallelization_factor`. A justificativa passa a ser vazão, não ordem.

**Locais com a promessa errada (10):** `KinesisDestino.java:16-17`, `modulos/funcoes/main.tf:175`,
`modulos/eventos/main.tf:13`, `contracts/asyncapi/financas-eventos.yaml:11`, `docs/adr/README.md:70` (ADR 028),
`docs/eventos.md:8,19,22`, `docs/visao-da-solucao.md:396` (linha "ordem por contrato" da tabela §6.1),
`README.md:22`, `TransacoesRecordProcessor.java:20`, `diagramas/gerar_fluxo_passo_a_passo.py` (rótulo "chave
id_contrato" está correto; revisar o texto do passo 8).

**Testes (Contabilização).**
- `EstornoRealizado` processado **antes** do `TransacaoAutorizada` original → dois registros contábeis corretos e
  balanceados.
- O mesmo evento entregue duas vezes → um único registro (inbox).
- Eventos de contratos diferentes intercalados → resultado independente da ordem.

**Aceite.** Nenhum documento afirma ordem garantida; os três testes passam.

---

## C03 — Outbox sem recuperação acima de 24 h (P1)

**Verificado.** O relay lê só o DynamoDB Streams, que retém registros por 24 h. Se o relay parar por mais
tempo, decisões gravadas nunca chegam ao Kinesis, à Contabilização nem à trilha S3, porque a trilha é alimentada
pelos próprios eventos. O alarme de `IteratorAge` existe (`modulos/observabilidade/main.tf:59`), mas avisar não
recupera.

**Correção (D1).**

| Opção | Como | Custo fixo | Recomendação |
|---|---|---|---|
| **A. Republicador a partir de exportação PITR** | `ExportTableToPointInTime` da tabela `autorizacoes` para o S3 (o PITR já está ligado; a exportação não consome capacidade da tabela) → job lê a exportação, filtra `evento_ocorrido_em` na janela do incidente e republica pelo mesmo `ExtratorDeAutorizacoes`. Os consumidores deduplicam pelo `id` | zero (paga só no incidente) | **recomendada** |
| B. Kinesis Data Streams for DynamoDB | a tabela publica as mudanças direto num stream Kinesis com retenção configurável (até 365 dias); o relay passa a ler esse stream | shards + unidades de captura de mudança | se a operação exigir recuperação sem intervenção |

Pela documentação da AWS, a opção B também pode entregar fora de ordem e em duplicata. Com o C02 resolvido, os
consumidores já toleram as duas coisas.

**Arquivos (opção A).**
- `funcoes/outbox-relay`: novo `RepublicadorDeExportacao`, que lê arquivos `DYNAMODB_JSON` do S3 e reutiliza
  `ExtratorDeAutorizacoes`/`KinesisDestino`; o mesmo vale para a tabela `limites` (`OUT#`) → EventBridge.
- `infra/terraform/modulos/funcoes`: função de execução manual (sem gatilho) e bucket de exportação com política
  mínima.
- `docs/runbooks/outbox-parado.md`: alarme → exportação PITR → republicação → conferência.

**Testes.** O republicador, com uma exportação de exemplo (fixture), publica só os itens da janela e com o mesmo
`id` do evento original.

**Aceite.** Runbook executável de ponta a ponta. Simulação: relay desligado, autorizações feitas, republicação a
partir da exportação, consumidores sem duplicar.

---

## C04 — Divergência da arquitetura de referência e do EventBridge (P1)

**Verificado.**
- O desvio do `TransacaoAutorizada` (Kinesis em vez de EventBridge) já está declarado em `visao-da-solucao.md`
  §6.1, com plano B.
- **Ajuste à auditoria:** a "fronteira ACID única inicialmente proposta" não é deste projeto. Autorização e
  Limites são contextos separados desde a ADR 001, e o próprio PDF (§3.2) pede "Serviço de Limites, Serviço de
  Autorização" separados e database-per-service. ECS × EKS é escolha deixada ao candidato (§3.2).
- Falta uma ADR comparativa única, com custo, latência e risco lado a lado, que diga o que é atendido
  literalmente e o que foi adaptado.

**Correção.** Nova **ADR 033 — Alternativas de desenho e aderência literal ao PDF**:

| Tema | Alternativa A | Alternativa B (escolhida) | Requisito do PDF |
|---|---|---|---|
| Evento `TransacaoAutorizada` | EventBridge com aumento de quota | Kinesis + EventBridge na integração | exemplo (§2.4) × "avaliar Kinesis" (§3.3) |
| Derivado (opção da auditoria) | Kinesis → EventBridge Pipes → barramento, só para eventos de baixo volume (ex.: estornos) | — | atende o exemplo sem pagar o alto volume |
| Compute | EKS | ECS Fargate | escolha do candidato (§3.2) |
| Consistência Autorização × Limites | um contexto, uma transação ACID | dois contextos, saga idempotente + compensação (C01) | database-per-service (§3.2) |
| Outbox | EventBridge Pipes | relay Lambda | não especificado |

Deixar explícito: entre Autorização e Limites há **consistência eventual com compensação**, e a janela máxima
de inconsistência é de 15 min (C01).

**D3.** Se a decisão for atender o exemplo literalmente, implementar a linha "Derivado" ou mover o
`TransacaoAutorizada` para o EventBridge, com o custo da §6.1.

**Aceite.** ADR 033 publicada; a matriz §4 da visão aponta para ela.

---

## C05 — RESERVA e DÉBITO com o mesmo efeito (P1)

**Verificado.** `TipoOperacao`, `Posicao` (`limiteConcedido`, `disponivel`) e `RegraDeConsumoDeLimite` tratam
RESERVA e DÉBITO igualmente; não há saldos separados de reservado e utilizado, nem captura, liberação ou expiração.
A ADR 021 e `docs/dominio.md` dizem "captura fora do escopo", mas de forma discreta, e o campo `saldo_reservado`
pode ser lido como "reservado", quando na prática é "consumido".

**Correção (D2).**

| Opção | O que muda |
|---|---|
| **Documentar** (mínimo) | Seção "Simplificações de escopo" em `dominio.md` e `visao-da-solucao.md`: RESERVA = pré-autorização que consome limite como débito; `saldo_reservado` = limite comprometido após a operação (definição do próprio PDF §2.4); captura e liberação ficam para a evolução. Pergunta preparada no roteiro da defesa |
| Implementar | `Posicao` com `reservado` e `utilizado`; operações `CAPTURA` (reservado → utilizado) e `LIBERACAO` (reservado → disponível); expiração da reserva por TTL + Streams → liberação automática; eventos `ReservaCapturada`/`ReservaLiberada`; testes de concorrência, expiração e estorno parcial |

**Aceite (documentar).** Nenhum documento sugere ciclo de reserva que não existe; a simplificação aparece na
matriz de aderência.

---

## C06 — Quarentena avança o checkpoint e pode perder o fato (P1)

**Verificado.** `TransacoesRecordProcessor.java:55-69`: depois de 3 tentativas, o registro vai para a quarentena
SQS e o lote segue até o checkpoint. **Agravante:** se o próprio `quarentena.enviar` falhar, a exceção sai do
`processRecords`, e o KCL não reentrega o lote; o fato contábil some sem rastro. Também não há reprocessamento
da quarentena.

**Correção.**
1. **Nunca avançar sem destino durável.** Se a quarentena falhar, repetir com backoff (teto de 30 s) **sem
   sair do registro e sem checkpoint**, o que segura o shard e dispara o alarme de idade do iterador. Se vier o
   pedido de desligamento, sair sem checkpoint; o próximo worker reprocessa, e o inbox garante a idempotência.
2. **Reprocessamento automático da quarentena.** Um listener na Contabilização consome a fila de quarentena com
   visibilidade longa (1 h) e tenta contabilizar de novo; após N recebimentos (ex.: 24, ou seja, 1 dia), a
   mensagem vai para a DLQ `contabilizacao-quarentena-dlq` com alarme e tratamento manual.
3. **Métricas e alarmes:** mensagens visíveis na quarentena > 0 por 15 min; DLQ > 0.
4. **Reconciliação contábil diária real**, que também substitui a promessa falsa do C01:
   - o Aurora exporta os `evento_id` contabilizados do dia para o S3 (`aws_s3.query_export_to_s3`, recurso do
     Aurora PostgreSQL);
   - o Athena compara com a trilha S3 dos eventos `TransacaoAutorizada`/`EstornoRealizado`;
   - os ids faltantes vão para o republicador do C03.

   Agendado pelo EventBridge Scheduler.

**Arquivos.** `TransacoesRecordProcessor.java`, `SqsQuarentena.java`, novo `ReprocessadorDeQuarentena`,
`modulos/eventos/main.tf` (fila com redrive para DLQ), `modulos/observabilidade/main.tf` (alarmes), novo
`modulos/reconciliacao` (Scheduler, Athena, Lambda), `V2__exportacao.sql` se necessário.

**Testes.**
- Falha da quarentena → o processador não retorna e não faz checkpoint até a quarentena aceitar.
- Pedido de desligamento durante a espera → sai sem checkpoint.
- Reprocessamento: mensagem da quarentena contabilizada na segunda tentativa → removida; inbox evita duplicar.

**Aceite.** Nenhum caminho do processador faz checkpoint de um registro que não foi contabilizado nem gravado na
quarentena.

---

## C07 — Role de CloudWatch do API Gateway (P2)

**Verificado.** Não existe `aws_api_gateway_account` no Terraform, e `modulos/api/main.tf:166` define
`logging_level = "ERROR"`. Numa conta sem a role configurada na região, o `apply` do stage falha. O `terraform
validate` não detecta isso, porque é uma configuração de conta.

**Correção.**
- `modulos/api/main.tf`: `aws_iam_role` com confiança `apigateway.amazonaws.com`, a política gerenciada
  `AmazonAPIGatewayPushToCloudWatchLogs` e `aws_api_gateway_account` com `cloudwatch_role_arn`.
- Variável `gerenciar_conta_api_gateway` (padrão `true`): é configuração única por conta e região; se a
  plataforma da conta já gerenciar essa role, desligar para não sobrescrever.
- `depends_on` do stage e do `method_settings` na conta.

**Aceite.** `validate` ok; a dependência fica documentada no README de produção.

---

## C08 — Ambiente local não demonstrado; LocalStack 4.0 legado (P2)

**Verificado.** O caminho local foi revisado, mas nunca executado (sem Docker na máquina de desenvolvimento). A
imagem `localstack/localstack:4.0` é da linha Community antiga; desde 2026 as imagens atuais exigem auth token.

**Correção.**
- `docker-compose.yml`: `image: ${LOCALSTACK_IMAGE:-localstack/localstack:4.0}` e repassar
  `LOCALSTACK_AUTH_TOKEN` (já existe). Permite trocar para a imagem atual com token sem editar o arquivo.
- `COMO-RODAR.md`: seção "LocalStack atual com conta Hobby", com as condições de licença (uso não comercial) e o
  aviso de não executar o Terraform de produção.
- Execução na máquina de teste (Mac Apple Silicon): roteiro do C11, duas vezes, em ambiente limpo.

**Aceite.** O roteiro do C11 passa duas vezes seguidas com `derrubar.sh` entre elas.

---

## C09 — Modelo FinOps ilustrativo (P2)

**Verificado.** `calcular_custos.py` reproduz US$ 35.077/mês com 1.500 TPS médios, premissa do autor e não do
PDF. Os −71% comparam dois desenhos do próprio autor, não uma medição.

**Correção.**
- Cenários de **500, 1.500, 3.000 e 5.000 TPS médios**, com custo total, custo por milhão e quais itens são
  lineares e quais são fixos.
- Dimensionamento de **pico** separado da média (tasks, shards, capacidade provisionada) e efeito de **contrato
  quente** (partição do DynamoDB).
- Itens que hoje faltam: CloudWatch Logs e métricas, X-Ray/ADOT, transferência de dados, backups/PITR, NAT
  (zero, por usar endpoints), o C01 (+US$ 1,1 mil) e retenção de 90 dias.
- Rótulos explícitos: "preço de lista confirmado (Price List API)" × "premissa".
- Reescrever os −71% como "comparação entre dois desenhos, não benchmark medido".

**Aceite.** `custos.md` com a tabela de sensibilidade e a separação entre fato e premissa.

---

## C10 — Diagramas (P3)

**Correção.**
- **Deployment com ícones oficiais da AWS** (AWS Architecture Icons): conta, região sa-east-1, VPC, 3 AZs,
  subnets, ECS Service Connect, NLB, VPC Link, DynamoDB, Streams, Kinesis, EventBridge, Lambda, Aurora, S3
  Object Lock, WAF, Cognito, API Gateway.
- **Sequência do caminho incerto e da compensação** (após o C01).
- **Máquina de estados** da `Autorizacao` (APROVADA, RECUSADA, ESTORNADA_PARCIALMENTE, ESTORNADA) e da
  `Operacao` no Limites (APLICADA, REVERTIDA).
- **Mapa BIAN** com a ressalva "referência, não equivalência 1:1".

**Aceite.** Quatro diagramas novos, gerados por script e versionados em `docs/diagramas/`.

---

## C11 — Roteiro de aceitação executável (seção 6 da auditoria)

**Verificado.** O `demo.sh` cobre ACL, 201/200/422/402, 50 concorrentes, 503 e o evento na contabilização; **não
cobre** estorno, perda silenciosa nem falhas induzidas com recuperação.

**Correção:** novos passos no `demo.sh`, com verificação automática (sai com erro se o esperado não acontecer).

| Critério da auditoria | Passo | Como |
|---|---|---|
| contrato criado pela ACL | 0 (existe) | — |
| 50 concorrentes sem ultrapassar | 5 (existe) | passa a **verificar** `disponivel ≥ 0` e `201 ≤ 45` |
| replay idempotente | 2 (existe) | — |
| **estorno controlado** | novo | estorno parcial → 201; estorno acima do aprovado → 422; `EstornoRealizado` contabilizado |
| evento sem perda silenciosa | novo | Contabilização parada → 10 autorizações → Contabilização volta → as 10 aparecem no razão |
| inscrição Kinesis recebida | novo | logs dos stubs de antifraude e notificações com os `id` |
| ledger double-entry + inbox | 8 (existe) | passa a verificar Σ D = Σ C e nenhum `fato_id` duplicado |
| **falha induzida com compensação** | novo | Limites com atraso injetado (variável só do ambiente local) → 503 → o reconciliador reverte (atraso local reduzido mantendo janelas disjuntas) |
| trace consultável | 9 (existe) | consulta à API do Jaeger pelo `correlation_id` |
| duas execuções limpas | — | `derrubar.sh` + `subir.sh` + `demo.sh`, duas vezes |

**Aceite.** `demo.sh` termina com "todos os critérios atendidos" duas vezes seguidas na máquina de teste.

---

## C12 — Documentação que promete o que não existe

Achado transversal: corrigir **antes** de qualquer apresentação.

| Afirmação | Onde | Correção |
|---|---|---|
| "reconciliação diária (trilha S3 × operações)" | ADR 027, `visao-da-solucao.md` §6.3, `roteiro-apresentacao.md`, `SqsVerificacaoPosteriorAdapter.java:39` | substituir pelo mecanismo real do C01 e do C06 |
| "ordem garantida por contrato" | 10 locais (C02) | "particionado por contrato; ordem não garantida; consumidores independentes de ordem" |
| "alarme de IteratorAge" como mitigação suficiente | `visao-da-solucao.md` §6.3 | alarme + runbook de republicação (C03) |
| "−71%" sem ressalva | README, `custos.md`, pôster | "comparação entre desenhos" (C09) |
| "80 testes" | README, pôster | manter (executados três vezes, a última a partir do ZIP), citando o comando que reproduz |

---

## Rastreabilidade

| Achado da auditoria (seção 3) | Item deste plano |
|---|---|
| P0 compensação financeira | C01, C12 |
| P0 ordenação Kinesis | C02, C12 |
| P1 Outbox > 24 h | C03 |
| P1 divergência de arquitetura / EventBridge | C04 |
| P1 RESERVA × DÉBITO | C05 |
| P1 quarentena e checkpoint | C06 |
| P2 CloudWatch do API Gateway | C07 |
| P2 teste local / LocalStack 4.0 | C08, C11 |
| P2 FinOps | C09 |
| P3 diagramas | C10 |
| Seção 5, bloco B (testabilidade local) | C08, C11 |
| Seção 5, bloco C (melhorias da defesa) | C04, C05, C09, C10 |
| Seção 6 (critério mínimo de aceitação) | C11 |

## Pontos da auditoria que precisam de ajuste

| Afirmação da auditoria | Ajuste |
|---|---|
| "fronteira ACID única inicialmente proposta" | não pertence a este projeto: a separação vem da ADR 001 e do próprio PDF (§3.2) |
| ordem por contrato como requisito | o PDF exige o contrário: consumidores tolerantes a reordenação (§2.4). A falha está na promessa, não na ausência de ordenação |
| "80 testes não comprovados" | correto do ponto de vista do auditor, que não tinha Maven; foram executados aqui (`mvn -B install`, inclusive a partir do ZIP) |
