#!/usr/bin/env python3
"""Verificação local e somente leitura dos artefatos do case, sem AWS/CI.

Uso: python3 local/verificar-estatico.py
Limites: NÃO compila Java, NÃO executa terraform validate, NÃO testa Docker/LocalStack.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
ERROS: list[str] = []
CONTAGENS: dict[str, int] = {}


def verificar_tipo(padrao: str, nome: str, fn) -> None:
    arquivos = sorted(ROOT.rglob(padrao))
    for arquivo in arquivos:
        try:
            fn(arquivo)
        except Exception as exc:
            ERROS.append(f"{arquivo.relative_to(ROOT)}: {exc}")
    CONTAGENS[nome] = len(arquivos)


def verificar_bash(arquivo: Path) -> None:
    proc = subprocess.run(["bash", "-n", str(arquivo)], text=True,
                          capture_output=True, check=False)
    if proc.returncode:
        raise RuntimeError(proc.stderr.strip())


def main() -> int:
    verificar_tipo("*.json", "JSON", lambda p: json.loads(p.read_text(encoding="utf-8")))
    verificar_tipo("*.xml", "XML", ElementTree.parse)
    verificar_tipo("*.svg", "SVG", ElementTree.parse)
    verificar_tipo("*.py", "Python", lambda p: compile(p.read_bytes(), str(p), "exec"))
    verificar_tipo("*.sh", "Bash", verificar_bash)
    verificar_tipo("*.png", "PNG", lambda p: verificar_png(p))

    try:
        import yaml
    except ImportError:
        print("AVISO: PyYAML não instalado; validação YAML ignorada (pip install pyyaml)")
    else:
        for padrao in ("*.yaml", "*.yml"):
            verificar_tipo(padrao, padrao, lambda p: yaml.safe_load(p.read_text(encoding="utf-8")))

    obrigatorios = [
        "pom.xml", "COMO-RODAR.md", "local/subir.sh", "local/demo.sh",
        "local/derrubar.sh", "local/docker-compose.yml",
        "servicos/autorizacao-service/pom.xml", "servicos/limites-service/pom.xml",
        "servicos/contabilizacao-service/pom.xml",
        "infra/terraform/ambientes/local/main.tf",
        "infra/terraform/ambientes/prod/main.tf",
        "contracts/openapi/autorizacoes-v1.yaml",
        "contracts/asyncapi/financas-eventos.yaml",
        "docs/adr/README.md",
    ]
    for relativo in obrigatorios:
        if not (ROOT / relativo).is_file():
            ERROS.append(f"Arquivo obrigatório ausente: {relativo}")

    if (ROOT / ".github" / "workflows").exists():
        ERROS.append("GitHub Actions existe nesta pasta: operação NÃO autorizada")

    print("Validação estática local (não provisiona recursos AWS):")
    for nome, quantidade in CONTAGENS.items():
        print(f"  {nome}: {quantidade} arquivo(s) verificados")
    if ERROS:
        print("FALHAS:", *ERROS, sep="\n - ", file=sys.stderr)
        return 1
    print("OK: estrutura e sintaxe dos formatos verificados")
    print("PENDENTE: Maven/JUnit, Docker/LocalStack E2E, Terraform validate/plan e testes de carga")
    return 0


def verificar_png(arquivo: Path) -> None:
    with arquivo.open("rb") as f:
        if f.read(8) != b"\x89PNG\r\n\x1a\n":
            raise ValueError("assinatura PNG inválida")


if __name__ == "__main__":
    sys.exit(main())