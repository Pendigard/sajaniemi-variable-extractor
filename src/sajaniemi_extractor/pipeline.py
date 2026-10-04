"""Callable high-level extraction workflow."""

from __future__ import annotations

from pathlib import Path

from .build_language_graphs import LANGUAGES, run_joern_parse
from .run_dynamic_extractors import DEFAULT_SCALA_SCRIPT, extract_graphs


def normalize_language(value: str) -> str:
    aliases = {name.casefold(): name for name in LANGUAGES}
    aliases.update({config.output_stem: name for name, config in LANGUAGES.items()})
    try:
        return aliases[value.casefold()]
    except KeyError as error:
        raise ValueError(f"Unsupported language {value!r}. Choose from: {', '.join(LANGUAGES)}") from error


def run_pipeline(source: Path, language: str, output_dir: Path, *, force: bool = False,
                 dry_run: bool = False, scala_script: Path = DEFAULT_SCALA_SCRIPT) -> Path:
    """Parse one source tree, extract roles, and write a comparable output bundle."""
    if not source.is_dir():
        raise ValueError(f"Source directory does not exist: {source}")
    language = normalize_language(language)
    graph = output_dir / "graphs" / f"{LANGUAGES[language].output_stem}.bin"
    run_joern_parse(source, graph, language, force, dry_run)
    if not dry_run:
        extract_graphs(graph.parent, source, output_dir / "roles.json",
                       scala_script=scala_script,
                       jsonl_output=output_dir / "roles.jsonl",
                       variable_facts_output=output_dir / "variable_facts.json",
                       legacy_output=output_dir / "legacy.json",
                       keep_pre=output_dir / "pre.json")
    return graph
