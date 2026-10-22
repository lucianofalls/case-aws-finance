# ADR-046 — Aceite financeiro local reproduzível com LocalStack

Data: 22/10/2026. Status: roteiro implementado, build e verificações auxiliares executados;
aceite Docker/LocalStack ainda depende da execução no Mac.

## Problema

O roteiro anterior imprimia respostas sem confirmar os resultados e não encerrava em
várias falhas. A expiração simulada chegava imediatamente, mas o domínio agora exige
vencimento real (sete dias por padrão). IDs gerados por dois `$RANDOM` podiam exceder
os 20 caracteres do formato posicional do core. A documentação ainda falava em
Fargate/Aurora e identidade por header, divergindo do código EKS/RDS/JWT.

## Decisão

1. `local/executar-tudo.sh` é a entrada única, com `set -euo pipefail`, evidência em
   arquivo local ignorado pelo Git, build, setup e aceite. Nenhum GitHub Actions.
2. `demo.sh` e `demo-ciclo-reserva.sh` delegam a `verificar-demo.py`, biblioteca padrão
   Python. Cada operação tem status esperado; IDs, saldos/invariante, Inbox e partidas
   dobradas são conferidos. Qualquer divergência produz exit 1.
3. A validade **apenas do Compose local** é PT2M (configurável entre 30s e 4min). O
   verificador lê o vencimento persistido, espera e entrega a SQS. O domínio não tem
   bypass, relógio adulterado ou endpoint administrativo de expiração.
4. Contratos sintéticos usam `CTR-` + 12 dígitos hexadecimais, dentro da largura 20.
   Entitlements são seed administrativo local, com validade, para canais sintéticos.
5. Autenticação usa JWT RSA efêmero. Chaves privadas e logs locais permanecem fora
   do Git; portas publicadas ficam no loopback. Rede Docker `financas_default` é
   explícita para os runtimes Lambda encontrarem LocalStack e os serviços.
6. O Terraform local tem endpoints loopback, credenciais fictícias e validação da
   variável de endpoint. O preflight rejeita variáveis CLI/state que alterem a execução
   silenciosamente. Não há apply no ambiente de produção.
7. Só DEBITO/CREDITO/CAPTURA/ESTORNO geram lançamentos. RESERVA/LIBERACAO devem estar
   no Inbox, sem lançamentos. A reentrega da expiração não pode criar nova liberação.

## Validação e limites

Os 142 testes unitários Java passaram com JDK21/Maven3.9.9. No runtime de revisão,
o Mockito precisou do agente Byte Buddy carregado no início da JVM por restrição
de self-attach; a alteração é somente da invocação de teste, não da aplicação.
Há três testes Python do verificador para detectar comprometido confundido com
utilizado, invariante quebrada e ID contábil incorreto com mesma quantidade de linhas.

Docker não está disponível neste runtime. Não declarar E2E, Testcontainers, download
de imagens ou o baseline LocalStack 4.0 aprovados nesta revisão. A imagem legada é
preservada; compatibilidade no Mac e eventual atualização merecem evidência própria.

LocalStack/PostgreSQL local não comprovam EKS Auto Mode, Pod Identity, VPC Link/NLB,
IAM, Cognito real/JWKS privado, TLS/failover RDS, Scheduler real, conciliação diária
Athena/Glue/S3, restauração/DR ou capacidade de 5.000 TPS. Fases 2–4 e 6–7 de produção
continuam no backlog. Não reduzir o aceite bancário a uma demo verde.

A priorização do aceite local foi autorizada pelo usuário após a revisão da Fase 1.
O protocolo de fases foi preservado em commits separados, sem alegar fechamento das
fases de produção que o emulador não consegue validar.

## Referências

- [LocalStack Lambda e Docker](https://docs.localstack.cloud/aws/services/lambda/)
- [Rede LocalStack](https://docs.localstack.cloud/aws/customization/networking/)
- [Spring JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [ADR-045](045-identidade-jwt-entitlements.md)

## Complemento de setup — 22/10/2026

A saúde HTTP do emulador não prova prontidão de recursos assíncronos. Antes de
iniciar serviços/E2E, `local/verificar-infra.py` exige tabelas ativas com Streams,
Kinesis ativo, filas, sete Lambdas ativas, mappings Enabled e regra EventBridge com
destino. Lambda Failed interrompe com o motivo. A verificação não substitui uma
invocação real; somente o aceite financeiro comprova execução dos handlers.

O setup exige versões mínimas Maven/Compose/Python/Terraform, preserva o lockfile
(`init -lockfile=readonly`) e não apaga estado em erros de conectividade. A entrada
única captura logs em falhas. Docker ausente neste host: nenhum E2E declarado.
