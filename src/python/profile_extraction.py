#!/usr/bin/env python3
"""Profile one or more cached Joern graphs without changing annotation output."""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

from src.python.variable_aware import legacy_annotations_from_roles, resolve_variable_aware_output


ROOT = Path(__file__).resolve().parents[2]
PROFILE_PREFIXES = ("SAJANIEMI_SCALA_PROFILE=", "SAJANIEMI_PYTHON_PROFILE=")
TARGET_ROLES = {"follower", "temporary", "one_way_flag", "gatherer"}


def canonical_digest(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def process_tree_rss_bytes(root_pid: int) -> int | None:
    """Sample aggregate RSS for a process tree using the portable macOS/Linux ps columns."""
    try:
        result = subprocess.run(
            ["ps", "-axo", "pid=,ppid=,rss="],
            check=False,
            capture_output=True,
            text=True,
        )
    except PermissionError:
        return None
    if result.returncode != 0:
        return None
    rows: dict[int, tuple[int, int]] = {}
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) != 3:
            continue
        try:
            pid, parent, rss_kib = map(int, fields)
        except ValueError:
            continue
        rows[pid] = (parent, rss_kib)
    if root_pid not in rows:
        return None
    descendants = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, (parent, _) in rows.items():
            if parent in descendants and pid not in descendants:
                descendants.add(pid)
                changed = True
    return sum(rows.get(pid, (0, 0))[1] for pid in descendants) * 1024


def parse_profiles(stderr: str) -> list[dict[str, Any]]:
    profiles: list[dict[str, Any]] = []
    for line in stderr.splitlines():
        for prefix in PROFILE_PREFIXES:
            if line.startswith(prefix):
                profiles.append(json.loads(line[len(prefix):]))
    return profiles


def run_joern(graph: Path, code_root: Path, scala_script: Path, pre_output: Path) -> dict[str, Any]:
    environment = os.environ.copy()
    environment["SAJANIEMI_PROFILE"] = "1"
    command = [
        "joern", str(graph), "--script", str(scala_script),
        "--param", f"output={pre_output}", "--param", f"sourceRoot={code_root}", "--nocolors",
    ]
    with tempfile.TemporaryDirectory(prefix="sajaniemi-profile-run-") as directory:
        stdout_path = Path(directory) / "stdout.log"
        stderr_path = Path(directory) / "stderr.log"
        started = time.perf_counter()
        peak_rss_bytes: int | None = None
        with stdout_path.open("w", encoding="utf-8") as stdout, stderr_path.open("w", encoding="utf-8") as stderr:
            process = subprocess.Popen(
                command,
                cwd=directory,
                env=environment,
                stdout=stdout,
                stderr=stderr,
                text=True,
            )
            while process.poll() is None:
                rss_bytes = process_tree_rss_bytes(process.pid)
                if rss_bytes is not None:
                    peak_rss_bytes = max(peak_rss_bytes or 0, rss_bytes)
                time.sleep(0.1)
            rss_bytes = process_tree_rss_bytes(process.pid)
            if rss_bytes is not None:
                peak_rss_bytes = max(peak_rss_bytes or 0, rss_bytes)
        elapsed = time.perf_counter() - started
        stderr_text = stderr_path.read_text(encoding="utf-8", errors="replace")
        if process.returncode:
            raise RuntimeError(
                f"Joern failed for {graph} with status {process.returncode}:\n{stderr_text[-4000:]}"
            )
        profiles = parse_profiles(stderr_text)
    scala_profile = next((item for item in profiles if "language" in item), None)
    if scala_profile is None:
        raise RuntimeError(f"missing Scala profile for {graph}")
    return {
        "wall_ns": int(elapsed * 1_000_000_000),
        "peak_process_tree_rss_bytes": peak_rss_bytes,
        "rss_available": peak_rss_bytes is not None,
        "scala": scala_profile,
    }


def profile_graph(graph: Path, code_root: Path, scala_script: Path, temporary: Path) -> dict[str, Any]:
    pre_output = temporary / f"{graph.stem}.pre.json"
    joern_metrics = run_joern(graph.resolve(), code_root.resolve(), scala_script.resolve(), pre_output)
    environment_before = os.environ.get("SAJANIEMI_PROFILE")
    os.environ["SAJANIEMI_PROFILE"] = "1"
    python_stderr = io.StringIO()
    try:
        with contextlib.redirect_stderr(python_stderr):
            resolved = resolve_variable_aware_output(pre_output, code_root)
            legacy = legacy_annotations_from_roles(resolved["role_annotations"])
    finally:
        if environment_before is None:
            os.environ.pop("SAJANIEMI_PROFILE", None)
        else:
            os.environ["SAJANIEMI_PROFILE"] = environment_before
    python_profiles = parse_profiles(python_stderr.getvalue())
    pre = json.loads(pre_output.read_text(encoding="utf-8"))
    role_counts: dict[str, int] = {}
    target_subjects: dict[str, list[str]] = {role: [] for role in sorted(TARGET_ROLES)}
    roles_by_subject = {
        record["subject"]["id"]: set(record["roles"])
        for record in resolved["variable_facts"]
    }
    for record in resolved["role_annotations"]:
        role = record["concept"]["name"]
        role_counts[role] = role_counts.get(role, 0) + 1
        if role in target_subjects:
            target_subjects[role].append(record["subject"]["id"])
    overlaps: dict[str, int] = {}
    for roles in roles_by_subject.values():
        for target in sorted(roles & TARGET_ROLES):
            for other in sorted(roles - {target}):
                key = f"{target}|{other}"
                overlaps[key] = overlaps.get(key, 0) + 1
    neutral = json.loads(json.dumps(resolved))
    neutral["role_annotations"] = [
        record for record in neutral["role_annotations"]
        if record["concept"]["name"] not in TARGET_ROLES
    ]
    for record in neutral["variable_facts"]:
        record["roles"] = [role for role in record["roles"] if role not in TARGET_ROLES]
    view_counts = {
        view: sum(len(record["views"][view]) for record in resolved["variable_facts"])
        for view in sorted(resolved["variable_facts"][0]["views"] if resolved["variable_facts"] else [])
    }
    return {
        "graph": str(graph),
        "graph_bytes": graph.stat().st_size,
        "joern": joern_metrics,
        "python": python_profiles,
        "pre_output_digest": canonical_digest(pre),
        "resolved_output_digest": canonical_digest(resolved),
        "legacy_digest": canonical_digest(legacy),
        "role_annotations": len(resolved["role_annotations"]),
        "variables": len(resolved["variable_facts"]),
        "legacy_annotations": len(legacy),
        "role_counts": dict(sorted(role_counts.items())),
        "target_subjects": {role: sorted(subjects) for role, subjects in target_subjects.items()},
        "target_role_overlaps": dict(sorted(overlaps.items())),
        "view_counts": view_counts,
        "protected_output_digest": canonical_digest(neutral),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--graph-dir", type=Path, default=ROOT / "graph/test")
    parser.add_argument("--languages", default="c,cpp,javascript,python,ruby")
    parser.add_argument("--code-root", type=Path, default=ROOT / "code/test")
    parser.add_argument("--scala-script", type=Path, default=ROOT / "src/scala/extract_dynamic_variables.sc")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    stems = [stem.strip() for stem in args.languages.split(",") if stem.strip()]
    report: dict[str, Any] = {
        "schema_version": 1,
        "rss_measurement": "100 ms ps samples of the joern process tree when ps is permitted; approximate and may miss short peaks",
        "graphs": {},
    }
    with tempfile.TemporaryDirectory(prefix="sajaniemi-profile-") as directory:
        temporary = Path(directory)
        for stem in stems:
            graph = args.graph_dir / f"{stem}.bin"
            if not graph.is_file():
                raise SystemExit(f"missing graph: {graph}")
            print(f"[profile] {stem}: {graph}", flush=True)
            report["graphs"][stem] = profile_graph(graph, args.code_root, args.scala_script, temporary)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote profile report to {args.report}")


if __name__ == "__main__":
    main()
