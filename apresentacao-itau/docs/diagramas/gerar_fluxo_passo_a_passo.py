"""Gera docs/diagramas/fluxo-passo-a-passo.svg: o funcionamento da plataforma com os passos numerados.

Uso: python gerar_fluxo_passo_a_passo.py   (sem dependências externas)
"""
from pathlib import Path
from xml.sax.saxutils import escape

LARGURA, ALTURA = 1700, 1560
FONTE = "Segoe UI, Helvetica, Arial, sans-serif"

AZUL = "#123a6b"          # caminho síncrono
LARANJA = "#ec7000"       # eventos
VERMELHO = "#b42318"      # exceção (resultado incerto)
CINZA = "#5b6573"

ESTILOS = {
    "servico": ("#123a6b", "#ffffff", "#123a6b"),   # Fargate
    "lambda": ("#fff1e5", "#7a3a00", LARANJA),
    "dados": ("#eef4fb", "#123a6b", "#6f93c2"),
    "gerenciado": ("#f2f4f7", "#1d2939", "#98a2b3"),
    "externo": ("#ffffff", "#1d2939", "#1d2939"),
}

partes = []


def caixa(x, y, l, a, titulo, linhas=(), tipo="gerenciado"):
    fundo, texto, borda = ESTILOS[tipo]
    partes.append(f'<rect x="{x}" y="{y}" width="{l}" height="{a}" rx="10" fill="{fundo}" '
                  f'stroke="{borda}" stroke-width="1.6"/>')
    total = 1 + len(linhas)
    y0 = y + a / 2 - (total - 1) * 9 + 5
    partes.append(f'<text x="{x + l / 2}" y="{y0}" text-anchor="middle" font-size="15" font-weight="700" '
                  f'fill="{texto}">{escape(titulo)}</text>')
    for i, linha in enumerate(linhas, start=1):
        partes.append(f'<text x="{x + l / 2}" y="{y0 + i * 18}" text-anchor="middle" font-size="12" '
                      f'fill="{texto}">{escape(linha)}</text>')


def seta(pontos, cor, tracejada=False, ponta=True):
    caminho = " ".join(f"{x},{y}" for x, y in pontos)
    traco = ' stroke-dasharray="7 5"' if tracejada else ""
    marcador = f' marker-end="url(#ponta-{cor[1:]})"' if ponta else ""
    partes.append(f'<polyline points="{caminho}" fill="none" stroke="{cor}" stroke-width="2.4"{traco}{marcador}/>')


def passo(x, y, numero, cor):
    partes.append(f'<circle cx="{x}" cy="{y}" r="15" fill="{cor}" stroke="#ffffff" stroke-width="2.5"/>')
    partes.append(f'<text x="{x}" y="{y + 5}" text-anchor="middle" font-size="14" font-weight="700" '
                  f'fill="#ffffff">{numero}</text>')


def rotulo(x, y, texto, cor=CINZA, ancora="middle"):
    partes.append(f'<text x="{x}" y="{y}" text-anchor="{ancora}" font-size="11.5" fill="{cor}" '
                  f'font-style="italic">{escape(texto)}</text>')


def faixa(x, y, l, a, titulo, cor_fundo, cor_texto):
    partes.append(f'<rect x="{x}" y="{y}" width="{l}" height="{a}" rx="14" fill="{cor_fundo}" '
                  f'stroke="{cor_texto}" stroke-opacity="0.35" stroke-dasharray="4 4"/>')
    partes.append(f'<text x="{x + 16}" y="{y + 24}" font-size="13" font-weight="700" fill="{cor_texto}" '
                  f'letter-spacing="0.5">{escape(titulo)}</text>')


# ---- Título --------------------------------------------------------------------------------------------
partes.append(f'<rect width="{LARGURA}" height="{ALTURA}" fill="#ffffff"/>')
partes.append(f'<text x="40" y="44" font-size="26" font-weight="700" fill="{AZUL}">'
              'Plataforma de Limites e Autorização — funcionamento passo a passo</text>')
partes.append(f'<text x="40" y="70" font-size="14" fill="{CINZA}">POD99 · AWS sa-east-1 · '
              'exemplo: débito de R$ 150,00 no contrato CT-123 (os números seguem a ordem do tempo)</text>')

# ---- Faixas --------------------------------------------------------------------------------------------
faixa(250, 210, 950, 340, "CAMINHO SÍNCRONO — o cliente aguarda (p99 < 100 ms)", "#f5f9ff", AZUL)
faixa(250, 580, 1430, 560, "CAMINHO ASSÍNCRONO — eventos de domínio", "#fffaf5",
      LARANJA)

# ---- Componentes ---------------------------------------------------------------------------------------
caixa(30, 260, 150, 100, "Canal / parceiro", ("app, internet banking,", "adquirente (M2M)"), "externo")
caixa(30, 100, 150, 76, "Amazon Cognito", ("OAuth2", "client_credentials"))
caixa(290, 250, 210, 130, "WAF + API Gateway", ("REST /v1 · Cognito authorizer", "usage plan → 429",
                                                 "validação OpenAPI → 422"))
caixa(590, 250, 240, 130, "autorizacao-service", ("Fargate · ctx Autorização", "BIAN Transaction",
                                                  "Authorization"), "servico")
caixa(590, 450, 240, 76, "DynamoDB autorizacoes", ("decisão + evento no mesmo item",), "dados")
caixa(950, 250, 240, 130, "limites-service", ("Fargate · ctx Limites", "BIAN Position Keeping +",
                                              "Limit & Exposure"), "servico")
caixa(950, 450, 240, 76, "DynamoDB limites", ("posição · operação · OUT#",), "dados")

caixa(590, 100, 240, 76, "SQS verificação", ("atraso 15 min",), "gerenciado")
caixa(950, 100, 240, 76, "reconciliador", ("Lambda", ), "lambda")

caixa(590, 620, 240, 70, "outbox-relay (autorizações)", ("Lambda · DynamoDB Streams",), "lambda")
caixa(590, 750, 240, 80, "Kinesis «transacoes»", ("8 shards · chave id_contrato", "retenção 7 dias"))
caixa(290, 930, 250, 86, "contabilizacao-service", ("Fargate + Spot · KCL", "BIAN Financial Accounting"),
      "servico")
caixa(290, 1050, 250, 70, "Aurora PostgreSQL", ("razão · partidas dobradas",), "dados")
caixa(600, 930, 220, 86, "antifraude · notificações", ("Lambdas (stubs)", "BIAN Fraud Eval. · Correspond."),
      "lambda")
caixa(880, 930, 220, 86, "Firehose → S3", ("trilha WORM (Object Lock)", "data platform · auditoria"))

caixa(950, 620, 240, 70, "outbox-relay (limites)", ("Lambda · só itens OUT#",), "lambda")
caixa(1220, 750, 220, 80, "EventBridge «financas»", ("regras · archive 90 d", "DLQ"))
caixa(1220, 450, 220, 76, "SQS limites-concedidos", ("+ DLQ",))
caixa(1220, 930, 220, 86, "Firehose → S3", ("trilha dos eventos", "de domínio"))
caixa(1480, 620, 180, 70, "Core banking", ("legado (MQ → SQS)",), "externo")
caixa(1480, 750, 180, 80, "corebanking-acl", ("Lambda · ACL", "traduz layout posicional"), "lambda")

# ---- Setas e passos ------------------------------------------------------------------------------------
seta([(105, 260), (105, 176)], AZUL)
passo(105, 218, 1, AZUL)
rotulo(120, 196, "token JWT", AZUL, "start")

seta([(180, 315), (290, 315)], AZUL)
passo(235, 315, 2, AZUL)
rotulo(235, 276, "POST", AZUL)
rotulo(235, 291, "Idempotency-Key", AZUL)

seta([(500, 315), (590, 315)], AZUL)
passo(545, 315, 3, AZUL)
rotulo(545, 291, "+ X-Client-Id", AZUL)
rotulo(545, 346, "VPC Link → NLB", AZUL)

seta([(650, 380), (650, 450)], AZUL)
passo(650, 415, 4, AZUL)
rotulo(668, 420, "lê: já decidida?", AZUL, "start")

seta([(830, 300), (950, 300)], AZUL)
passo(890, 300, 5, AZUL)
rotulo(890, 276, "reservar limite", AZUL)
rotulo(890, 334, "timeout 200 ms", AZUL)
rotulo(890, 349, "retry", AZUL)
rotulo(890, 364, "circuit breaker", AZUL)

seta([(1070, 380), (1070, 450)], AZUL)
passo(1070, 415, 6, AZUL)
rotulo(1088, 410, "escrita condicional", AZUL, "start")
rotulo(1088, 425, "disponível ≥ valor", AZUL, "start")

seta([(780, 380), (780, 450)], AZUL)
passo(780, 415, 7, AZUL)
rotulo(798, 410, "grava decisão", AZUL, "start")
rotulo(798, 425, "+ evento", AZUL, "start")
seta([(590, 366), (500, 366)], AZUL, tracejada=True)
seta([(290, 350), (180, 350)], AZUL, tracejada=True)
rotulo(235, 372, "201 / 402", AZUL)

seta([(710, 526), (710, 620)], LARANJA)
passo(710, 573, 8, LARANJA)
rotulo(728, 578, "Stream", LARANJA, "start")
seta([(710, 690), (710, 750)], LARANJA)
rotulo(728, 724, "TransacaoAutorizada", LARANJA, "start")

seta([(710, 830), (710, 880), (415, 880), (415, 930)], LARANJA)
seta([(710, 880), (710, 930)], LARANJA)
seta([(710, 880), (990, 880), (990, 930)], LARANJA)
passo(560, 880, 9, LARANJA)
seta([(415, 1016), (415, 1050)], LARANJA)

seta([(1570, 690), (1570, 750)], LARANJA)
passo(1570, 720, 10, LARANJA)
seta([(1480, 790), (1440, 790)], LARANJA)
rotulo(1460, 742, "LimiteConcedido", LARANJA)
seta([(1330, 750), (1330, 526)], LARANJA)
rotulo(1342, 650, "regra", LARANJA, "start")
seta([(1330, 450), (1330, 300), (1190, 300)], LARANJA)
rotulo(1260, 290, "novo limite", LARANJA)

seta([(1070, 526), (1070, 620)], LARANJA)
passo(1070, 573, 11, LARANJA)
rotulo(1088, 578, "OUT#", LARANJA, "start")
seta([(1070, 690), (1070, 790), (1220, 790)], LARANJA)
rotulo(1145, 780, "LimiteAlterado", LARANJA)
seta([(1330, 830), (1330, 930)], LARANJA)

seta([(800, 250), (800, 176)], VERMELHO, tracejada=True)
passo(800, 213, 12, VERMELHO)
rotulo(818, 200, "só se incerto", VERMELHO, "start")
seta([(830, 138), (950, 138)], VERMELHO, tracejada=True)
seta([(1070, 176), (1070, 250)], VERMELHO, tracejada=True)
rotulo(1088, 218, "reverte órfã", VERMELHO, "start")

# ---- Legenda -------------------------------------------------------------------------------------------
lx, ly = 1220, 100
partes.append(f'<text x="{lx}" y="{ly}" font-size="13" font-weight="700" fill="#1d2939">Legenda</text>')
itens = [("servico", "microsserviço (ECS Fargate, Java 21)"), ("lambda", "função Lambda (Java 21)"),
         ("dados", "banco de dados (database-per-service)"), ("gerenciado", "serviço AWS gerenciado")]
for i, (tipo, texto) in enumerate(itens):
    fundo, _, borda = ESTILOS[tipo]
    yy = ly + 16 + i * 24
    partes.append(f'<rect x="{lx}" y="{yy}" width="26" height="16" rx="4" fill="{fundo}" stroke="{borda}"/>')
    partes.append(f'<text x="{lx + 36}" y="{yy + 13}" font-size="12" fill="#1d2939">{escape(texto)}</text>')
for i, (cor, tracejada, texto) in enumerate([(AZUL, False, "síncrono (request/response)"),
                                             (LARANJA, False, "evento assíncrono"),
                                             (VERMELHO, True, "exceção: resultado incerto")]):
    yy = ly + 124 + i * 22
    traco = ' stroke-dasharray="6 4"' if tracejada else ""
    partes.append(f'<line x1="{lx}" y1="{yy}" x2="{lx + 26}" y2="{yy}" stroke="{cor}" stroke-width="2.4"{traco}/>')
    partes.append(f'<text x="{lx + 36}" y="{yy + 4}" font-size="12" fill="#1d2939">{escape(texto)}</text>')

# ---- Passo a passo (texto) -----------------------------------------------------------------------------
PASSOS = [
    (1, AZUL, "Autenticação", "O canal obtém um token OAuth2 (client_credentials) no Cognito."),
    (2, AZUL, "Requisição", "POST /v1/contratos/CT-123/autorizacoes com token, x-api-key e Idempotency-Key."),
    (3, AZUL, "Borda", "WAF filtra; API Gateway valida token, cota (429) e schema (422) e encaminha."),
    (4, AZUL, "Idempotência", "id = UUIDv5(cliente, chave). Já existe? Devolve a decisão original (200)."),
    (5, AZUL, "Reserva", "Autorização pede ao Limites para debitar R$ 150 (único acoplamento síncrono)."),
    (6, AZUL, "Consistência", "Limites grava só se a versão não mudou e há saldo; senão 402 ou nova tentativa."),
    (7, AZUL, "Decisão", "Uma escrita grava a decisão e o evento juntos (outbox) e o cliente recebe 201."),
    (8, LARANJA, "Publicação", "DynamoDB Streams aciona o relay, que publica TransacaoAutorizada no Kinesis."),
    (9, LARANJA, "Consumo", "Contabilização lança no razão; antifraude, notificações e trilha S3 consomem."),
    (10, LARANJA, "Legado", "Core banking muda um limite; a ACL traduz e o Limites aplica via EventBridge."),
    (11, LARANJA, "Integração", "Mudanças de limite saem pelo outbox OUT# para o EventBridge (regras + archive)."),
    (12, VERMELHO, "Recuperação", "Se o Limites não respondeu a tempo, após 15 min o reconciliador desfaz a órfã."),
]
py = 1170
partes.append(f'<line x1="40" y1="{py}" x2="{LARGURA - 40}" y2="{py}" stroke="#d0d5dd"/>')
partes.append(f'<text x="40" y="{py + 34}" font-size="18" font-weight="700" fill="{AZUL}">Passo a passo</text>')
for i, (n, cor, titulo, texto) in enumerate(PASSOS):
    coluna, linha = divmod(i, 6)
    x = 40 + coluna * 820
    y = py + 70 + linha * 52
    passo(x + 15, y - 5, n, cor)
    partes.append(f'<text x="{x + 42}" y="{y}" font-size="14" font-weight="700" fill="{cor}">'
                  f'{escape(titulo)}</text>')
    partes.append(f'<text x="{x + 160}" y="{y}" font-size="13.5" fill="#1d2939">{escape(texto)}</text>')

# ---- Montagem ------------------------------------------------------------------------------------------
marcadores = "".join(
    f'<marker id="ponta-{cor[1:]}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
    f'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{cor}"/></marker>'
    for cor in (AZUL, LARANJA, VERMELHO))
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{LARGURA}" height="{ALTURA}" '
       f'viewBox="0 0 {LARGURA} {ALTURA}" font-family="{FONTE}"><defs>{marcadores}</defs>'
       + "\n".join(partes) + "</svg>\n")
destino = Path(__file__).with_name("fluxo-passo-a-passo.svg")
destino.write_text(svg, encoding="utf-8")
print(destino)
