# Revisão de prontidão para entrega — 22/10/2026

**Parecer: ainda não pronto para entrega final.** Arquitetura coerente como proposta, implementação parcial e execução não demonstrada. Revisão documental e estática; não é certificação AWS, auditoria regulatória nem prova de runtime.

Baseline revisado: `e2563e0fc5e02c076f0193ab75828963deb13494`, também confirmado no remoto.

## Fonte primária recuperada

Lidas integralmente as cinco páginas de `Desafio Técnico — Arquiteto de Aplicação.docx.pdf`, localizado em Downloads, e sua cópia `(1)` com o mesmo conteúdo textual. O PDF não foi copiado para o repositório. A indicação anterior de PDF não localizado refletia a busca limitada à árvore Git e Documents/consultoria; esta revisão resolve essa limitação.

- §2.2: aproximadamente 80 milhões de contas, 3–5 contratos por conta e pico de 5.000 TPS. Não especifica média de 1.500 TPS nem SLA numérico de latência/disponibilidade.
- §2.3: quatro operações no exemplo; CAPTURA/LIBERACAO são extensões da solução e precisam de justificativa e contratos consistentes.
- §2.4: exemplo TransacaoAutorizada em EventBridge; CloudEvents, deduplicação, evolução de schema e tolerância a reordenação.
- §3.2: EKS é opção explícita; persistência fica a critério do candidato. Aurora é exemplo, não obrigação. RDS PostgreSQL convencional é uma escolha admissível.
- §3.3: modelar EventBridge/SNS/SQS e avaliar SQS/Kinesis/MSK. Manter Kinesis é defensável, mas não elimina explicar a diferença do exemplo EventBridge.
- §5: ZIP com código, IaC, diagramas e ADRs; apresentação de 20 minutos, perguntas por 40 minutos e demonstração local com Docker OU AWS. Deploy AWS não é pré-requisito para demonstrar o case.

## Achados por prioridade

| Prioridade | Evidência no repositório | Impacto e fechamento necessário |
|---|---|---|
| P0 entrega | `docs/evidencias/fase-0-2026-10-22.txt`: Maven não executado, init/validate bloqueados | Falta comprovar build e demonstração exigida pelo §5. Executar JDK21/Maven, Terraform compatível e E2E local com logs |
| P0 segurança | `AutorizacaoController.java:37` recebe X-Client-Id; `k8s/templates/stack.yaml.tpl:111` usa HTTP para Limites | Acesso interno alternativo não deve poder forjar identidade. Implementar validação de identidade/token e proteção do tráfego interno; testar bypass e acesso cruzado |
| P0 segurança | `stack.yaml.tpl:438` restringe ingress de Limites, mas permite CIDR VPC e não define egress; ADR-043 registra fonte de entitlement pendente | NetworkPolicy não comprova autenticação. Definir provisionamento/revogação e isolamento efetivo |
| P0 dados | `stack.yaml.tpl:381` e `ExportarRazaoJdbcHandler.java:38` usam sslmode=require | TLS não verifica identidade do servidor nesse modo. Preparar verify-full/CA, segregação SQL e rotação; provar conexão |
| P0 integridade | `DynamoDbPosicaoRepository.java:147-218` contém condições por reserva e transação | Boa defesa implementada, mas concorrência, compensação e expiração ainda precisam de testes executados; não afirmar ausência de falhas financeiras |
| P1 aderência | ADR-040 e flag EventBridge OFF | Ponte preparada não é demonstração do exemplo §2.4. Validar fluxo pequeno ou defender claramente o desvio, preservando Kinesis como caminho principal |
| P1 capacidade | `docs/custos/custos-eks-rds.md`, `docs/visao-eks-rds.md` | Não há comprovação de 5.000 TPS nem dimensionamento de writer, retenção, contenção e quotas. Defender modelo com hipóteses, teste e limites explícitos |
| P1 recuperação | ADR-041 | Replay manual e trilha potencialmente incompleta não garantem recuperação de evento nunca publicado. Definir fonte independente e ensaio de falha/replay |
| P1 operação | Busca por metrics-server/aws_eks_addon em infra e k8s não encontrou implantação | Status anterior dizia addon existente sem evidência nos arquivos. HPA requer fonte de métricas configurada; confirmar na Fase 2 |
| P2 apresentação | Documentação e diagramas anteriores ECS/Aurora preservados como histórico | Pacote final deve destacar somente arquitetura vigente e separar histórico; atualizar custos, figuras, roteiro e gerar ZIP revisado |

Os achados de segurança bloqueiam uma declaração de prontidão bancária. Para o case de cinco dias, controles corporativos e DR real podem ser apresentados como dependências explícitas, mas não como funcionalidades já implementadas/testadas. O mínimo executável e a consistência dos artefatos continuam obrigatórios.

## Padrões modernos de arquitetura

DDD com três bounded contexts, portas/adaptadores, persistência por serviço, outbox, Inbox, idempotência, CloudEvents e mini-saga formam uma base adequada ao problema. Há evidência de código e de contratos; a adequação não equivale à validação integral. EKS não torna uma solução automaticamente superior: a defesa deve justificar governança Kubernetes, operação, custo e limites. RDS Multi-AZ oferece proposta de disponibilidade; não demonstra escala de escrita. Não é necessário reescrever a solução nem trocar EKS/RDS.

`docs/dominio.md` explica Repository, Domain Events e CQRS seletivo; `docs/visao-da-solucao.md` mapeia os campos do enunciado para CloudEvents e documenta depreciação. Validar esses mapeamentos com testes de contrato, inclusive as extensões de reserva, antes da entrega.

## AWS Well-Architected e FSI Lens

Avaliação qualitativa deste repositório, sem pontuação inventada:

| Pilar | Evidência favorável | Lacuna |
|---|---|---|
| Excelência operacional | IaC, ADRs, scripts e logs estáticos | Build/demo, métricas HPA, observabilidade e runbooks exercitados |
| Segurança | KMS, rede privada, Pod Identity e entitlement | Identidade interna, TLS verificado, revogação e segregação SQL comprovada |
| Confiabilidade | Multi-AZ, outbox/Inbox, condições atômicas e DLQs | Concorrência/falhas, replay completo, restore e RPO/RTO medidos |
| Eficiência de performance | Kinesis, HPA declarativo e DynamoDB | Modelo e medição de 5.000 TPS, hot keys e writer PostgreSQL |
| Otimização de custos | Cenários de volume e componentes identificados | Orçamento EKS/RDS atual e análise de sensibilidade |
| Sustentabilidade | Potencial de autoscaling e serviços gerenciados | Métricas de utilização, dimensionamento e retenção/arquivamento justificados |

A AWS orienta avaliação nos seis pilares; a FSI Lens acrescenta operação documentada, segurança desde o desenho e governança. Esta revisão identifica lacunas contra essas orientações, sem afirmar conformidade regulatória.

Fontes oficiais consultadas:
- https://docs.aws.amazon.com/wellarchitected/latest/framework/the-pillars-of-the-framework.html
- https://docs.aws.amazon.com/wellarchitected/latest/financial-services-industry-lens/design-principles.html
- https://docs.aws.amazon.com/wellarchitected/latest/financial-services-industry-lens/reliability.html

## Critério de liberação do pacote

1. Encerrar Fase 0 com build/contratos e Terraform validados, ou limitações justificadas sem artefatos sabidamente inválidos.
2. Fechar P0 de identidade e integridade financeira; demonstrar autorização, duplicidade, conflito, recusa, reserva parcial, expiração e estorno.
3. Demonstrar autorização → evento → Inbox/ledger e recuperação de falhas no ambiente local.
4. Explicitar EventBridge, capacidade, custos e dependências corporativas; não alegar 5.000 TPS medidos sem teste.
5. Unificar diagramas/ADRs/roteiro e empacotar ZIP reproduzível; ensaiar defesa de 20+40 minutos.

Nenhum deploy, GitHub Actions, teste de carga ou recurso AWS foi executado nesta revisão. Não se iniciou implementação das fases posteriores.
