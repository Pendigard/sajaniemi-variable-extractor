#!/usr/bin/env python3
"""Build one Joern graph per language for train/test source directories."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class LanguageConfig:
    joern_language: str
    output_stem: str


LANGUAGES: dict[str, LanguageConfig] = {
    "C": LanguageConfig("c", "c"),
    "C++": LanguageConfig("c", "cpp"),
    "C#": LanguageConfig("csharpsrc", "csharp"),
    # "Java": LanguageConfig("javasrc", "java"),
    "JavaScript": LanguageConfig("javascript", "javascript"),
    "Python": LanguageConfig("pythonsrc", "python"),
    "Ruby": LanguageConfig("rubysrc", "ruby"),
}


def has_source_files(path: Path) -> bool:
    return any(child.is_file() for child in path.rglob("*"))


def output_path(output_dir: Path, split: str, language: str) -> Path:
    return output_dir / split / f"{LANGUAGES[language].output_stem}.bin"


def run_joern_parse(source_dir: Path, output: Path, language: str, force: bool, dry_run: bool) -> None:
    config = LANGUAGES[language]
    if output.exists():
        if not force:
            print(f"[skip] {output} already exists (use --force to rebuild)")
            return
        output.unlink()

    output.parent.mkdir(parents=True, exist_ok=True)
    print(f"[joern-parse] {source_dir} -> {output} ({config.joern_language})")
    if dry_run:
        return

    parse_source = source_dir.resolve()
    output = output.resolve()
    if language == "Ruby":
        # rubysrc2cpg may silently respect the enclosing Git worktree's ignore
        # rules. The research corpora live under ignored /code, so parse an
        # isolated copy while keeping the original tree untouched.
        with tempfile.TemporaryDirectory(prefix="sajaniemi-ruby-source-") as directory:
            staged_source = Path(directory) / source_dir.name
            shutil.copytree(parse_source, staged_source)
            subprocess.run(
                [
                    "joern-parse",
                    str(staged_source),
                    "--output",
                    str(output),
                    "--language",
                    config.joern_language,
                ],
                check=True,
            )
        return

    subprocess.run(
        [
            "joern-parse",
            str(parse_source),
            "--output",
            str(output),
            "--language",
            config.joern_language,
        ],
        check=True,
    )


def build_graphs(code_root: Path, output_dir: Path, splits: list[str], force: bool, dry_run: bool) -> None:
    for split in splits:
        split_dir = code_root / split
        if not split_dir.is_dir():
            print(f"[skip] missing split directory: {split_dir}")
            continue

        for language, config in LANGUAGES.items():
            source_dir = split_dir / language
            if not source_dir.is_dir():
                print(f"[skip] missing language directory: {source_dir}")
                continue
            if not has_source_files(source_dir):
                print(f"[skip] empty language directory: {source_dir}")
                continue

            run_joern_parse(
                source_dir=source_dir,
                output=output_path(output_dir, split, language),
                language=language,
                force=force,
                dry_run=dry_run,
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate one Joern .bin graph per language from code/train and/or code/test."
    )
    parser.add_argument("--code-root", type=Path, default=Path("code"), help="Root containing train/ and test/.")
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory where graph .bin files are written.")
    parser.add_argument(
        "--splits",
        nargs="+",
        default=["train", "test"],
        choices=["train", "test"],
        help="Split directories to parse.",
    )
    parser.add_argument("--force", action="store_true", help="Rebuild graphs even when output .bin files already exist.")
    parser.add_argument("--dry-run", action="store_true", help="Print joern-parse commands without running them.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    build_graphs(
        code_root=args.code_root,
        output_dir=args.output_dir,
        splits=args.splits,
        force=args.force,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()
