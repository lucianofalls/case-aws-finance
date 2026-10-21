"""Gera docs/diagramas/piramide-bian-ddd.svg — torre estilo LEGO do BIAN até o DDD.

Uso: python docs/diagramas/gerar_piramide_bian_ddd.py
Edite LAYERS para ajustar textos/cores; a ordem é da base (BIAN) para o topo.
Hierarquia BIAN validada na matriz oficial v12.0 e v13.0 (bian.org) em 2026-10-20 — ver docs/MEMORIA.md §3.
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).with_name("piramide-bian-ddd.svg")

WIDTH, HEIGHT = 2120, 1030
CENTER_X = 880
BRICK_H = 76
STUD_W, STUD_H, STUD_GAP = 18, 9, 30
CALLOUT_X, CALLOUT_W = 1650, 440
CALLOUT_CHARS = 60
FONT = "Segoe UI, Arial, sans-serif"
GREY = "#a7b1bd"

AREA, DOMAIN, ACL, CONSUMER, CORE, VOCAB = "#1b3a6b", "#3a6fb0", "#8a6bbf", "#2a9d8f", "#0d47a1", "#8dbbea"
CONTEXT, TACTICAL, SERVICE, AWS = "#f28c28", "#2e9e5b", "#1b2a4a", "#ff9900"

# (bricks, cor, cor_texto, título do callout, texto do callout, largura da fileira)
# Brick com prefixo "~" = considerado e fora do escopo / lacuna (cinza).
LAYERS = [
    (["Sales and\nService", "Operations and\nExecution", "Risk and\nCompliance", "Business\nSupport", "~Reference\nData"],
     AREA, "#ffffff", "① BIAN · Business Areas (v12/v13)",
     "As 5 áreas oficiais. 4 são tocadas pelo case; Reference Data (Product Directory) foi considerada e não usada.", 1440),
    (["Cross\nChannel", "Customer\nManagement", "Account\nManagement", "Trade\nBanking", "Loans and\nDeposits",
      "Cards", "Investment\nManagement", "Consumer\nServices", "Regulations\nand Compliance", "Document Mgmt\nand Archive"],
     DOMAIN, "#ffffff", "② BIAN · Business Domains",
     "10 dos 39 domínios. Validado: Position Keeping em Account Management; Limit and Exposure em Trade Banking.", 1330),
    (["Sales Product\nAgreement", "Current\nAccount", "Consumer\nLoan", "Credit\nCard", "Investment\nAccount",
      "Brokered Product\n(seguros)", "~Consórcio\n(sem SD BIAN)"],
     ACL, "#ffffff", "③ Service Domains · legado via ACL",
     "O \"contrato\" do PDF e os produtos do core banking. Consórcio não tem SD no BIAN: lacuna documentada.", 1220),
    (["Fraud Evaluation\n→ antifraude", "Correspondence\n→ notificações", "Customer Event History\n→ data platform",
      "Archive Services\n→ auditoria"],
     CONSUMER, "#ffffff", "④ Service Domains · consumidores de eventos",
     "Consomem TransacaoAutorizada de forma desacoplada. \"Fraud Detection\" não existe: o SD é Fraud Evaluation.", 1110),
    (["Transaction\nAuthorization\n→ Autorização", "Position\nKeeping\n→ Limites", "Limit and Exposure\nManagement\n→ Limites",
      "Financial\nAccounting\n→ Contabilização"],
     CORE, "#ffffff", "⑤ Service Domains · núcleo (implementados)",
     "PK \"maintains positions… includes limits and blocks\": a reserva de limite é dele, por isso vai para Limites.", 1000),
    (["CR Interactive\nTransaction Assessment", "CR Financial Position Log\nBQ Fin. Tx Capture",
      "Position Limit Value\nAmount Block", "CR Financial Booking Log\nBQ Ledger Posting"],
     VOCAB, "#0f2747", "⑥ BIAN · Control Records · BQs · Action Terms",
     "Vocabulário oficial. Operações: Evaluate (TA), Control e Capture (PK), Capture LedgerPosting (FA).", 890),
    "PLATE",
    (["Autorização\n(Core)", "Limites\n(Core)", "Contabilização\n(Supporting)", "ACL Core Banking\n(Integração)"],
     CONTEXT, "#ffffff", "⑦ DDD Estratégico · Bounded Contexts",
     "3 BCs + ACL. Customer/Supplier (Autorização → Limites), Published Language (eventos), ACL (legado).", 780),
    (["Agregado\nAutorizacao", "LimiteContrato\nReserva · Movimento", "RegistroContabil\nLancamento",
      "Domain Events\nTransacaoAutorizada"],
     TACTICAL, "#ffffff", "⑧ DDD Tático",
     "Invariante disponível ≥ 0 no agregado; entidades do BIAN em PT-BR; eventos no passado → outbox.", 670),
    (["autorizacao\n(Fargate)", "limites\n(Fargate)", "contabilizacao\n(Fargate)", "corebanking-acl\n(Lambda)"],
     SERVICE, "#ffffff", "⑨ Microsserviços · Hexagonal",
     "4 deployables, database-per-service, ports & adapters isolando AWS SDK e frameworks.", 560),
    (["AWS Well-Architected"],
     AWS, "#1b2a4a", "⑩ AWS Well-Architected",
     "Cada bloco implantado seguindo os 6 pilares + FSI Lens (ADR 000), validados na doc AWS.", 300),
]

PLATE_H = 30
PLATE_TEXT = "Tradução BIAN → linguagem ubíqua (PT-BR)"

GROUPS = [  # (rótulo, índice da primeira e da última camada em LAYERS, cor)
    ("BIAN v12/v13 · validado", 0, 5, DOMAIN),
    ("DDD", 7, 8, CONTEXT),
    ("Implementação", 9, 10, SERVICE),
]

LEGEND = [
    (CORE, "SD núcleo (implementado)"),
    (ACL, "SD do legado (via ACL)"),
    (CONSUMER, "SD consumidor de eventos"),
    (GREY, "considerado / fora do escopo / lacuna"),
]


def shade(hex_color: str, factor: float) -> str:
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    clamp = lambda v: max(0, min(255, int(v)))
    return "#{:02x}{:02x}{:02x}".format(*(clamp(c * factor) for c in (r, g, b)))


def text_block(x: float, y_center: float, label: str, color: str, size: int) -> str:
    lines = label.split("\n")
    line_h = size * 1.2
    first_y = y_center - (len(lines) - 1) * line_h / 2 + size * 0.35
    tspans = "".join(
        f'<tspan x="{x:.1f}" y="{first_y + i * line_h:.1f}">{escape(line)}</tspan>'
        for i, line in enumerate(lines)
    )
    return (f'<text font-family="{FONT}" font-size="{size}" font-weight="600" '
            f'fill="{color}" text-anchor="middle">{tspans}</text>')


def wrap(text: str, max_chars: int) -> list[str]:
    lines, current = [], ""
    for word in text.split():
        if current and len(current) + 1 + len(word) > max_chars:
            lines.append(current)
            current = word
        else:
            current = f"{current} {word}".strip()
    return lines + [current] if current else lines


def brick(x: float, y: float, w: float, color: str, text_color: str, label: str, font: int) -> str:
    if label.startswith("~"):
        label, color, text_color = label[1:], GREY, "#ffffff"
    dark, light = shade(color, 0.72), shade(color, 1.18)
    parts = []
    stud_count = max(1, int((w - 12) // STUD_GAP))
    offset = x + (w - (stud_count - 1) * STUD_GAP) / 2
    for i in range(stud_count):
        sx = offset + i * STUD_GAP - STUD_W / 2
        parts.append(f'<rect x="{sx:.1f}" y="{y - STUD_H:.1f}" width="{STUD_W}" height="{STUD_H + 2}" rx="3" '
                     f'fill="{color}" stroke="{dark}" stroke-width="1"/>')
        parts.append(f'<rect x="{sx + 3:.1f}" y="{y - STUD_H + 2:.1f}" width="{STUD_W - 10}" height="3" rx="1.5" '
                     f'fill="{light}" opacity="0.8"/>')
    parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{BRICK_H}" rx="5" '
                 f'fill="{color}" stroke="{dark}" stroke-width="1.5"/>')
    parts.append(f'<rect x="{x + 2:.1f}" y="{y + BRICK_H - 10:.1f}" width="{w - 4:.1f}" height="8" rx="3" '
                 f'fill="{dark}" opacity="0.55"/>')
    parts.append(f'<rect x="{x + 6:.1f}" y="{y + 4:.1f}" width="{w - 12:.1f}" height="3" rx="1.5" '
                 f'fill="{light}" opacity="0.6"/>')
    parts.append(text_block(x + w / 2, y + BRICK_H / 2 - 3, label, text_color, font))
    return "\n".join(parts)


def callout(y_center: float, row_right: float, title: str, body: str, color: str) -> str:
    body_lines = wrap(body, CALLOUT_CHARS)
    if len(body_lines) > 2:
        raise ValueError(f"callout '{title}' passa de 2 linhas e sobrepõe a fileira vizinha: encurte o texto")
    box_h = 30 + 18 * len(body_lines)
    top = y_center - box_h / 2
    tspans = "".join(
        f'<tspan x="{CALLOUT_X + 14}" y="{top + 44 + i * 18:.1f}">{escape(line)}</tspan>'
        for i, line in enumerate(body_lines)
    )
    return "\n".join([
        f'<line x1="{row_right + 8:.1f}" y1="{y_center:.1f}" x2="{CALLOUT_X:.1f}" y2="{y_center:.1f}" '
        f'stroke="{color}" stroke-width="1.5" stroke-dasharray="5 4"/>',
        f'<circle cx="{row_right + 8:.1f}" cy="{y_center:.1f}" r="4" fill="{color}"/>',
        f'<rect x="{CALLOUT_X}" y="{top:.1f}" width="{CALLOUT_W}" height="{box_h}" rx="8" '
        f'fill="#ffffff" stroke="{color}" stroke-width="2"/>',
        f'<rect x="{CALLOUT_X}" y="{top:.1f}" width="6" height="{box_h}" rx="3" fill="{color}"/>',
        f'<text x="{CALLOUT_X + 14}" y="{top + 22:.1f}" font-family="{FONT}" '
        f'font-size="15" font-weight="700" fill="#1b2a4a">{escape(title)}</text>',
        f'<text font-family="{FONT}" font-size="13" fill="#33415c">{tspans}</text>',
    ])


def legend(x: float, y: float) -> str:
    parts, cursor = [], x
    for color, label in LEGEND:
        parts.append(f'<rect x="{cursor:.1f}" y="{y - 12:.1f}" width="22" height="14" rx="3" fill="{color}"/>')
        parts.append(f'<text x="{cursor + 30:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="14" '
                     f'fill="#33415c">{escape(label)}</text>')
        cursor += 60 + len(label) * 7.4
    return "\n".join(parts)


def main() -> None:
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" '
           f'viewBox="0 0 {WIDTH} {HEIGHT}">',
           f'<rect width="{WIDTH}" height="{HEIGHT}" fill="#f6f8fb"/>',
           f'<text x="60" y="58" font-family="{FONT}" font-size="30" font-weight="700" '
           'fill="#1b2a4a">Do BIAN ao DDD: a torre da plataforma de Limites e Autorização</text>',
           f'<text x="60" y="88" font-family="{FONT}" font-size="16" fill="#55627a">'
           'Itaú POD99 · BIAN Service Landscape v12/v13 (validado em bian.org) · cada camada se apoia na anterior</text>',
           legend(60, 124)]

    # Monta de baixo para cima: cada camada nova cobre os pinos da anterior.
    y_bottom = HEIGHT - 40
    layer_y: dict[int, tuple[float, float]] = {}
    for index, layer in enumerate(LAYERS):
        if layer == "PLATE":
            plate_w = LAYERS[index - 1][5] - 40
            top = y_bottom - PLATE_H
            svg.append(f'<rect x="{CENTER_X - plate_w / 2:.1f}" y="{top:.1f}" width="{plate_w}" '
                       f'height="{PLATE_H}" rx="4" fill="#ec7000" stroke="#b55600" stroke-width="1.5"/>')
            svg.append(text_block(CENTER_X, top + PLATE_H / 2, f"▲  {PLATE_TEXT}  ▲", "#ffffff", 14))
            y_bottom = top
            continue
        labels, color, text_color, title, body, row_w = layer
        top = y_bottom - BRICK_H
        brick_w = row_w / len(labels)
        left = CENTER_X - row_w / 2
        font = 14 if brick_w >= 200 else 13 if brick_w >= 140 else 12
        for i, label in enumerate(labels):
            svg.append(brick(left + i * brick_w + 2, top, brick_w - 4, color, text_color, label, font))
        svg.append(callout(top + BRICK_H / 2, CENTER_X + row_w / 2, title, body, color))
        layer_y[index] = (top, top + BRICK_H)
        y_bottom = top - 2

    for label, first, last, color in GROUPS:
        y1, y2 = layer_y[last][0], layer_y[first][1]
        x = 70
        svg.append(f'<path d="M{x + 18},{y1} L{x},{y1} L{x},{y2} L{x + 18},{y2}" fill="none" '
                   f'stroke="{color}" stroke-width="3"/>')
        mid = (y1 + y2) / 2
        svg.append(f'<text x="{x - 14}" y="{mid:.1f}" transform="rotate(-90 {x - 14} {mid:.1f})" '
                   f'font-family="{FONT}" font-size="16" font-weight="700" '
                   f'fill="{color}" text-anchor="middle">{escape(label)}</text>')

    svg.append("</svg>")
    OUT.write_text("\n".join(svg), encoding="utf-8")
    print(f"gerado: {OUT}")


if __name__ == "__main__":
    main()
