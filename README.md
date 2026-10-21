# Case Itaú POD99 — Nova Plataforma de Finanças

Este repositório contém o trabalho integral entregue pelo Claude Code e uma auditoria técnica incremental, **sem GitHub Actions e sem infraestrutura AWS provisionada**.

## Status da auditoria

| Fase | Entrega |
|---|---|
| 1 — Importação | **244/244 arquivos do ZIP original verificados por SHA Git** — [commit de preservação](https://github.com/lucianofalls/case-aws-finance/commit/ad35be77a0293824a610f18b72c10e71af6acc07) |
| 2 — Consistência e compensação | Regressões corrigidas em reversão, SQS e consulta Athena + [ADR-036](apresentacao-itau/docs/adr/036-hardening-compensacao.md) |
| 3 — Infraestrutura/eventos/local | Scripts executáveis, portas loopback, validador estático, [ADR-037](apresentacao-itau/docs/adr/037-eventbridge-contrato-vs-kinesis.md) e [ADR-038](apresentacao-itau/docs/adr/038-validacao-local-sem-actions.md) |
| 4 — Relatório | **[Relatório final](docs/auditoria/RELATORIO-FINAL.md)** e [matriz de aceite](docs/auditoria/MATRIZ-REQUISITOS.md) |

## Abrir o projeto

- [Código principal, serviços e infraestrutura](apresentacao-itau/)
- [Visão geral do Claude Code](apresentacao-itau/docs/visao-da-solucao.md)
- [Como executar no Mac / Docker / LocalStack](apresentacao-itau/COMO-RODAR.md)
- [Diagramas da solução](apresentacao-itau/docs/diagramas/)
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

1. Executar Maven, Terraform validate/plan e Docker/LocalStack E2E na máquina do usuário.
2. Validar em execução a compensação de operação entre dois commits independentes.
3. Fechar o desvio literal do evento `TransacaoAutorizada` enviado ao Kinesis, quando o PDF mostra EventBridge, e demonstrar o papel SNS/SQS no fan-out de negócio.
4. Provar recuperação acima da retenção de 24h do DynamoDB Streams.
5. Dimensionar e medir 5.000 TPS de pico e confirmar retenção regulatória.

## Regras de segurança do trabalho

- **Nunca ativar GitHub Actions** sem solicitação explícita.
- **Nunca executar `terraform apply` em AWS**, criar recursos ou gerar custos sem autorização.
- Reportar separadamente testes realizados e somente previstos.
- Preservar o commit original para comparação com as alterações de auditoria.
