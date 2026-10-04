#!/usr/bin/env python3
"""Resolve Joern line/column variable annotations to exact character spans."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


def load_annotations(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError(f"{path} must contain a JSON array")
    return data


def build_source_index(code_root: Path) -> dict[str, Path]:
    index: dict[str, Path] = {}
    for path in code_root.rglob("*"):
        if path.is_file():
            rel = path.relative_to(code_root).as_posix()
            index[rel] = path
            index.setdefault(path.name, path)
    return index


def line_offsets(text: str) -> list[int]:
    offsets = [0]
    for match in re.finditer(r"\n", text):
        offsets.append(match.end())
    return offsets


def identifier_matches(line: str, name: str) -> list[re.Match[str]]:
    pattern = re.compile(rf"(?<![A-Za-z0-9_$]){re.escape(name)}(?![A-Za-z0-9_$])")
    return list(pattern.finditer(line))


def pick_match(matches: list[re.Match[str]], column: int) -> re.Match[str] | None:
    if not matches:
        return None
    target = max(0, column - 1)
    return min(matches, key=lambda m: abs(m.start() - target))


def positive_int(value: Any, default: int = 0) -> int:
    """Return a positive integer hint, or ``default`` for malformed input."""
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def resolve_one(ann: dict[str, Any], source_index: dict[str, Path], cache: dict[Path, tuple[str, list[int]]]) -> dict[str, Any] | None:
    if not isinstance(ann, dict) or not isinstance(ann.get("concept"), str) or not isinstance(ann.get("variant"), str):
        return None
    raw_path = str(ann.get("path", ""))
    source_path = source_index.get(raw_path) or source_index.get(Path(raw_path).name)
    if source_path is None:
        return None

    if source_path not in cache:
        text = source_path.read_text(encoding="utf-8", errors="replace")
        cache[source_path] = (text, line_offsets(text))
    text, offsets = cache[source_path]

    line_no = positive_int(ann.get("line"))
    if line_no <= 0 or line_no > len(offsets):
        return None
    line_start = offsets[line_no - 1]
    line_end = text.find("\n", line_start)
    if line_end == -1:
        line_end = len(text)
    line_text = text[line_start:line_end]

    name = str(ann.get("name") or "")
    if not name:
        return None
    match = pick_match(identifier_matches(line_text, name), positive_int(ann.get("column"), default=1))
    if match is None:
        return None

    span_start = line_start + match.start()
    span_end = line_start + match.end()
    resolved = {
        "concept": ann["concept"],
        "variant": ann["variant"],
        "path": source_path.relative_to(source_index[""]).as_posix() if "" in source_index else raw_path,
        "line": line_no,
        "column": match.start() + 1,
        "line_end": line_no,
        "span_start": span_start,
        "span_end": span_end,
    }
    return resolved


def resolve_annotations(pre_annotations: Path, code_root: Path) -> list[dict[str, Any]]:
    source_index = build_source_index(code_root)
    source_index[""] = code_root
    cache: dict[Path, tuple[str, list[int]]] = {}
    resolved: list[dict[str, Any]] = []
    seen: set[tuple[Any, ...]] = set()
    for ann in load_annotations(pre_annotations):
        if not isinstance(ann, dict):
            continue
        item = resolve_one(ann, source_index, cache)
        if item is None:
            continue
        key = (item["concept"], item["variant"], item["path"], item["span_start"], item["span_end"])
        if key not in seen:
            seen.add(key)
            resolved.append(item)
    return sorted(resolved, key=lambda a: (a["path"], a["line"], a["column"], a["concept"], a["variant"]))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pre_annotations", type=Path)
    parser.add_argument("--code-root", type=Path, default=Path("code"))
    parser.add_argument("--output", type=Path, default=Path("dynamic_annotations.json"))
    args = parser.parse_args()

    resolved = resolve_annotations(args.pre_annotations, args.code_root)
    args.output.write_text(json.dumps(resolved, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(resolved)} resolved annotations to {args.output}")


if __name__ == "__main__":
    main()
