#!/usr/bin/env python3
"""Exact and subject-level comparison of extractor output bundles."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any


FILES = ("pre.json", "variable_facts.json", "roles.json", "roles.jsonl", "legacy.json")
SUBJECT_FIELDS = ("id", "joern_id", "name", "kind", "path", "scope")


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def subject_key(record: dict[str, Any]) -> str:
    subject = record.get("subject", {})
    return str(subject.get("id", "<missing>"))


def index_unique(records: list[dict[str, Any]], label: str) -> tuple[dict[str, dict[str, Any]], list[str]]:
    indexed: dict[str, dict[str, Any]] = {}
    duplicates: list[str] = []
    for record in records:
        key = subject_key(record)
        if key in indexed:
            duplicates.append(f"{label}:{key}")
        else:
            indexed[key] = record
    return indexed, duplicates


def item_identity(item: dict[str, Any]) -> tuple[Any, ...]:
    span = item.get("span", {})
    return (
        item.get("id"), item.get("code"), item.get("type"), item.get("relation"),
        span.get("byte_start"), span.get("byte_end"),
        span.get("line"), span.get("column"), span.get("line_end"), span.get("column_end"),
    )


def compare_views(before: dict[str, list[dict[str, Any]]], after: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    deltas: list[dict[str, Any]] = []
    for view in sorted(set(before) | set(after)):
        left = before.get(view, [])
        right = after.get(view, [])
        if left == right:
            continue
        left_ids = [item_identity(item) for item in left]
        right_ids = [item_identity(item) for item in right]
        if Counter(left_ids) == Counter(right_ids):
            deltas.append({"kind": "view_order_modified", "view": view})
            continue
        removed = list((Counter(left_ids) - Counter(right_ids)).elements())
        added = list((Counter(right_ids) - Counter(left_ids)).elements())
        if removed:
            deltas.append({"kind": "view_items_removed", "view": view, "items": removed})
        if added:
            deltas.append({"kind": "view_items_added", "view": view, "items": added})
    return deltas


def compare_variable_facts(before: list[dict[str, Any]], after: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[str]]:
    left, duplicates_left = index_unique(before, "before_variable")
    right, duplicates_right = index_unique(after, "after_variable")
    deltas: list[dict[str, Any]] = []
    for key in sorted(set(left) - set(right)):
        deltas.append({"subject": key, "kind": "subject_removed"})
    for key in sorted(set(right) - set(left)):
        deltas.append({"subject": key, "kind": "subject_added"})
    for key in sorted(set(left) & set(right)):
        old = left[key]
        new = right[key]
        old_subject = old.get("subject", {})
        new_subject = new.get("subject", {})
        metadata = {
            field: {"before": old_subject.get(field), "after": new_subject.get(field)}
            for field in SUBJECT_FIELDS if old_subject.get(field) != new_subject.get(field)
        }
        if metadata:
            deltas.append({"subject": key, "kind": "subject_metadata_modified", "fields": metadata})
        if old.get("is_collection") != new.get("is_collection"):
            deltas.append({
                "subject": key, "kind": "collection_modified",
                "before": old.get("is_collection"), "after": new.get("is_collection"),
            })
        old_roles = old.get("roles", [])
        new_roles = new.get("roles", [])
        removed_roles = sorted(set(old_roles) - set(new_roles))
        added_roles = sorted(set(new_roles) - set(old_roles))
        if removed_roles:
            deltas.append({"subject": key, "kind": "roles_removed", "roles": removed_roles})
        if added_roles:
            deltas.append({"subject": key, "kind": "roles_added", "roles": added_roles})
        if not removed_roles and not added_roles and old_roles != new_roles:
            deltas.append({"subject": key, "kind": "role_order_modified"})
        for view_delta in compare_views(old.get("views", {}), new.get("views", {})):
            deltas.append({"subject": key, **view_delta})
    return deltas, duplicates_left + duplicates_right


def role_key(record: dict[str, Any]) -> tuple[str, str]:
    return subject_key(record), str(record.get("concept", {}).get("name", "<missing>"))


def compare_roles(before: list[dict[str, Any]], after: list[dict[str, Any]]) -> list[dict[str, Any]]:
    left = {role_key(record): record for record in before}
    right = {role_key(record): record for record in after}
    deltas: list[dict[str, Any]] = []
    for key in sorted(set(left) - set(right)):
        deltas.append({"subject": key[0], "role": key[1], "kind": "role_record_removed"})
    for key in sorted(set(right) - set(left)):
        deltas.append({"subject": key[0], "role": key[1], "kind": "role_record_added"})
    for key in sorted(set(left) & set(right)):
        if left[key] == right[key]:
            continue
        for view_delta in compare_views(left[key].get("views", {}), right[key].get("views", {})):
            deltas.append({"subject": key[0], "role": key[1], **view_delta})
        if left[key].get("subject") != right[key].get("subject"):
            deltas.append({"subject": key[0], "role": key[1], "kind": "role_subject_modified"})
    if Counter(map(role_key, before)) == Counter(map(role_key, after)) and before != after:
        deltas.append({"kind": "role_record_order_modified"})
    return deltas


def compare_bundle(before_dir: Path, after_dir: Path) -> dict[str, Any]:
    report: dict[str, Any] = {"schema_version": 1, "files": {}, "deltas": [], "duplicates": []}
    documents: dict[str, tuple[Any, Any]] = {}
    for name in FILES:
        before_path = before_dir / name
        after_path = after_dir / name
        if not before_path.is_file() or not after_path.is_file():
            report["deltas"].append({"kind": "missing_output", "file": name})
            continue
        byte_equal = before_path.read_bytes() == after_path.read_bytes()
        if name.endswith(".jsonl"):
            before_value = [json.loads(line) for line in before_path.read_text(encoding="utf-8").splitlines()]
            after_value = [json.loads(line) for line in after_path.read_text(encoding="utf-8").splitlines()]
        else:
            before_value, after_value = load_json(before_path), load_json(after_path)
        documents[name] = before_value, after_value
        report["files"][name] = {
            "byte_equal": byte_equal,
            "before_digest": digest(before_value),
            "after_digest": digest(after_value),
            "canonical_equal": before_value == after_value,
        }

    pre = documents.get("pre.json")
    if pre and pre[0].get("schema_version") != pre[1].get("schema_version"):
        report["deltas"].append({
            "kind": "schema_version_modified",
            "before": pre[0].get("schema_version"), "after": pre[1].get("schema_version"),
        })
    facts = documents.get("variable_facts.json")
    if facts:
        deltas, duplicates = compare_variable_facts(facts[0], facts[1])
        report["deltas"].extend(deltas)
        report["duplicates"].extend(duplicates)
    roles = documents.get("roles.json")
    if roles:
        report["deltas"].extend(compare_roles(roles[0], roles[1]))
    legacy = documents.get("legacy.json")
    if legacy and legacy[0] != legacy[1]:
        kind = "legacy_order_modified" if Counter(map(digest, legacy[0])) == Counter(map(digest, legacy[1])) else "legacy_modified"
        report["deltas"].append({"kind": kind})
    report["equal"] = not report["deltas"] and not report["duplicates"] and all(
        details["byte_equal"] for details in report["files"].values()
    ) and len(report["files"]) == len(FILES)
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = compare_bundle(args.before, args.after)
    payload = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(payload, encoding="utf-8")
    print(payload, end="")
    raise SystemExit(0 if report["equal"] else 1)


if __name__ == "__main__":
    main()
