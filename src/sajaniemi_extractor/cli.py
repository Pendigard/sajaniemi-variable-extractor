"""Public command line interface."""

from __future__ import annotations

import argparse
import json
import warnings
from collections import defaultdict
from pathlib import Path

from tqdm import tqdm

from .build_language_graphs import LANGUAGES, run_joern_parse
from tools.compare_annotations import compare_bundle
from .pipeline import normalize_language
from .source_layout import SourceJob, discover_sources
from tools.profile_extraction import profile_graphs
from .run_dynamic_extractors import DEFAULT_SCALA_SCRIPT, extract_graphs, extract_group


def _run_jobs(args: argparse.Namespace, jobs: list[SourceJob]) -> None:
    groups: dict[tuple[str, ...], list[SourceJob]] = defaultdict(list)
    for job in jobs:
        groups[job.output_parts[:-1] if job.output_parts else ()].append(job)

    completed_groups = 0
    for group_parts, group_jobs in groups.items():
        source_root = args.source.joinpath(*group_parts)
        output_dir = args.output_dir.joinpath(*group_parts)
        graph_sources: list[tuple[Path, Path]] = []
        label = "/".join(group_parts) or args.source.name
        with tqdm(total=len(group_jobs) + (0 if args.dry_run else 1), desc=label,
                  unit="step", dynamic_ncols=True) as progress:
            for job in group_jobs:
                stem = job.output_parts[-1] if job.output_parts else LANGUAGES[job.language].output_stem
                graph = output_dir / "graphs" / f"{stem}.bin"
                progress.set_postfix_str(f"parse {job.source.name}")
                try:
                    run_joern_parse(job.source, graph, job.language, args.force, args.dry_run)
                    graph_sources.append((graph, job.source))
                except (ValueError, RuntimeError, OSError) as error:
                    if len(jobs) == 1 and not job.output_parts:
                        raise
                    warnings.warn(f"Skipping {job.source}: {error}", stacklevel=2)
                finally:
                    progress.update(1)

            if graph_sources and not args.dry_run:
                progress.set_postfix_str("extract annotations")
                try:
                    extract_group(graph_sources, source_root, output_dir / "roles.json",
                                  scala_script=args.scala_script,
                                  jsonl_output=output_dir / "roles.jsonl",
                                  variable_facts_output=output_dir / "variable_facts.json",
                                  legacy_output=output_dir / "legacy.json",
                                  keep_pre=output_dir / "pre.json")
                    completed_groups += 1
                except (ValueError, RuntimeError, OSError) as error:
                    if len(groups) == 1:
                        raise
                    warnings.warn(f"Skipping split {source_root}: {error}", stacklevel=2)
                finally:
                    progress.update(1)
            elif graph_sources:
                completed_groups += 1
            elif not args.dry_run:
                progress.update(1)

    if not completed_groups:
        raise ValueError("No source repositories could be extracted")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="sajaniemi-variable-extractor",
                                     description="Extract Sajaniemi variable roles with Joern.")
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="Parse source and produce a complete annotation bundle.")
    run.add_argument("--source", type=Path, required=True, help="Directory containing source files.")
    run.add_argument("--language", help="Optional language check for a single source tree: C, C++, JavaScript, Python, or Ruby.")
    run.add_argument("--layout", choices=("auto", "single", "repos", "splits"), default="auto",
                     help="Input layout (default: detect automatically). Set explicitly if directory nesting is ambiguous.")
    run.add_argument("--output-dir", type=Path, default=Path.cwd(),
                     help="Destination root (default: current working directory).")
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
            language = normalize_language(args.language) if args.language else None
            jobs = discover_sources(args.source, args.layout, language)
            if args.language:
                if len(jobs) != 1 or jobs[0].output_parts:
                    raise ValueError("--language applies only to a single source tree")
            _run_jobs(args, jobs)
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
    except (ValueError, RuntimeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
