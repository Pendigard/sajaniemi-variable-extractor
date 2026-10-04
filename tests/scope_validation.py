from __future__ import annotations

import copy
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

from sajaniemi_extractor.variable_aware import MINIMAL_VIEW_KEYS, SourceResolver, legacy_annotations_from_roles


FORBIDDEN_FIELDS = {"facts", "evidence", "role_evidence", "extra_fact_evidence"}
NON_SCOPE_VIEWS = tuple(view for view in MINIMAL_VIEW_KEYS if view != "scope")
SCOPE_OUTCOMES = (
    "resolved",
    "file_or_module",
    "class_body",
    "function_defining_variable",
    "duplicate_merged",
    "synthetic_wrapper",
    "ambiguous_start",
    "ambiguous_end",
    "source_mismatch",
    "unsupported_callable",
    "unclassified",
)


def canonical_json_digest(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def without_scope(resolved: dict[str, Any]) -> dict[str, Any]:
    normalized = copy.deepcopy(resolved)
    for section in ("role_annotations", "variable_facts"):
        for record in normalized[section]:
            record["views"].pop("scope", None)
    return normalized


def duplicate_role_pair_count(resolved: dict[str, Any]) -> int:
    pairs = [
        (record["subject"]["id"], record["concept"]["name"])
        for record in resolved["role_annotations"]
    ]
    return len(pairs) - len(set(pairs))


def validate_output_invariants(
    resolved: dict[str, Any], *, require_unique_roles: bool = True
) -> None:
    if set(resolved) != {"schema_version", "role_annotations", "variable_facts"}:
        raise AssertionError(f"unexpected top-level keys: {sorted(resolved)}")
    roles = resolved["role_annotations"]
    variables = resolved["variable_facts"]
    if require_unique_roles and duplicate_role_pair_count(resolved):
        raise AssertionError("duplicate (subject.id, role) record")
    if any(not FORBIDDEN_FIELDS.isdisjoint(record) for record in roles + variables):
        raise AssertionError("obsolete fact/evidence field emitted")
    if any(not isinstance(record.get("is_collection"), bool) for record in variables):
        raise AssertionError("variable_facts.is_collection must be present and boolean")
    if roles != sorted(
        roles,
        key=lambda record: (
            record["subject"]["path"],
            record["views"]["declaration"][0]["location"]["start_byte"],
            record["concept"]["name"],
            record["subject"]["id"],
        ),
    ):
        raise AssertionError("role annotations are not deterministically ordered")
    if variables != sorted(variables, key=lambda record: record["subject"]["id"]):
        raise AssertionError("variable_facts are not deterministically ordered")


def output_metrics(resolved: dict[str, Any], pre_output: dict[str, Any] | None = None) -> dict[str, Any]:
    roles = resolved["role_annotations"]
    variables = resolved["variable_facts"]
    legacy = legacy_annotations_from_roles(roles)
    views = {
        view: sum(len(record["views"][view]) for record in variables)
        for view in sorted(MINIMAL_VIEW_KEYS)
    }
    candidates = 0
    if pre_output is not None:
        candidates = sum(
            len(record.get("views", {}).get("scope", []))
            for record in pre_output.get("variable_facts", [])
        )
    diagnostics = pre_output.get("scope_diagnostics", {}) if pre_output is not None else {}
    rejection_reasons = diagnostics.get("python_rejections_by_reason") if isinstance(diagnostics, dict) else None
    return {
        "variables": len(variables),
        "role_records": len(roles),
        "roles": dict(sorted(Counter(record["concept"]["name"] for record in roles).items())),
        "views": views,
        "legacy": {"count": len(legacy), "digest": canonical_json_digest(legacy)},
        "non_scope_digest": canonical_json_digest(without_scope(resolved)),
        "subject_ids_digest": canonical_json_digest([record["subject"]["id"] for record in variables]),
        "subject_scopes_digest": canonical_json_digest([
            {"id": record["subject"]["id"], "scope": record["subject"]["scope"]}
            for record in variables
        ]),
        "scala_scope_candidates": candidates,
        "python_scope_rejections": max(0, candidates - views["scope"]),
        "python_scope_rejections_by_reason": rejection_reasons,
        "duplicate_role_pairs": duplicate_role_pair_count(resolved),
    }


def scope_boundary_metrics(pre_output: dict[str, Any], code_root: Path) -> dict[str, dict[str, int]]:
    resolver = SourceResolver(code_root)
    candidates: Counter[str] = Counter()
    resolved: Counter[str] = Counter()
    for record in pre_output.get("variable_facts", []):
        for item in record.get("views", {}).get("scope", []):
            hint = item.get("_hint", {})
            boundary = str(hint.get("boundary") or "unknown")
            candidates[boundary] += 1
            if resolver.scope(item) is not None:
                resolved[boundary] += 1
    return {
        boundary: {"candidates": candidates[boundary], "resolved": resolved[boundary]}
        for boundary in sorted(candidates)
    }


def final_subject_scope_metrics(
    pre_output: dict[str, Any], resolved: dict[str, Any], code_root: Path
) -> dict[str, Any]:
    """Track raw scope hints to consolidated final subjects without changing annotations."""
    raw_by_joern_id: dict[Any, list[dict[str, Any]]] = {}
    for record in pre_output.get("variable_facts", []):
        subject = record.get("subject", {})
        raw_by_joern_id.setdefault(subject.get("joern_id"), []).append(record)
    raw_candidate_ids = {
        joern_id
        for joern_id, records in raw_by_joern_id.items()
        if any(record.get("views", {}).get("scope", []) for record in records)
    }

    outcomes: Counter[str] = Counter({name: 0 for name in SCOPE_OUTCOMES})
    subjects_with_candidate = 0
    duplicate_merged = 0
    exact_spans = 0
    final_scope_count = 0
    unclassified_with_candidate = 0
    unclassified_without_candidate = 0
    final_joern_ids: set[Any] = set()
    resolver = SourceResolver(code_root)

    for record in resolved.get("variable_facts", []):
        subject = record["subject"]
        final_joern_ids.add(subject.get("joern_id"))
        raw_records = raw_by_joern_id.get(subject.get("joern_id"), [])
        raw_candidates = [
            item
            for raw in raw_records
            for item in raw.get("views", {}).get("scope", [])
            if isinstance(item, dict)
        ]
        if raw_candidates:
            subjects_with_candidate += 1
        candidate_keys = [
            json.dumps(item.get("_hint", {}), sort_keys=True, separators=(",", ":"))
            for item in raw_candidates
        ]
        duplicate_merged += max(
            max(0, len(raw_records) - 1),
            len(candidate_keys) - len(set(candidate_keys)),
        )

        scopes = record.get("views", {}).get("scope", [])
        if scopes:
            outcomes["resolved"] += 1
            final_scope_count += len(scopes)
            source_path = code_root / subject["path"]
            if source_path.is_file():
                source = source_path.read_bytes()
                for item in scopes:
                    location = item["location"]
                    if source[location["start_byte"]:location["end_byte"]] == item["code"].encode("utf-8"):
                        exact_spans += 1
            continue

        scope_meta = subject.get("scope", {})
        scope_type = str(scope_meta.get("type", "")).lower()
        scope_name = str(scope_meta.get("name", ""))
        qualified_scope = str(scope_meta.get("qualified_name", ""))
        if (
            scope_type in {"file", "module", "namespace"}
            or scope_name in {"<module>", "<global>", ":program"}
            or any(marker in qualified_scope for marker in ("<module>", "<global>", ":program"))
        ):
            outcomes["file_or_module"] += 1
            continue
        if scope_type in {"class", "type", "type_decl"}:
            outcomes["class_body"] += 1
            continue

        declaration = record.get("views", {}).get("declaration", [])
        declaration_location = declaration[0].get("location", {}) if declaration else {}
        defining_callable = any(
            declaration_location.get("start_line") == item.get("_hint", {}).get("line")
            and declaration_location.get("start_column", 0) < item.get("_hint", {}).get("column", 0)
            for item in raw_candidates
        )
        if defining_callable:
            outcomes["function_defining_variable"] += 1
            continue
        if any(
            "<" in str(item.get("_hint", {}).get("name", ""))
            and str(item.get("_hint", {}).get("name", "")) != ""
            for item in raw_candidates
        ):
            outcomes["synthetic_wrapper"] += 1
            continue
        if raw_candidates:
            supported = {
                "block", "prototype", "cpp_lambda_block", "javascript_block",
                "javascript_arrow_expression", "python_def", "python_def_adapter",
                "python_lambda", "ruby_def", "ruby_do", "ruby_brace",
            }
            boundaries = {str(item.get("_hint", {}).get("boundary", "")) for item in raw_candidates}
            if not boundaries.issubset(supported):
                outcomes["unsupported_callable"] += 1
            elif not any(resolver.source(str(item.get("_hint", {}).get("path", ""))) for item in raw_candidates):
                outcomes["source_mismatch"] += 1
            else:
                # The current hint contract does not expose which boundary failed.
                outcomes["unclassified"] += 1
                unclassified_with_candidate += 1
        else:
            outcomes["unclassified"] += 1
            unclassified_without_candidate += 1

    outcomes["duplicate_merged"] = duplicate_merged
    intentional = sum(outcomes[name] for name in (
        "file_or_module", "class_body", "function_defining_variable", "synthetic_wrapper"
    ))
    real_rejections = sum(outcomes[name] for name in (
        "ambiguous_start", "ambiguous_end", "source_mismatch", "unsupported_callable"
    )) + unclassified_with_candidate
    eligible = outcomes["resolved"] + real_rejections
    return {
        "final_subjects": len(resolved.get("variable_facts", [])),
        "subjects_with_candidate": subjects_with_candidate,
        "raw_candidate_subjects": len(raw_candidate_ids),
        "raw_candidate_subjects_not_final": len(raw_candidate_ids - final_joern_ids),
        "resolved_subjects": outcomes["resolved"],
        "resolved_scopes": final_scope_count,
        "intentional_exclusions": intentional,
        "real_rejections": real_rejections,
        "duplicate_merged": duplicate_merged,
        "unclassified_losses": outcomes["unclassified"],
        "unclassified_with_candidate": unclassified_with_candidate,
        "eligibility_unknown_without_candidate": unclassified_without_candidate,
        "exact_scope_spans": exact_spans,
        "span_precision": exact_spans / final_scope_count if final_scope_count else None,
        "eligible_subjects": eligible,
        "eligible_coverage": outcomes["resolved"] / eligible if eligible else None,
        "outcomes": dict(outcomes),
        "classification_limit": (
            "ambiguous_start and ambiguous_end remain zero unless the extractor exposes a boundary reason; "
            "unresolved source-backed supported hints are conservatively unclassified; "
            "subjects without any candidate are excluded from eligible coverage"
        ),
    }


def load_scope_expectations(repository_root: Path) -> dict[str, Any]:
    path = repository_root / "tests/fixtures/scope_expectations.json"
    return json.loads(path.read_text(encoding="utf-8"))
