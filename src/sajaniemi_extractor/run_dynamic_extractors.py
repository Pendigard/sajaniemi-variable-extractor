#!/usr/bin/env python3
"""Run the batch extractor and resolve minimal variable-aware views."""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from .paths import scala_script_path
from sajaniemi_extractor.variable_aware import (
    legacy_annotations_from_roles,
    resolve_variable_aware_output,
    write_json,
)


def run_joern(graph: Path, scala_script: Path, output: Path, source_root: Path) -> None:
    print(f"[joern] Running {scala_script} on {graph} -> {output}")
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
    subprocess.run(cmd, check=True, cwd=output.parent)


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
    if not code_root.is_dir():
        raise ValueError(f"Source directory does not exist: {code_root}")
    if not scala_script.is_file():
        raise ValueError(f"Scala script does not exist: {scala_script}")
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="dynamic-joern-") as tmp:
        tmpdir = Path(tmp)
        pre_files: list[Path] = []
        for graph in graphs:
            pre = tmpdir / f"{graph.stem}.pre.json"
            print(f"[joern] {graph} -> {pre}")
            run_joern(graph, scala_script, pre, code_root)
            pre_files.append(pre)

        merged_pre = keep_pre or (tmpdir / "merged_pre_annotations.json")
        merged = {"schema_version": 1, "role_annotations": [], "variable_facts": []}
        for pre in pre_files:
            graph_output = json.loads(pre.read_text(encoding="utf-8"))
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
        print(f"Wrote {len(role_annotations)} role annotations to {output}")
        print(f"Wrote {len(role_annotations)} role annotations to {jsonl_output}")
        print(f"Wrote {len(variable_facts)} variable facts to {facts_output}")
        if legacy_output:
            legacy = legacy_annotations_from_roles(role_annotations)
            write_json(legacy_output, legacy)
            print(f"Wrote {len(legacy)} converted legacy annotations to {legacy_output}")
        if keep_pre:
            print(f"Kept merged pre-annotations in {keep_pre}")


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
