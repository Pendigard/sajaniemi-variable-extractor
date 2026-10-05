#!/usr/bin/env python3
"""Build Joern graphs for supported source directories."""

from __future__ import annotations

import argparse
import shlex
import shutil
import subprocess
import tempfile
import uuid
import warnings
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class LanguageConfig:
    joern_language: str
    output_stem: str


LANGUAGES: dict[str, LanguageConfig] = {
    "C": LanguageConfig("c", "c"),
    "C++": LanguageConfig("c", "cpp"),
    "JavaScript": LanguageConfig("javascript", "javascript"),
    "Python": LanguageConfig("pythonsrc", "python"),
    "Ruby": LanguageConfig("rubysrc", "ruby"),
}


def run_joern_parse(source_dir: Path, output: Path, language: str, force: bool, dry_run: bool) -> None:
    config = LANGUAGES[language]
    if output.exists():
        if not force:
            print(f"[skip] {output} already exists (use --force to rebuild)")
            return
    parse_source = source_dir.resolve()
    output = output.resolve()
    command = ["joern-parse", str(parse_source), "--output", str(output), "--language", config.joern_language]
    staging_dir = None
    if language == "Ruby":
        staging_dir = Path(tempfile.gettempdir()) / f"sajaniemi-ruby-source-{uuid.uuid4().hex}"
        command[1] = str(staging_dir / source_dir.name)
    if dry_run:
        if staging_dir is not None:
            print(f"[dry-run] copy {parse_source} to {command[1]}")
        print(f"[dry-run] {shlex.join(command)}")
        return

    if output.exists():
        output.unlink()
    output.parent.mkdir(parents=True, exist_ok=True)
    if language == "Ruby":
        # rubysrc2cpg may silently respect the enclosing Git worktree's ignore
        # rules. The research corpora live under ignored /code, so parse an
        # isolated copy while keeping the original tree untouched.
        assert staging_dir is not None
        staging_dir.mkdir()
        try:
            shutil.copytree(parse_source, staging_dir / source_dir.name)
            _run_quietly(command)
        finally:
            shutil.rmtree(staging_dir)
        return

    _run_quietly(command)


def _run_quietly(command: list[str], *, cwd: Path | None = None) -> None:
    """Keep Joern chatter hidden on success and include it in failures."""
    try:
        subprocess.run(command, check=True, cwd=cwd, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True)
    except subprocess.CalledProcessError as error:
        detail = (error.stdout or "").strip()
        raise RuntimeError(f"Joern command failed: {shlex.join(command)}\n{detail}") from error


def build_graphs(code_root: Path, output_dir: Path, force: bool, dry_run: bool,
                 layout: str = "auto") -> None:
    from .source_layout import discover_sources

    jobs = discover_sources(code_root, layout)
    succeeded = 0
    for job in jobs:
        group_parts = job.output_parts[:-1] if job.output_parts else ()
        stem = job.output_parts[-1] if job.output_parts else LANGUAGES[job.language].output_stem
        graph = output_dir.joinpath(*group_parts) / "graphs" / f"{stem}.bin"
        try:
            run_joern_parse(job.source, graph, job.language, force, dry_run)
            succeeded += 1
        except (ValueError, RuntimeError, OSError) as error:
            if len(jobs) == 1 and not job.output_parts:
                raise
            warnings.warn(f"Skipping {job.source}: {error}", stacklevel=2)
    if not succeeded:
        raise ValueError("No source repositories could be parsed")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate Joern graphs from one source tree, repositories, or named splits."
    )
    parser.add_argument("--code-root", type=Path, required=True, help="Input source root.")
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory where graph .bin files are written.")
    parser.add_argument("--layout", choices=("auto", "single", "repos", "splits"), default="auto")
    parser.add_argument("--force", action="store_true", help="Rebuild graphs even when output .bin files already exist.")
    parser.add_argument("--dry-run", action="store_true", help="Print joern-parse commands without running them.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    build_graphs(
        code_root=args.code_root,
        output_dir=args.output_dir,
        force=args.force,
        dry_run=args.dry_run,
        layout=args.layout,
    )


if __name__ == "__main__":
    main()
