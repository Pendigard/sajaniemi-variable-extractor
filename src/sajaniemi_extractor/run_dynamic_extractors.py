#!/usr/bin/env python3
"""Run the batch extractor and resolve minimal variable-aware views."""

from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

from .paths import scala_script_path
from .build_language_graphs import _run_quietly
from .resolve_spans import build_source_index
from sajaniemi_extractor.variable_aware import (
    legacy_annotations_from_roles,
    resolve_variable_aware_output,
    write_json,
)


def run_joern(graph: Path, scala_script: Path, output: Path, source_root: Path) -> None:
    cmd = [
        "joern",
        str(graph.resolve()),
        "--script",
        str(scala_script.resolve()),
        "--param",
        f"output={output.resolve()}",
        "--param",
        f"sourceRoot={source_root.resolve()}",
        "--nocolors",
    ]
    _run_quietly(cmd, cwd=output.parent)


def write_jsonl(path: Path, annotations: list[dict]) -> None:
    lines = (json.dumps(annotation, ensure_ascii=False) for annotation in annotations)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + ("\n" if annotations else ""), encoding="utf-8")


DEFAULT_SCALA_SCRIPT = scala_script_path()


def extract_graphs(
    graph_dir: Path,
    code_root: Path,
    output: Path,
    *,
    scala_script: Path = DEFAULT_SCALA_SCRIPT,
    jsonl_output: Path | None = None,
    variable_facts_output: Path | None = None,
    legacy_output: Path | None = None,
    keep_pre: Path | None = None,
) -> None:
    """Run Scala extraction, resolve source spans, and write the output bundle."""
    graphs = sorted(graph_dir.glob("*.bin"))
    if not graphs:
        raise ValueError(f"No .bin graph found in {graph_dir}.")
    extract_group([(graph, code_root) for graph in graphs], code_root, output,
                  scala_script=scala_script, jsonl_output=jsonl_output,
                  variable_facts_output=variable_facts_output,
                  legacy_output=legacy_output, keep_pre=keep_pre)


def _prefix_raw_paths(value: object, group_root: Path, source_index: dict[str, Path]) -> None:
    """Rebase Joern's repository-local paths before resolving a grouped split."""
    if isinstance(value, list):
        for item in value:
            _prefix_raw_paths(item, group_root, source_index)
    elif isinstance(value, dict):
        for key, item in value.items():
            if key == "path" and isinstance(item, str) and item:
                source_file = source_index.get(item) or source_index.get(Path(item).name)
                if source_file is not None:
                    value[key] = source_file.relative_to(group_root).as_posix()
            else:
                _prefix_raw_paths(item, group_root, source_index)


def extract_group(
    graph_sources: list[tuple[Path, Path]],
    code_root: Path,
    output: Path,
    *,
    scala_script: Path = DEFAULT_SCALA_SCRIPT,
    jsonl_output: Path | None = None,
    variable_facts_output: Path | None = None,
    legacy_output: Path | None = None,
    keep_pre: Path | None = None,
) -> None:
    """Extract several repository graphs into one bundle for their shared root."""
    if not graph_sources:
        raise ValueError("No graphs to extract")
    if not code_root.is_dir():
        raise ValueError(f"Source directory does not exist: {code_root}")
    code_root = code_root.resolve()
    if not scala_script.is_file():
        raise ValueError(f"Scala script does not exist: {scala_script}")
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="dynamic-joern-") as tmp:
        tmpdir = Path(tmp)
        merged_pre = keep_pre or (tmpdir / "merged_pre_annotations.json")
        merged = {"schema_version": 1, "role_annotations": [], "variable_facts": []}
        for index, (graph, source_root) in enumerate(graph_sources):
            source_root = source_root.resolve()
            pre = tmpdir / f"{index}.pre.json"
            run_joern(graph, scala_script, pre, source_root)
            graph_output = json.loads(pre.read_text(encoding="utf-8"))
            if source_root != code_root:
                source_index = build_source_index(source_root)
                _prefix_raw_paths(graph_output, code_root, source_index)
                prefix = source_root.relative_to(code_root).as_posix()
                for record in graph_output["role_annotations"] + graph_output["variable_facts"]:
                    subject = record.get("subject", {})
                    if isinstance(subject.get("id"), str):
                        subject["id"] = f"{prefix}/{subject['id']}"
            merged["role_annotations"].extend(graph_output["role_annotations"])
            merged["variable_facts"].extend(graph_output["variable_facts"])
        write_json(merged_pre, merged)

        resolved = resolve_variable_aware_output(merged_pre, code_root)
        role_annotations = resolved["role_annotations"]
        variable_facts = resolved["variable_facts"]
        write_json(output, role_annotations)
        facts_output = variable_facts_output or output.with_name(
            f"{output.stem}.variable_facts.json"
        )
        write_json(facts_output, variable_facts)
        jsonl_output = jsonl_output or output.with_suffix(".jsonl")
        write_jsonl(jsonl_output, role_annotations)
        if legacy_output:
            legacy = legacy_annotations_from_roles(role_annotations)
            write_json(legacy_output, legacy)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--graph-dir", type=Path, default=Path("graph"))
    parser.add_argument("--code-root", type=Path, default=Path("code"))
    parser.add_argument("--scala-script", type=Path, default=DEFAULT_SCALA_SCRIPT)
    parser.add_argument("--output", type=Path, default=Path("dynamic_annotations.json"))
    parser.add_argument("--jsonl-output", type=Path)
    parser.add_argument("--variable-facts-output", type=Path)
    parser.add_argument("--legacy-output", type=Path)
    parser.add_argument("--keep-pre", type=Path)
    args = parser.parse_args()
    extract_graphs(args.graph_dir, args.code_root, args.output, scala_script=args.scala_script,
                   jsonl_output=args.jsonl_output, variable_facts_output=args.variable_facts_output,
                   legacy_output=args.legacy_output, keep_pre=args.keep_pre)


if __name__ == "__main__":
    main()
