"""Stable access to the modular Scala extractor sources for contract tests."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCALA_ROOT = ROOT / "src/scala"


def scala_source(relative_path: str) -> str:
    path = SCALA_ROOT / relative_path
    if not path.is_file():
        raise AssertionError(f"missing Scala source: {path}")
    return path.read_text(encoding="utf-8")


def all_scala_sources() -> str:
    paths = sorted(SCALA_ROOT.rglob("*.sc"), key=lambda path: path.relative_to(SCALA_ROOT).as_posix())
    return "\n".join(path.read_text(encoding="utf-8") for path in paths)
