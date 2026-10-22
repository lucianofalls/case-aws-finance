# ADR-045 — JWT no backend e identidade dos canais

Status: implementado no Resource Server; integração Cognito/EKS e alimentação corporativa pendentes.
Data: 22/10/2026. Complementa ADR-043. Não altera EKS/RDS.

## Decisão e escopo do PDF

O §3.4 aceita Cognito, Lambda Authorizer ou OAuth2. Cognito não é obrigatório, mas já existe no Terraform e fica como emissor M2M de referência. Um IdP corporativo OAuth2 pode substituí-lo mediante adaptação do authorizer e do contrato de claims. Não criar 80 milhões de usuários: o §2.2 informa contas bancárias, não usuários autenticáveis ou MAUs. Contratos estimados: 240–400 milhões, pela média de 3–5 por conta. Conta, titular, contrato, canal e aplicação OAuth são entidades distintas.

O `client_id` representa a aplicação/canal autorizado. A autenticação do correntista e consentimento/delegação do canal ficam no domínio corporativo de identidade; não inferir que um token client_credentials autentique uma pessoa. Se o banco exigir identidade individual ponta a ponta, exigir token delegado com sujeito/consentimento e adequar a projeção, sem reutilizar client_id como CPF ou usuário.

- API Gateway valida o token na borda; Autorização valida novamente assinatura RS256, emissor, validade, token_use=access e client_id em allowlist. A rota exige scope `financas/autorizacoes.write`.
- X-Client-Id deixa de ser fonte de identidade. A chave idempotente permanece `(client_id, Idempotency-Key)`, preservando a semântica existente.
- JWKS vem de URI HTTPS configurada, nunca de URL fornecida no token. Cache é gerido pelo decoder; não emitir/introspectar token por transação.
- Cluster privado sem NAT precisa de saída corporativa controlada ou espelho JWKS HTTPS com rotação e autenticidade preservadas. Endpoint eks-auth não resolve JWKS Cognito. Validar esse acesso antes do deploy.
- Tokens sem autenticação recebem 401; escopo insuficiente/entitlement negado, 403.
- Perfil local usa somente chave pública local; chave privada efêmera fica em diretório ignorado pelo Git. Nenhum bypass por header ou flag permitAll foi adicionado.

## Entitlements: fonte e revogação

A tabela é uma projeção do serviço corporativo de autorização/relacionamento, não a fonte original. O produtor corporativo e o identificador do sistema dependem do banco; nenhum endpoint de concessão foi exposto na API financeira. O contrato de integração está em `contracts/schemas/entitlement-v1.json`.

O integrador administrativo deve autenticar o produtor, aplicar eventos com condição `attribute_not_exists(versao) OR versao < :nova`, persistir revogações como tombstones (`ativo=false`) e emitir auditoria. Nunca apagar tombstones antes da janela máxima de replay: evento antigo não pode reativar permissão. Repetição de mesma versão com outro conteúdo exige quarentena. Concessão e revogação não vêm do payload financeiro nem de simples evento de criação de contrato.

Cada autorização consulta a projeção com leitura forte e sem cache, antes inclusive do replay idempotente. Agora `valido_ate` (epoch seconds) é obrigatório em runtime e expiração é verificada pelo código: TTL do DynamoDB não tem remoção imediata. Dados legados sem validade serão negados; provisionar validade pela fonte confiável antes de rollout, sem preencher permissões presumidas.

Revogação é observada na próxima leitura após a escrita da projeção. Isso NÃO garante revogação instantânea desde a origem corporativa: definir SLO de propagação, idade máxima da concessão, monitoramento de lag e resposta a indisponibilidade com o banco. O adaptador de ingestão corporativo ainda é dependência de produção; demo faz apenas seed sintético com validade curta.

## Cognito e escala

Capacidade M2M depende de aplicações, instâncias que mantêm cache e intervalo de renovação, não das contas. Exemplo explicitamente hipotético: 100 processos renovando a cada 45 minutos geram média de 0,037 token/s; usar jitter para evitar rajadas sincronizadas. A API pode processar 5.000 TPS reutilizando access tokens válidos e verificando assinaturas localmente. Isso não é benchmark. Quotas e preços M2M devem ser cotados separadamente de MAU; medir cache miss, rotação JWKS e tempestade de renovação.

## Evidências e limites

Testes cobrem token forjado/expirado, emissor/client_id/token_use incorretos, scope e header falsificado; testes de adapter cobrem revogação, conta/contrato/cliente cruzados e validade. Tráfego Autorização→Limites ainda é responsabilidade da Fase 2: JWT de entrada não resolve identidade de workload ou TLS interno.

Referências:
- https://aws.amazon.com/blogs/security/how-to-monitor-optimize-and-secure-amazon-cognito-machine-to-machine-authorization/
- https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html
- https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html
- https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
