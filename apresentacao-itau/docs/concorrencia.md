# Concorrência: como o mesmo dado nunca é atualizado duas vezes

**Princípios:** sem lock distribuído; a regra vai **dentro** da escrita (condição no item); toda operação mutante
é idempotente; máquinas de estado com guarda; append-only onde possível.

## Catálogo

| # | Corrida | Mecanismo implementado | Resultado | Teste |
|---|---|---|---|---|
| C1 | N autorizações paralelas no mesmo contrato | `TransactWriteItems`: POSICAO `SET disponivel = :novo, versao = :v+1` **condicionado** a `versao = :lida AND disponivel >= :valor` + OPERACAO `attribute_not_exists` | nunca gasta além do limite; saldo devolvido é exato | `cinquentaDebitosSimultaneosNuncaUltrapassamOLimite` (33 aprovados, 17 recusados, disponível 1,00) |
| C2 | versão mudou / `TransactionConflict` | `PoliticaDeRetentativa`: relê, reavalia a invariante, backoff exponencial com jitter (4 tentativas) | 402 se o saldo acabou; **409** se a disputa persistir | idem |
| C3 | Autorização reenvia a operação (timeout/retry) | operação identificada pelo id da autorização; `attribute_not_exists(sk)` | devolve a fotografia original, nunca aplica duas vezes | `repeticaoComOMesmoIdNaoAplicaDuasVezes` |
| C4 | mesma Idempotency-Key em paralelo | id **determinístico** (UUID v5 de cliente + chave): Limites dedupe pela operação; `PutItem attribute_not_exists` na autorização; o perdedor lê o vencedor | uma única decisão (201 para um, 200 para os demais) | `requisicoesSimultaneasComAMesmaChaveGeramUmaUnicaAutorizacao` |
| C5 | mesma chave com outro payload | `ImpressaoDigital` (SHA-256 do conteúdo) gravada com a decisão | 422 | `mesmaChaveComOutroPayloadERejeitada` |
| C6 | estornos simultâneos do mesmo original | estorno e original na mesma transação; original condicionado a `versao = :lida` | soma dos estornos ≤ valor aprovado; o perdedor recebe 409 e é reconciliado | `estornoAcimaDoValorAprovadoERejeitadoSemTocarNoLimite` |
| C7 | limite do legado fora de ordem / com reservas em andamento | delta sobre o disponível + `versaoOrigem` monotônica; versão antiga ignorada | não perde reservas, não aplica limite velho | `eventoForaDeOrdemComVersaoAntigaEIgnorado`, `reducaoDeLimitePreservaOQueJaFoiConsumido` |
| C8 | mesmo evento entregue duas vezes à contabilização | inbox `INSERT … ON CONFLICT DO NOTHING` na **mesma transação** dos lançamentos; cada fato é contabilizado de forma independente da ordem de chegada | um registro contábil por evento | `reentregaDoMesmoEventoNaoDuplicaLancamentos` |
| C9 | saldo contábil em linha quente | razão append-only; sem `UPDATE` de saldo | sem disputa | esquema `V1__razao_contabil.sql` |
| C10 | resultado incerto (timeout após enviar, falha ao gravar a decisão) | verificação posterior em SQS com atraso de 15 min; reconciliador reverte a operação órfã | o limite não fica preso | `timeoutNoLimiteAgendaVerificacaoPosteriorEPropaga503`, `falhaAoGravarDepoisDeAplicarOLimiteAgendaVerificacao` |
| C11 | cliente retenta **enquanto** o reconciliador reverte | **janelas disjuntas**: o Limites aceita repetição só até 10 min; o reconciliador só age após 15 min | impossível aprovar com o limite já devolvido | `repeticaoForaDaJanelaEhRecusadaParaNaoDisputarComOReconciliador` |

## Por que não PENDENTE persistido nem índice de pendentes

O desenho inicial gravava a autorização como PENDENTE e mantinha um índice esparso para o reconciliador:
≈ 3 WRU a mais **em toda transação** (~US$ 11 mil/mês on-demand). Como o resultado incerto é raro, a
verificação posterior é paga **só quando acontece** (SQS com atraso) — ADR 027.

## Limites físicos

- Um item do DynamoDB serializa escritas; uma partição sustenta ~1.000 WCU/s. Um contrato de varejo não chega
  perto disso. Monitorar com Contributor Insights; contrato corporativo muito quente → sublimites fragmentados.
- Para a disputa ser rara, a transação toca só 2 itens pequenos (posição + operação).
