"""Gera docs/diagramas/poster-solucao.svg: a solução em uma página (estudo e apresentação à banca).

Uso: python gerar_poster.py   (sem dependências externas; o PNG é renderizado à parte, ex.: Chrome headless)
"""
from pathlib import Path
from xml.sax.saxutils import escape

LARGURA, ALTURA = 2400, 1660
FONTE = "Segoe UI, Helvetica, Arial, sans-serif"
AZUL = "#123a6b"
AZUL_CLARO = "#eef4fb"
LARANJA = "#ec7000"
LARANJA_CLARO = "#fff1e5"
CINZA = "#5b6573"
TEXTO = "#1d2939"
BORDA = "#d0d5dd"

partes = []


def texto(x, y, conteudo, tamanho=16, cor=TEXTO, peso="400", ancora="start", estilo="normal"):
    partes.append(f'<text x="{x}" y="{y}" font-size="{tamanho}" font-weight="{peso}" fill="{cor}" '
                  f'text-anchor="{ancora}" font-style="{estilo}">{escape(conteudo)}</text>')


def quebrar(conteudo, largura, tamanho):
    maximo = max(8, int(largura / (tamanho * 0.52)))
    linhas, atual = [], ""
    for palavra in conteudo.split():
        candidato = f"{atual} {palavra}".strip()
        if len(candidato) > maximo and atual:
            linhas.append(atual)
            atual = palavra
        else:
            atual = candidato
    return linhas + ([atual] if atual else [])


def paragrafo(x, y, largura, conteudo, tamanho=16, cor=TEXTO, peso="400", entrelinha=1.35):
    for linha in quebrar(conteudo, largura, tamanho):
        texto(x, y, linha, tamanho, cor, peso)
        y += tamanho * entrelinha
    return y


def cartao(x, y, l, a, numero, titulo):
    partes.append(f'<rect x="{x}" y="{y}" width="{l}" height="{a}" rx="16" fill="#ffffff" stroke="{BORDA}" '
                  f'stroke-width="1.5"/>')
    partes.append(f'<circle cx="{x + 34}" cy="{y + 36}" r="17" fill="{LARANJA}"/>')
    texto(x + 34, y + 42, str(numero), 17, "#ffffff", "700", "middle")
    texto(x + 62, y + 44, titulo, 22, AZUL, "700")
    partes.append(f'<line x1="{x + 20}" y1="{y + 64}" x2="{x + l - 20}" y2="{y + 64}" stroke="{BORDA}"/>')


def caixa(x, y, l, a, titulo, subtitulo="", fundo=AZUL, cor=None, borda=None, tamanho=17):
    cor = cor or ("#ffffff" if fundo == AZUL else AZUL)
    partes.append(f'<rect x="{x}" y="{y}" width="{l}" height="{a}" rx="10" fill="{fundo}" '
                  f'stroke="{borda or fundo}" stroke-width="1.5"/>')
    linhas = quebrar(subtitulo, l - 20, 13) if subtitulo else []
    y0 = y + a / 2 - (len(linhas) * 17) / 2 + 6
    texto(x + l / 2, y0, titulo, tamanho, cor, "700", "middle")
    for i, linha in enumerate(linhas, start=1):
        texto(x + l / 2, y0 + i * 17, linha, 13, cor, "400", "middle")


def seta(pontos, cor=AZUL, tracejada=False, dupla=False):
    caminho = " ".join(f"{x},{y}" for x, y in pontos)
    traco = ' stroke-dasharray="8 6"' if tracejada else ""
    inicio = f' marker-start="url(#p-{cor[1:]})"' if dupla else ""
    partes.append(f'<polyline points="{caminho}" fill="none" stroke="{cor}" stroke-width="2.6"{traco}{inicio} '
                  f'marker-end="url(#p-{cor[1:]})"/>')


def item(x, y, largura, titulo, descricao, cor=AZUL, tamanho=15):
    partes.append(f'<circle cx="{x + 6}" cy="{y - 5}" r="5" fill="{cor}"/>')
    texto(x + 20, y, titulo, tamanho + 1, cor, "700")
    return paragrafo(x + 20, y + tamanho * 1.45, largura - 20, descricao, tamanho, TEXTO) + 8


# ---- Cabeçalho ----------------------------------------------------------------------------------------
partes.append(f'<rect width="{LARGURA}" height="{ALTURA}" fill="#f6f7f9"/>')
partes.append(f'<rect width="{LARGURA}" height="118" fill="{AZUL}"/>')
partes.append(f'<rect y="118" width="{LARGURA}" height="6" fill="{LARANJA}"/>')
texto(40, 58, "Plataforma de Limites e Autorização de Transações", 40, "#ffffff", "700")
texto(40, 96, "Itaú POD99 · Arquiteto de Aplicação · AWS sa-east-1 · DDD + BIAN · Clean/Hexagonal · "
              "Event-Driven · Well-Architected", 20, "#ffd9b8")
texto(LARGURA - 40, 58, "a solução em uma página", 22, "#ffffff", "400", "end", "italic")
texto(LARGURA - 40, 94, "estudo e apresentação à banca", 18, "#ffd9b8", "400", "end")

# ---- 1. Problema --------------------------------------------------------------------------------------
cartao(40, 150, 700, 380, 1, "O problema (PDF)")
y = 250
for titulo, descricao in [
    ("Autorizar em tempo real", "aprovar ou recusar cada transação de 80 mi de contas (3 a 5 contratos cada), "
                                "com pico de 5.000 TPS"),
    ("Nunca passar do limite", "reserva consistente mesmo com muitas transações simultâneas no mesmo contrato"),
    ("Avisar a retaguarda", "eventos desacoplados para contabilidade, antifraude, notificações e data platform"),
    ("Respeitar o legado e a auditoria", "core banking via camada anticorrupção; idempotência e rastreio ponta a ponta"),
]:
    y = item(64, y, 650, titulo, descricao)

# ---- 2. Domínio ---------------------------------------------------------------------------------------
cartao(770, 150, 920, 380, 2, "Domínio: 3 bounded contexts + ACL (DDD × BIAN)")
contextos = [
    ("Autorização", "BIAN Transaction Authorization", "agregado Autorizacao · decide, garante idempotência, "
                                                       "registra e publica o fato"),
    ("Limites", "BIAN Position Keeping + Limit & Exposure", "agregados Posicao e Operacao · dono do saldo; "
                                                             "escrita condicional"),
    ("Contabilização", "BIAN Financial Accounting", "agregados RegistroContabil e Lancamento · partidas "
                                                     "dobradas no razão"),
]
for i, (nome, bian, descricao) in enumerate(contextos):
    x = 796 + i * 296
    partes.append(f'<rect x="{x}" y="232" width="280" height="196" rx="12" fill="{AZUL_CLARO}" stroke="#6f93c2"/>')
    texto(x + 140, 266, nome, 21, AZUL, "700", "middle")
    texto(x + 140, 290, bian, 13, LARANJA, "700", "middle")
    paragrafo(x + 16, 320, 252, descricao, 14, TEXTO)
partes.append(f'<rect x="796" y="444" width="872" height="66" rx="12" fill="{LARANJA_CLARO}" stroke="{LARANJA}"/>')
texto(816, 472, "Core Banking ACL", 18, "#7a3a00", "700")
texto(816, 496, "traduz o registro posicional do legado em LimiteConcedido · o modelo legado não entra no domínio",
      14, TEXTO)
texto(1650, 472, "Shared kernel: Dinheiro · Published language: CloudEvents", 13, CINZA, "400", "end", "italic")

# ---- 3. Números ---------------------------------------------------------------------------------------
cartao(1720, 150, 640, 380, 3, "Números")
indicadores = [("5.000", "TPS de pico (PDF)"), ("< 100 ms", "p99 na borda (premissa)"),
               ("~US$ 35 mil", "por mês (preço de lista)"), ("US$ 8,90", "por milhão de autorizações"),
               ("80 + 4", "testes + integração"), ("000–031", "ADRs registradas")]
for i, (valor, rotulo) in enumerate(indicadores):
    coluna, linha = i % 2, i // 2
    x, y = 1744 + coluna * 304, 232 + linha * 98
    partes.append(f'<rect x="{x}" y="{y}" width="288" height="86" rx="10" fill="{AZUL_CLARO}"/>')
    texto(x + 18, y + 44, valor, 30, AZUL, "700")
    texto(x + 18, y + 70, rotulo, 14, CINZA)

# ---- 4. Como funciona ---------------------------------------------------------------------------------
cartao(40, 560, 2320, 440, 4, "Como funciona")
partes.append(f'<rect x="64" y="640" width="1440" height="150" rx="12" fill="{AZUL_CLARO}" opacity="0.6"/>')
texto(80, 664, "SÍNCRONO · o cliente aguarda", 14, AZUL, "700")
partes.append(f'<rect x="64" y="806" width="2272" height="176" rx="12" fill="{LARANJA_CLARO}" opacity="0.6"/>')
texto(80, 830, "ASSÍNCRONO · eventos de domínio (o cliente já recebeu a resposta)", 14, "#7a3a00", "700")

caixa(84, 680, 170, 92, "Canal", "app, parceiro, adquirente", fundo="#ffffff", cor=AZUL, borda=AZUL)
caixa(312, 680, 330, 92, "API Gateway REST", "Cognito OAuth2 · usage plan (429) · validação OpenAPI (422) · WAF",
      fundo="#ffffff", cor=AZUL, borda=AZUL)
caixa(700, 680, 330, 92, "Autorização", "Fargate · id = UUIDv5(cliente, Idempotency-Key) → repetição devolve 200")
caixa(1150, 680, 330, 92, "Limites", "Fargate · grava só se versão igual e disponível ≥ valor (402 / 409)")
seta([(254, 726), (312, 726)])
seta([(642, 726), (700, 726)])
seta([(1030, 726), (1150, 726)], dupla=True)
texto(1090, 712, "único", 13, AZUL, "700", "middle")
texto(1090, 750, "síncrono", 13, AZUL, "700", "middle")
texto(1520, 700, "timeout 200 ms · retry com jitter · circuit breaker → 503", 15, AZUL, "700")
texto(1520, 726, "resultado incerto → SQS (15 min) → reconciliador desfaz a operação órfã", 15, "#b42318")
texto(1520, 752, "repetição aceita por 10 min · reversão só após 15 min: janelas disjuntas", 15, CINZA)

caixa(560, 850, 330, 110, "Outbox → Kinesis", "decisão e evento na mesma escrita do DynamoDB · Streams → relay "
                                                 "Lambda → Kinesis (chave id_contrato)", fundo="#ffffff",
      cor=AZUL, borda=LARANJA)
caixa(84, 850, 420, 110, "Consumidores do Kinesis", "Contabilização (KCL → Aurora) · Antifraude · Notificações "
                                                     "· Data platform (S3 WORM)", fundo="#ffffff", cor=AZUL,
      borda=LARANJA)
caixa(1010, 850, 330, 110, "Outbox OUT# → EventBridge", "LimiteAlterado · OperacaoRevertida · regras, "
                                                         "archive 90 d, DLQ", fundo="#ffffff", cor=AZUL,
      borda=LARANJA)
caixa(1460, 850, 360, 110, "EventBridge «financas»", "integração entre domínios: roteamento por regra → SQS → "
                                                     "Limites", fundo="#ffffff", cor=AZUL, borda=LARANJA)
caixa(1940, 850, 370, 110, "Core banking → ACL", "Lambda traduz o layout legado em LimiteConcedido (versão "
                                                 "monotônica)", fundo=LARANJA_CLARO, cor="#7a3a00", borda=LARANJA)
seta([(865, 772), (865, 850)], LARANJA)
seta([(560, 905), (504, 905)], LARANJA)
seta([(1315, 772), (1315, 850)], LARANJA)
seta([(1340, 905), (1460, 905)], LARANJA)
seta([(1940, 905), (1820, 905)], LARANJA)
seta([(1640, 850), (1640, 815), (1420, 815), (1420, 772)], LARANJA)
texto(1650, 840, "novo limite", 13, "#7a3a00", "700")

# ---- 5. Decisões --------------------------------------------------------------------------------------
cartao(40, 1030, 900, 440, 5, "Decisões-chave e o porquê")
y = 1124
for titulo, descricao in [
    ("Fargate no síncrono, Lambda na cola", "p99 previsível a 5k TPS, sem cold start; Lambda onde o gatilho é "
                                            "nativo (Streams, SQS, Kinesis)"),
    ("DynamoDB com escrita condicional", "concorrência sem lock distribuído; ~5 WRU por autorização (2 escritas)"),
    ("Outbox no próprio item + relay Lambda", "sem dual-write; dezenas de dólares contra ~US$ 2,7 mil/mês do Pipes"),
    ("Kinesis no alto volume, EventBridge na integração", "custo e fan-out a 5k eventos/s; EventBridge onde "
                                                          "brilha: regras e outros times"),
    ("Contract-first de verdade", "API Gateway importa o OpenAPI; Glue Schema Registry (FULL) barra evento "
                                  "incompatível"),
]:
    y = item(64, y, 850, titulo, descricao, tamanho=15)

# ---- 6. Garantias -------------------------------------------------------------------------------------
cartao(970, 1030, 700, 440, 6, "Garantias")
y = 1124
for titulo, descricao in [
    ("Idempotência", "mesma chave = mesma decisão; outro conteúdo = 422"),
    ("Consistência sob concorrência", "controle otimista + retentativa; 50 débitos simultâneos testados"),
    ("Sem dual-write", "o evento sai se, e somente se, a decisão foi gravada"),
    ("At-least-once + deduplicação", "id do CloudEvent; inbox na contabilização"),
    ("Resiliência", "circuit breaker (503), DLQ em todo consumidor, replay de 7 dias"),
    ("Rastreio", "correlation_id e traceparent ponta a ponta, entre contextos"),
]:
    y = item(994, y, 660, titulo, descricao, LARANJA, 14)

# ---- 7. Well-Architected ------------------------------------------------------------------------------
cartao(1700, 1030, 660, 440, 7, "Well-Architected")
pilares = [
    ("Excelência operacional", "Terraform, alarmes, deploy com rollback"),
    ("Segurança", "OAuth2, WAF, VPC sem NAT, IAM mínimo, KMS"),
    ("Confiabilidade", "3 AZs, idempotência, outbox, DLQ, replay"),
    ("Eficiência de performance", "acesso por chave, virtual threads"),
    ("Otimização de custos", "~71% abaixo do desenho inicial"),
    ("Sustentabilidade", "Graviton, escala a zero, TTL 35 dias"),
]
for i, (pilar, descricao) in enumerate(pilares):
    y = 1110 + i * 58
    partes.append(f'<rect x="1724" y="{y}" width="612" height="50" rx="8" fill="{AZUL_CLARO}"/>')
    texto(1740, y + 22, pilar, 15, AZUL, "700")
    texto(1740, y + 42, descricao, 14, TEXTO)

# ---- Rodapé: trade-offs -------------------------------------------------------------------------------
partes.append(f'<rect x="40" y="1496" width="2320" height="140" rx="16" fill="{AZUL}"/>')
texto(64, 1534, "Trade-offs que eu defendo", 22, "#ffffff", "700")
rodape = [
    ("Kinesis, não EventBridge, no TransacaoAutorizada", "o PDF traz o EventBridge como exemplo e pede avaliar "
                                                         "Kinesis (§3.3). Plano B: EventBridge com aumento de quota"),
    ("SNS fora do fluxo de eventos", "o fan-out é do Kinesis e das regras do EventBridge; SNS entra nas "
                                     "notificações reais ao cliente"),
    ("Local × AWS", "LocalStack sobe o fluxo inteiro; API Gateway, Cognito, WAF e Glue ficam no Terraform de "
                    "produção"),
]
for i, (titulo, descricao) in enumerate(rodape):
    x = 64 + i * 770
    texto(x, 1570, titulo, 16, "#ffd9b8", "700")
    paragrafo(x, 1596, 730, descricao, 14, "#ffffff")

marcadores = "".join(
    f'<marker id="p-{cor[1:]}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
    f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{cor}"/></marker>'
    for cor in (AZUL, LARANJA))
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{LARGURA}" height="{ALTURA}" '
       f'viewBox="0 0 {LARGURA} {ALTURA}" font-family="{FONTE}"><defs>{marcadores}</defs>'
       + "\n".join(partes) + "</svg>\n")
destino = Path(__file__).with_name("poster-solucao.svg")
destino.write_text(svg, encoding="utf-8", newline="\n")
print(destino)
