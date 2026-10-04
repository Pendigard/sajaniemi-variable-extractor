"""Public command line interface."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .build_language_graphs import LANGUAGES, run_joern_parse
from tools.compare_annotations import compare_bundle
from .pipeline import normalize_language, run_pipeline
from tools.profile_extraction import profile_graphs
from .run_dynamic_extractors import DEFAULT_SCALA_SCRIPT, extract_graphs


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="sajaniemi-variable-extractor",
                                     description="Extract Sajaniemi variable roles with Joern.")
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="Parse source and produce a complete annotation bundle.")
    run.add_argument("--source", type=Path, required=True, help="Directory containing source files.")
    run.add_argument("--language", required=True, help="C, C++, C#, JavaScript, Python, or Ruby.")
    run.add_argument("--output-dir", type=Path, required=True)
    run.add_argument("--force", action="store_true", help="Rebuild an existing graph.")
    run.add_argument("--dry-run", action="store_true", help="Print the parse command without changing files.")
    run.add_argument("--scala-script", type=Path, default=DEFAULT_SCALA_SCRIPT)

    parse = commands.add_parser("parse", help="Build a Joern graph from one source tree.")
    parse.add_argument("--source", type=Path, required=True)
    parse.add_argument("--language", required=True)
    parse.add_argument("--output", type=Path, required=True, help="Path of the .bin graph.")
    parse.add_argument("--force", action="store_true")
    parse.add_argument("--dry-run", action="store_true")

    extract = commands.add_parser("extract", help="Extract annotations from existing .bin graphs.")
    extract.add_argument("--graph-dir", type=Path, required=True)
    extract.add_argument("--source", type=Path, required=True)
    extract.add_argument("--output-dir", type=Path, required=True)
    extract.add_argument("--scala-script", type=Path, default=DEFAULT_SCALA_SCRIPT)

    profile = commands.add_parser("profile", help="Profile extraction on existing graphs.")
    profile.add_argument("--graph-dir", type=Path, required=True)
    profile.add_argument("--source", type=Path, required=True)
    profile.add_argument("--report", type=Path, required=True)
    profile.add_argument("--languages", help="Comma-separated graph stems; defaults to all .bin files.")
    profile.add_argument("--scala-script", type=Path, default=DEFAULT_SCALA_SCRIPT)

    compare = commands.add_parser("compare", help="Compare two complete output bundles.")
    compare.add_argument("--before", type=Path, required=True)
    compare.add_argument("--after", type=Path, required=True)
    compare.add_argument("--report", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = create_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "run":
            run_pipeline(args.source, args.language, args.output_dir, force=args.force,
                         dry_run=args.dry_run, scala_script=args.scala_script)
        elif args.command == "parse":
            if not args.source.is_dir():
                raise ValueError(f"Source directory does not exist: {args.source}")
            run_joern_parse(args.source, args.output, normalize_language(args.language),
                            args.force, args.dry_run)
        elif args.command == "extract":
            if not args.graph_dir.is_dir():
                raise ValueError(f"Graph directory does not exist: {args.graph_dir}")
            extract_graphs(args.graph_dir, args.source, args.output_dir / "roles.json",
                           scala_script=args.scala_script,
                           jsonl_output=args.output_dir / "roles.jsonl",
                           variable_facts_output=args.output_dir / "variable_facts.json",
                           legacy_output=args.output_dir / "legacy.json",
                           keep_pre=args.output_dir / "pre.json")
        elif args.command == "profile":
            if not args.graph_dir.is_dir() or not args.source.is_dir():
                raise ValueError("Graph and source directories must exist")
            stems = ([part.strip() for part in args.languages.split(",") if part.strip()]
                     if args.languages else sorted(path.stem for path in args.graph_dir.glob("*.bin")))
            if not stems:
                raise ValueError(f"No .bin graph found in {args.graph_dir}")
            profile_graphs(args.graph_dir, args.source, args.report, stems, args.scala_script)
        elif args.command == "compare":
            if not args.before.is_dir() or not args.after.is_dir():
                raise ValueError("Both bundle directories must exist")
            report = compare_bundle(args.before, args.after)
            payload = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
            if args.report:
                args.report.parent.mkdir(parents=True, exist_ok=True)
                args.report.write_text(payload, encoding="utf-8")
            print(payload, end="")
            return 0 if report["equal"] else 1
    except ValueError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
