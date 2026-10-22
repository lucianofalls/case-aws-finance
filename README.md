# Case Itaú POD99 — Nova Plataforma de Finanças

Este repositório contém o trabalho integral entregue pelo Claude Code e uma auditoria técnica incremental, **sem GitHub Actions e sem infraestrutura AWS provisionada**.

## Executar a revisão local

```bash
cd apresentacao-itau
bash local/executar-tudo.sh
```

O roteiro prepara LocalStack/PostgreSQL/Jaeger, sobe os serviços com JWT local e executa
asserções financeiras/HTTP/Inbox/ledger. Pré-requisitos e diagnóstico em
[COMO-RODAR.md](apresentacao-itau/COMO-RODAR.md). **142 testes Java e três testes Python
passaram nesta revisão; E2E Docker/LocalStack ainda precisa ser executado no Mac.**
A arquitetura de produção permanece EKS Auto Mode + RDS PostgreSQL convencional Multi-AZ.
Consulte [ADR-046](apresentacao-itau/docs/adr/046-aceite-localstack-financeiro.md).

## Status da auditoria

| Fase | Entrega |
|---|---|
| 1 — Importação | **244/244 arquivos do ZIP original verificados por SHA Git** — [commit de preservação](https://github.com/lucianofalls/case-aws-finance/commit/ad35be77a0293824a610f18b72c10e71af6acc07) |
| 2 — Consistência e compensação | Regressões corrigidas em reversão, SQS e consulta Athena + [ADR-036](apresentacao-itau/docs/adr/036-hardening-compensacao.md) |
| 3 — Infraestrutura/eventos/local | Scripts executáveis, portas loopback, validador estático, [ADR-037](apresentacao-itau/docs/adr/037-eventbridge-contrato-vs-kinesis.md) e [ADR-038](apresentacao-itau/docs/adr/038-validacao-local-sem-actions.md) |
| 4 — Relatório | **[Relatório e atualizações](docs/auditoria/RELATORIO-FINAL.md)** e [matriz de aceite](docs/auditoria/MATRIZ-REQUISITOS.md) |
| 5 — Correções de 22/10 | [Status C01–C12](apresentacao-itau/docs/STATUS-FASES.md), [ADR-039](apresentacao-itau/docs/adr/039-protecao-agendamento-quarentena.md), [ADR-040](apresentacao-itau/docs/adr/040-ponte-kinesis-eventbridge-opcional.md), [ADR-041](apresentacao-itau/docs/adr/041-replay-ttl-evidencias.md) |

## Abrir o projeto

- [Código principal, serviços e infraestrutura](apresentacao-itau/)
- [Visão geral do Claude Code](apresentacao-itau/docs/visao-da-solucao.md)
- [Como executar no Mac / Docker / LocalStack](apresentacao-itau/COMO-RODAR.md)
- [Diagramas da solução](apresentacao-itau/docs/diagramas/) e [fluxos atualizados](apresentacao-itau/docs/diagramas/fluxos-atuais.md)
- [Ciclo de reserva da demonstração local](apresentacao-itau/local/demo-ciclo-reserva.sh)
- [Sensibilidade FinOps](apresentacao-itau/docs/custos/sensibilidade.py)
- [ADRs do case](apresentacao-itau/docs/adr/)
- [Histórico de auditoria](docs/auditoria/)

### Teste rápido sem AWS

```bash
git clone https://github.com/lucianofalls/case-aws-finance.git
cd case-aws-finance/apresentacao-itau
python3 local/verificar-estatico.py
```

Para o teste completo, siga `COMO-RODAR.md`. **`local/subir.sh` aplica Terraform apenas no LocalStack configurado no ambiente local; não execute o diretório `ambientes/prod` sem revisão de segurança/custos.**

## Principais pendências

1. Executar o aceite Docker/LocalStack no Mac e validar novamente Terraform local. Build Java desta revisão passou; validações operacionais de produção continuam pendentes.
2. Validar em execução a compensação de operação entre dois commits independentes.
3. Validar a ponte **opcional e desligada** Kinesis→EventBridge Pipes→SNS/SQS (ADR-040), suas quotas, custos e compatibilidade com o exemplo do PDF.
4. Provar recuperação acima da retenção de 24h do DynamoDB Streams e fechar automação histórica após TTL de 35 dias; runbook/manual não substitui mecanismo de produção.
5. Dimensionar e medir 5.000 TPS de pico e confirmar retenção regulatória.

## Regras de segurança do trabalho

- **Nunca ativar GitHub Actions** sem solicitação explícita.
- **Nunca executar `terraform apply` em AWS**, criar recursos ou gerar custos sem autorização.
- Reportar separadamente testes realizados e somente previstos.
- Preservar o commit original para comparação com as alterações de auditoria.

**Última atualização (22/10):** correções publicadas, mas **build Maven, Terraform validate/plan e E2E LocalStack após as alterações continuam pendentes**, a serem executados no Mac. Nenhuma AWS real foi provisionada.
