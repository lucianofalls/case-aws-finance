# Case Itaú POD99 — Plataforma de Finanças

Repositório destinado à entrega original do Claude Code, auditoria técnica e correções incrementais do desafio de arquitetura de autorização financeira em AWS.

## Organização das fases

- **Fase 0 — Importação fiel:** preservar o ZIP `itau-pod99-solucao(2).zip` com todos os fontes, Terraform, contratos, testes e diagramas.
- **Fase 1 — Consistência financeira:** revisar comunicação Autorização↔Limites, compensações, reconciliação e idempotência.
- **Fase 2 — Mensageria:** Outbox, Kinesis, EventBridge, SQS, entrega, replay e ordenação.
- **Fase 3 — Infraestrutura e testes:** IAM, ECS/compute, Terraform, execução local, custos e Well-Architected.
- **Fase 4 — ADRs e relatório final:** matriz de requisitos, evidências de testes, riscos remanescentes e recomendações para apresentação.

## Regras de execução

1. Não ativar nem criar GitHub Actions.
2. Não executar `terraform apply`, provisionar AWS ou gerar custos sem autorização.
3. Preservar a autoria e o conteúdo original; toda alteração posterior deve ter justificativa e teste.
4. Documentar o que foi **validado** e o que ainda está **pendente**, sem supor que um parse estático equivale a implantação.

> **Atenção:** este README é o índice de trabalho. A transferência completa dos arquivos do ZIP para este repositório será marcada explicitamente somente após verificação de contagem e hashes. Não considerar esta página prova de que o código foi importado.
