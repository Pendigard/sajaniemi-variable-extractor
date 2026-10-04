"""Resolve and convert the minimal variable-aware annotation contract."""

from __future__ import annotations

import copy
import json
import os
import re
import resource
import sys
import time
from pathlib import Path
from typing import Any, Iterable

from src.python.resolve_spans import build_source_index, identifier_matches, pick_match, positive_int


SAJANIEMI_ROLES = frozenset({
    "fixed_value",
    "stepper",
    "gatherer",
    "walker",
    "follower",
    "most_recent_holder",
    "most_wanted_holder",
    "one_way_flag",
    "temporary",
    "organizer",
    "container",
})

MINIMAL_VIEW_KEYS = frozenset({
    "declaration",
    "identifier",
    "reads",
    "writes",
    "updates",
    "state_mutations",
    "control_context",
    "scope",
})


def _profiling_enabled() -> bool:
    return os.environ.get("SAJANIEMI_PROFILE") == "1"


def _maximum_rss_bytes() -> int:
    """Return process peak RSS; ru_maxrss uses bytes on macOS and KiB on Linux."""
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(value if sys.platform == "darwin" else value * 1024)


def _emit_profile(component: str, started_at: float, phases: dict[str, float], counters: dict[str, int]) -> None:
    if not _profiling_enabled():
        return
    report = {
        "schema_version": 1,
        "component": component,
        "total_ns": int((time.perf_counter() - started_at) * 1_000_000_000),
        "peak_rss_bytes": _maximum_rss_bytes(),
        "durations_ns": {name: int(duration * 1_000_000_000) for name, duration in sorted(phases.items())},
        "counters": dict(sorted(counters.items())),
    }
    print("SAJANIEMI_PYTHON_PROFILE=" + json.dumps(report, sort_keys=True), file=sys.stderr)


class VariableAwareResolutionError(ValueError):
    """Raised when the pre-resolution output does not have the expected shape."""


class SourceResolver:
    def __init__(self, code_root: Path) -> None:
        self.code_root = code_root
        self.source_index = build_source_index(code_root)
        self.cache: dict[Path, tuple[str, list[int]]] = {}

    def source(self, raw_path: str) -> tuple[Path, str, list[int]] | None:
        source_path = self.source_index.get(raw_path) or self.source_index.get(Path(raw_path).name)
        if source_path is None:
            return None
        if source_path not in self.cache:
            with source_path.open("r", encoding="utf-8", errors="replace", newline="") as source_file:
                text = source_file.read()
            offsets = [0]
            offsets.extend(match.end() for match in re.finditer(r"\n", text))
            self.cache[source_path] = (text, offsets)
        text, offsets = self.cache[source_path]
        return source_path, text, offsets

    def relative_path(self, source_path: Path) -> str:
        return source_path.relative_to(self.code_root).as_posix()

    @staticmethod
    def location(text: str, start: int, end: int, line: int, start_column: int, end_column: int) -> dict[str, int]:
        return {
            "start_byte": len(text[:start].encode("utf-8")),
            "end_byte": len(text[:end].encode("utf-8")),
            "start_line": line,
            "start_column": start_column,
            "end_line": line,
            "end_column": end_column,
        }

    @staticmethod
    def range_location(
        text: str,
        start: int,
        end: int,
        start_line: int,
        start_column: int,
        end_line: int,
        end_column: int,
    ) -> dict[str, int]:
        return {
            "start_byte": len(text[:start].encode("utf-8")),
            "end_byte": len(text[:end].encode("utf-8")),
            "start_line": start_line,
            "start_column": start_column,
            "end_line": end_line,
            "end_column": end_column,
        }

    def identifier(self, item: dict[str, Any]) -> dict[str, Any] | None:
        hint = item.get("_hint")
        if not isinstance(hint, dict):
            return None
        resolved_source = self.source(str(hint.get("path", "")))
        if resolved_source is None:
            return None
        _, text, offsets = resolved_source
        line = positive_int(hint.get("line"))
        if line <= 0 or line > len(offsets):
            return None
        line_start = offsets[line - 1]
        line_end = text.find("\n", line_start)
        if line_end == -1:
            line_end = len(text)
        line_text = text[line_start:line_end]
        name = str(hint.get("name") or "")
        match = pick_match(
            identifier_matches(line_text, name),
            positive_int(hint.get("column"), default=1),
        )
        if match is None:
            return None
        start = line_start + match.start()
        end = line_start + match.end()
        return {
            "usage": str(item.get("usage") or "unknown"),
            "location": self.location(text, start, end, line, match.start() + 1, match.end() + 1),
            "code": text[start:end],
        }

    def context(self, item: dict[str, Any], *, require_name: bool = True) -> dict[str, Any] | None:
        hint = item.get("_hint")
        if not isinstance(hint, dict):
            return None
        resolved_source = self.source(str(hint.get("path", "")))
        if resolved_source is None:
            return None
        _, text, offsets = resolved_source
        line = positive_int(hint.get("line"))
        if line <= 0 or line > len(offsets):
            return None
        line_start = offsets[line - 1]
        line_end = text.find("\n", line_start)
        if line_end == -1:
            line_end = len(text)
        raw_line = text[line_start:line_end]
        name = str(hint.get("name") or "")
        if require_name and name and not identifier_matches(raw_line, name):
            return None
        left = len(raw_line) - len(raw_line.lstrip())
        right = len(raw_line.rstrip())
        if left >= right:
            return None
        start = line_start + left
        end = line_start + right
        return {
            "location": self.location(text, start, end, line, left + 1, right + 1),
            "code": text[start:end],
        }

    @staticmethod
    def _ruby_definition_end(header: str) -> int:
        """Exclude an endless-method expression while retaining default arguments."""
        operator = r"(?:\[\]=?|<=>|===|==|<=|>=|<<|>>|[-+*/%&|^~`])"
        source_name = rf"(?:(?:self\.)?(?:[A-Za-z_$][A-Za-z0-9_$]*[!?=]?|{operator}))"
        endless = re.match(rf"(?s)^def\s+{source_name}(?:\s*\([^)]*\))?\s+(=)(?!=)", header)
        return endless.end(1) if endless else len(header.rstrip())

    @staticmethod
    def _scan_balanced_header(source: str, boundary: str) -> int | None:
        """Return an exclusive source-backed boundary without parsing a full language."""
        stack: list[str] = []
        quote = ""
        triple = False
        escaped = False
        index = 0
        while index < len(source):
            char = source[index]
            if quote:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif triple and source.startswith(quote * 3, index):
                    index += 2
                    quote = ""
                    triple = False
                elif not triple and char == quote:
                    quote = ""
                index += 1
                continue
            if char in {"'", '"'}:
                quote = char
                triple = source.startswith(char * 3, index)
                if triple:
                    index += 2
                index += 1
                continue
            if char == "#":
                newline = source.find("\n", index)
                if newline == -1:
                    return None
                index = newline
                continue
            if source.startswith("//", index):
                newline = source.find("\n", index)
                if newline == -1:
                    return None
                index = newline
                continue
            if source.startswith("/*", index):
                closing = source.find("*/", index + 2)
                if closing == -1:
                    return None
                index = closing + 2
                continue
            if boundary == "ruby_brace" and char == "{" and not stack:
                block_parameter = re.match(r"\{\s*\|[^|]*\|", source[index:])
                return index + (block_parameter.end() if block_parameter else 1)
            if boundary == "ruby_do" and not stack and source.startswith("do", index):
                before = source[index - 1] if index else " "
                after = source[index + 2] if index + 2 < len(source) else " "
                if not (before.isalnum() or before in "_$") and not (after.isalnum() or after in "_$"):
                    block_parameter = re.match(r"do\s*\|[^|]*\|", source[index:])
                    return index + (block_parameter.end() if block_parameter else 2)
            if char in "([{":
                stack.append(char)
            elif char in ")]}":
                if stack:
                    stack.pop()
            elif boundary in {"python_def", "python_def_adapter", "python_lambda"} and char == ":" and not stack:
                return index + 1
            elif boundary == "javascript_arrow_expression" and source.startswith("=>", index) and not stack:
                return index + 2
            elif boundary == "prototype" and char == ";" and not stack:
                return index + 1
            elif boundary == "ruby_def" and char == "\n" and not stack:
                return SourceResolver._ruby_definition_end(source[:index].rstrip())
            index += 1
        if boundary == "ruby_def" and not stack:
            return SourceResolver._ruby_definition_end(source.rstrip())
        return None

    @staticmethod
    def _hint_column(hint: dict[str, Any]) -> int:
        try:
            return max(0, int(hint.get("column", 0)))
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _method_prefix(raw_code: Any) -> str:
        code = str(raw_code or "").lstrip()
        if not code or code in {"<empty>", ":program", "<global>"}:
            return ""
        return code.splitlines()[0].rstrip()

    @staticmethod
    def _structural_start(text: str, lower_bound: int, anchor: int, *, comma: bool) -> int:
        """Find the last same-level source separator before a callable."""
        stack: list[str] = []
        quote = ""
        escaped = False
        line_comment = False
        block_comment = False
        boundaries: list[tuple[int, tuple[str, ...]]] = [(lower_bound, ())]
        index = lower_bound
        while index < anchor:
            char = text[index]
            following = text[index + 1] if index + 1 < anchor else ""
            if line_comment:
                if char == "\n":
                    line_comment = False
                index += 1
                continue
            if block_comment:
                if char == "*" and following == "/":
                    block_comment = False
                    index += 2
                else:
                    index += 1
                continue
            if quote:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == quote:
                    quote = ""
                index += 1
                continue
            if char == "/" and following == "/":
                line_comment = True
                index += 2
                continue
            if char == "/" and following == "*":
                block_comment = True
                index += 2
                continue
            if char in {"'", '"', "`"}:
                quote = char
                index += 1
                continue
            if char in "([{":
                stack.append(char)
                if char == "{":
                    boundaries.append((index + 1, tuple(stack)))
            elif char in ")]}" and stack:
                stack.pop()
                if char == "}":
                    boundaries.append((index + 1, tuple(stack)))
            elif char == ";" and not any(item in "([" for item in stack):
                boundaries.append((index + 1, tuple(stack)))
            elif comma and char == "," and not any(item in "([" for item in stack):
                boundaries.append((index + 1, tuple(stack)))
            index += 1
        target = tuple(stack)
        same_level = [position for position, state in boundaries if state == target]
        return max(same_level) if same_level else lower_bound

    @staticmethod
    def _skip_leading_comments(text: str, start: int, anchor: int) -> int:
        candidate = start
        while candidate < anchor:
            while candidate < anchor and text[candidate].isspace():
                candidate += 1
            if text.startswith("//", candidate):
                newline = text.find("\n", candidate + 2, anchor)
                if newline < 0:
                    return anchor
                candidate = newline + 1
            elif text.startswith("/*", candidate):
                closing = text.find("*/", candidate + 2, anchor)
                if closing < 0:
                    return anchor
                candidate = closing + 2
            else:
                break
        return candidate

    @classmethod
    def _extend_c_family_start(cls, text: str, anchor: int, method_prefix: str) -> int:
        candidate = cls._structural_start(text, 0, anchor, comma=False)
        segment = text[candidate:anchor]
        delimiters = list(re.finditer(
            r"(?m)^\s*(?:#(?!.*\\\s*$).*$|(?:public|private|protected)\s*:\s*$)", segment
        ))
        if delimiters:
            candidate += delimiters[-1].end()
        candidate = cls._skip_leading_comments(text, candidate, anchor)
        prefix = text[candidate:anchor]
        if not prefix.strip() or "\n\n" in prefix.replace("\r\n", "\n"):
            return anchor
        if "//" in prefix or "/*" in prefix or "*/" in prefix:
            return anchor
        comparable = method_prefix[: min(len(method_prefix), 80)]
        if not comparable or not text.startswith(comparable, anchor):
            return anchor
        return candidate

    @classmethod
    def _extend_javascript_start(cls, text: str, line_start: int, anchor: int) -> int:
        candidate = cls._structural_start(text, line_start, anchor, comma=True)
        while candidate < anchor and text[candidate].isspace():
            candidate += 1
        left = text[candidate:anchor]
        assignment = r"(?:const|let|var)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*"
        property_key = r"(?:[A-Za-z_$][A-Za-z0-9_$]*|\"(?:\\.|[^\"])*\"|'(?:\\.|[^'])*')"
        property_assignment = rf"{property_key}\s*:\s*"
        expression_assignment = r"[A-Za-z_$][A-Za-z0-9_$\.\[\]'\"]*\s*=\s*"
        if re.fullmatch(rf"(?:{assignment}|{property_assignment}|{expression_assignment}|export\s+default\s+|\(\s*)", left):
            return candidate
        return anchor

    def _callable_anchor(
        self,
        text: str,
        line_start: int,
        line_end: int,
        hint: dict[str, Any],
        boundary: str,
    ) -> tuple[int, bool] | None:
        column = self._hint_column(hint)
        relative = {column, max(0, column - 1)}
        candidates = [line_start + value for value in relative if line_start + value <= line_end]
        method_prefix = self._method_prefix(hint.get("code"))
        name = str(hint.get("name") or "")
        if method_prefix:
            for candidate in sorted(candidates):
                comparable = method_prefix[: min(len(method_prefix), 80)]
                if comparable and text.startswith(comparable, candidate):
                    return candidate, True

        if boundary == "block" and name:
            declaration = re.compile(
                rf"(?s)^(?!\s*(?:if|for|while|switch)\b)[^;{{}}]*\b{re.escape(name)}\s*\("
            )
            for candidate in sorted(candidates):
                if declaration.match(text[candidate:line_end]):
                    return candidate, False

        line = text[line_start:line_end]
        patterns: list[str] = []
        if boundary in {"python_def", "python_def_adapter"}:
            patterns = [rf"(?:async\s+)?def\s+{re.escape(name)}\b"]
        elif boundary == "ruby_def":
            patterns = [rf"def\s+(?:self\.)?{re.escape(name)}(?=\s|\()"]
        elif boundary == "python_lambda":
            patterns = [r"\blambda\b"]
        elif boundary.startswith("javascript"):
            if name:
                patterns.append(rf"\bfunction\s+{re.escape(name)}\b")
                patterns.append(rf"\b{re.escape(name)}\s*\(")
            patterns.extend([r"\bfunction\b", r"(?:\([^)]*\)|[A-Za-z_$][A-Za-z0-9_$]*)\s*=>"])
        elif boundary == "cpp_lambda_block":
            patterns = [r"\["]
        elif boundary in {"ruby_do", "ruby_brace"}:
            patterns = [r"\S"]
        elif name:
            patterns = [re.escape(name)]

        matches = [match for pattern in patterns for match in re.finditer(pattern, line)]
        if matches:
            target = min(candidates) if candidates else line_start
            match = min(matches, key=lambda found: abs(line_start + found.start() - target))
            anchor = line_start + match.start()
            if boundary in {"block", "prototype"} and method_prefix:
                method_name_offset = method_prefix.find(name)
                if method_name_offset >= 0:
                    anchor = max(line_start, anchor - method_name_offset)
            return anchor, False
        return None

    def scope(self, item: dict[str, Any]) -> dict[str, Any] | None:
        hint = item.get("_hint")
        if not isinstance(hint, dict):
            return None
        resolved_source = self.source(str(hint.get("path", "")))
        if resolved_source is None:
            return None
        _, text, offsets = resolved_source
        start_line = positive_int(hint.get("line"))
        if start_line <= 0 or start_line > len(offsets):
            return None
        raw_start = offsets[start_line - 1]
        first_line_end = text.find("\n", raw_start)
        if first_line_end == -1:
            first_line_end = len(text)
        boundary = str(hint.get("boundary") or "")
        name = str(hint.get("name") or "")
        anchor_info = self._callable_anchor(text, raw_start, first_line_end, hint, boundary)
        if anchor_info is None:
            return None
        anchor, method_code_matched = anchor_info
        if boundary.startswith("javascript"):
            start = self._extend_javascript_start(text, raw_start, anchor)
        elif boundary == "block" and method_code_matched:
            start = self._extend_c_family_start(text, anchor, self._method_prefix(hint.get("code")))
        else:
            start = anchor

        if boundary in {"block", "cpp_lambda_block", "javascript_block"}:
            end_line_hint = positive_int(hint.get("end_line"))
            end_column_hint = positive_int(hint.get("end_column"), default=1)
            if end_line_hint <= 0 or end_line_hint > len(offsets):
                return None
            boundary_line_start = offsets[end_line_hint - 1]
            boundary_line_end = text.find("\n", boundary_line_start)
            if boundary_line_end == -1:
                boundary_line_end = len(text)
            boundary_line = text[boundary_line_start:boundary_line_end]
            braces = [
                match.start() for match in re.finditer(r"\{", boundary_line)
                if boundary_line_start + match.start() >= anchor
            ]
            if not braces:
                return None
            target_columns = {end_column_hint, max(0, end_column_hint - 1)}
            brace = min(braces, key=lambda column: min(abs(column - target) for target in target_columns))
            end = boundary_line_start + brace + 1
        elif boundary in {
            "prototype", "python_def", "python_def_adapter", "python_lambda", "ruby_def", "ruby_do", "ruby_brace",
            "javascript_arrow_expression",
        }:
            scanned = self._scan_balanced_header(text[anchor:], boundary)
            if scanned is None:
                return None
            end = anchor + scanned
        else:
            return None

        code = text[start:end].rstrip()
        end = start + len(code)
        if not code or "\n" in name:
            return None
        if boundary in {"python_def", "python_def_adapter"} and not re.match(r"^(?:async\s+)?def\s+" + re.escape(name) + r"\b", code):
            return None
        if boundary == "ruby_def" and not re.match(r"^def\s+(?:self\.)?" + re.escape(name) + r"(?=\s|\()", code):
            return None
        if boundary == "python_lambda" and not code.startswith("lambda"):
            return None
        hinted_code = str(hint.get("code") or "").strip().replace("\r\n", "\n")
        source_matches_hint = bool(hinted_code) and code.replace("\r\n", "\n") == hinted_code
        if boundary == "prototype" and (not (method_code_matched or source_matches_hint) or not code.endswith(";")):
            return None
        if boundary == "cpp_lambda_block" and not code.startswith("["):
            return None
        if boundary == "javascript_arrow_expression" and not code.endswith("=>"):
            return None
        if boundary == "ruby_do" and not re.search(r"\bdo(?:\s*\|[^|]*\|)?$", code):
            return None
        if boundary == "ruby_brace" and not re.search(r"\{(?:\s*\|[^|]*\|)?$", code):
            return None
        if boundary in {"block", "cpp_lambda_block", "javascript_block"} and not code.endswith("{"):
            return None
        if name and boundary not in {"python_def", "python_def_adapter", "ruby_def"} and name[0].isalnum() and not identifier_matches(code, name):
            return None

        start_prefix = text[:start]
        resolved_start_line = start_prefix.count("\n") + 1
        start_last_newline = start_prefix.rfind("\n")
        prefix = text[:end]
        end_line = prefix.count("\n") + 1
        last_newline = prefix.rfind("\n")
        end_column = end - last_newline
        return {
            "location": self.range_location(
                text, start, end, resolved_start_line, start - start_last_newline, end_line, end_column
            ),
            "code": code,
        }


def _item_sort_key(item: dict[str, Any]) -> tuple[Any, ...]:
    location = item["location"]
    return (
        location["start_byte"],
        location["end_byte"],
        item.get("usage", ""),
        item.get("relation", ""),
        item.get("mutation_kind", ""),
        item.get("code", ""),
    )


def _resolve_views(raw_views: dict[str, Any], resolver: SourceResolver) -> dict[str, list[dict[str, Any]]]:
    views: dict[str, list[dict[str, Any]]] = {}
    prefixes = {
        "declaration": "declaration",
        "identifier": "identifier",
        "reads": "read",
        "writes": "write",
        "updates": "update",
        "state_mutations": "state_mutation",
        "control_context": "control",
        "scope": "scope",
    }
    for key in (
        "declaration",
        "identifier",
        "reads",
        "writes",
        "updates",
        "state_mutations",
        "control_context",
        "scope",
    ):
        raw_items = raw_views.get(key, [])
        if not isinstance(raw_items, list):
            raise VariableAwareResolutionError(f"views.{key} must be a list")
        resolved_items = []
        for raw_item in raw_items:
            if not isinstance(raw_item, dict):
                continue
            resolved = (
                resolver.identifier(raw_item)
                if key == "identifier"
                else resolver.scope(raw_item)
                if key == "scope"
                else resolver.context(raw_item, require_name=key != "control_context")
            )
            if resolved is not None:
                for metadata_key in (
                    "mutation_kind",
                    "parser_operator",
                    "type",
                    "subtype",
                    "relation",
                ):
                    metadata_value = raw_item.get(metadata_key)
                    if isinstance(metadata_value, str) and metadata_value:
                        resolved[metadata_key] = metadata_value
                resolved_items.append(resolved)
        unique: dict[tuple[Any, ...], dict[str, Any]] = {}
        for item in sorted(resolved_items, key=_item_sort_key):
            location = item["location"]
            identity = (
                location["start_byte"],
                location["end_byte"],
                item.get("usage"),
                item.get("mutation_kind"),
                item.get("parser_operator"),
                item.get("type"),
                item.get("subtype"),
                item.get("relation"),
            )
            unique.setdefault(identity, item)
        views[key] = []
        for index, item in enumerate(unique.values()):
            views[key].append({"id": f"{prefixes[key]}:{index}", **item})
    return views


def _raw_subject_key(record: dict[str, Any]) -> tuple[Any, ...]:
    subject = record.get("subject", {})
    scope = subject.get("scope", {}) if isinstance(subject, dict) else {}
    if not isinstance(scope, dict):
        scope = {}
    return (
        subject.get("path"),
        scope.get("qualified_name") or scope.get("name"),
        subject.get("kind"),
        subject.get("name"),
        subject.get("_declaration_line"),
        subject.get("_declaration_column"),
    )


def _merge_raw_records(records: list[dict[str, Any]], *, include_concept: bool) -> list[dict[str, Any]]:
    """Collapse frontend duplicate declarations that identify the same source variable."""
    merged: dict[tuple[Any, ...], dict[str, Any]] = {}
    for record in records:
        concept_name = record.get("concept", {}).get("name") if include_concept else None
        key = (*_raw_subject_key(record), concept_name)
        if key not in merged:
            merged[key] = copy.deepcopy(record)
            continue
        target = merged[key]
        target_subject = target.get("subject", {})
        source_subject = record.get("subject", {})
        target_id = target_subject.get("joern_id")
        source_id = source_subject.get("joern_id")
        if isinstance(source_id, int) and (not isinstance(target_id, int) or source_id < target_id):
            target_subject["joern_id"] = source_id
        if not include_concept:
            target["roles"] = sorted(set(target.get("roles", [])) | set(record.get("roles", [])))
            target["is_collection"] = bool(target.get("is_collection", False)) or bool(
                record.get("is_collection", False)
            )
        for view_name in MINIMAL_VIEW_KEYS:
            target_items = target.setdefault("views", {}).setdefault(view_name, [])
            source_items = record.get("views", {}).get(view_name, [])
            seen = {json.dumps(item, sort_keys=True) for item in target_items}
            for item in source_items:
                serialized = json.dumps(item, sort_keys=True)
                if serialized not in seen:
                    seen.add(serialized)
                    target_items.append(copy.deepcopy(item))
    return list(merged.values())


def _resolved_subject_key(record: dict[str, Any]) -> tuple[Any, ...]:
    """Canonical identity after exact source-span resolution."""
    subject = record.get("subject", {})
    scope = subject.get("scope", {}) if isinstance(subject, dict) else {}
    declarations = record.get("views", {}).get("declaration", [])
    location = declarations[0].get("location", {}) if declarations else {}
    return (
        subject.get("path"),
        scope.get("qualified_name") or scope.get("name"),
        subject.get("kind"),
        subject.get("name"),
        location.get("start_byte"),
        location.get("end_byte"),
    )


def _merge_resolved_records(records: list[dict[str, Any]], *, include_concept: bool) -> list[dict[str, Any]]:
    """Merge only records with the same exact introduction span and callable owner."""
    merged: dict[tuple[Any, ...], dict[str, Any]] = {}
    for record in records:
        concept_name = record.get("concept", {}).get("name") if include_concept else None
        key = (*_resolved_subject_key(record), concept_name)
        if key not in merged:
            merged[key] = copy.deepcopy(record)
            continue
        target = merged[key]
        if str(record["subject"].get("id", "")) < str(target["subject"].get("id", "")):
            target["subject"] = copy.deepcopy(record["subject"])
        if not include_concept:
            target["roles"] = sorted(set(target.get("roles", [])) | set(record.get("roles", [])))
            target["is_collection"] = bool(target.get("is_collection", False)) or bool(
                record.get("is_collection", False)
            )
        for view_name in MINIMAL_VIEW_KEYS:
            combined = target.setdefault("views", {}).setdefault(view_name, []) + record.get("views", {}).get(
                view_name, []
            )
            unique: dict[str, dict[str, Any]] = {}
            for item in combined:
                unique.setdefault(json.dumps(item, sort_keys=True), copy.deepcopy(item))
            target["views"][view_name] = list(unique.values())
    return list(merged.values())


def _normalize_first_introduction(record: dict[str, Any], resolver: SourceResolver) -> dict[str, Any]:
    """Use a Python ``for name in ...`` header as the declaration source statement."""
    normalized = copy.deepcopy(record)
    subject = normalized.get("subject", {})
    if subject.get("kind") != "local":
        return normalized
    name = str(subject.get("name") or "")
    raw_views = normalized.get("views", {})
    identifiers = raw_views.get("identifier", [])
    candidates: list[tuple[int, int, dict[str, Any]]] = []
    pattern = re.compile(rf"\bfor\s+{re.escape(name)}\s+in\b")
    for item in identifiers:
        hint = item.get("_hint", {}) if isinstance(item, dict) else {}
        resolved_source = resolver.source(str(hint.get("path", ""))) if isinstance(hint, dict) else None
        line = positive_int(hint.get("line")) if isinstance(hint, dict) else 0
        if resolved_source is None or line <= 0:
            continue
        _, text, offsets = resolved_source
        if line > len(offsets):
            continue
        line_start = offsets[line - 1]
        line_end = text.find("\n", line_start)
        if line_end == -1:
            line_end = len(text)
        if pattern.search(text[line_start:line_end]):
            candidates.append((line, positive_int(hint.get("column"), default=1), item))
    if not candidates:
        return normalized
    _, _, declaration_item = min(candidates, key=lambda candidate: (candidate[0], candidate[1]))
    declaration_hint = declaration_item.get("_hint", {})
    declaration_key = (
        declaration_hint.get("path"),
        declaration_hint.get("line"),
        declaration_hint.get("column"),
    )
    for item in identifiers:
        hint = item.get("_hint", {})
        item_key = (hint.get("path"), hint.get("line"), hint.get("column"))
        if item_key == declaration_key:
            item["usage"] = "declaration"
        elif item.get("usage") == "declaration":
            item["usage"] = "read"
    declaration_context = copy.deepcopy(declaration_item)
    declaration_context["usage"] = "declaration"
    raw_views["declaration"] = [declaration_context]
    read_contexts: list[dict[str, Any]] = []
    seen_lines: set[tuple[Any, ...]] = set()
    for item in identifiers:
        if item.get("usage") != "read":
            continue
        hint = item.get("_hint", {})
        line_key = (hint.get("path"), hint.get("line"))
        if line_key not in seen_lines:
            seen_lines.add(line_key)
            read_contexts.append(copy.deepcopy(item))
    raw_views["reads"] = read_contexts
    return normalized


def _resolve_subject(raw_subject: dict[str, Any], views: dict[str, list[dict[str, Any]]], resolver: SourceResolver) -> dict[str, Any]:
    raw_path = str(raw_subject.get("path", ""))
    resolved_source = resolver.source(raw_path)
    path = resolver.relative_path(resolved_source[0]) if resolved_source is not None else raw_path
    declaration_identifiers = [
        item for item in views["identifier"] if item.get("usage") in {"declaration", "parameter"}
    ]
    line = positive_int(raw_subject.get("_declaration_line"))
    column = positive_int(raw_subject.get("_declaration_column"), default=1)
    if declaration_identifiers:
        location = declaration_identifiers[0]["location"]
        line = location["start_line"]
        column = location["start_column"]
    kind = str(raw_subject.get("kind") or "unknown")
    name = str(raw_subject.get("name") or "")
    raw_scope = raw_subject.get("scope", {})
    if not isinstance(raw_scope, dict):
        raw_scope = {}
    qualified_scope = str(raw_scope.get("qualified_name") or raw_scope.get("name") or path)
    scope = {
        "type": str(raw_scope.get("type") or "file"),
        "name": str(raw_scope.get("name") or qualified_scope),
        "qualified_name": qualified_scope,
    }
    return {
        "id": f"{path}::{qualified_scope}::{kind}::{name}:{line}:{column}",
        "joern_id": raw_subject.get("joern_id"),
        "name": name,
        "kind": kind,
        "path": path,
        "scope": scope,
    }


def _resolve_role_record(raw_record: dict[str, Any], resolver: SourceResolver) -> dict[str, Any]:
    raw_record = _normalize_first_introduction(raw_record, resolver)
    views = _resolve_views(raw_record.get("views", {}), resolver)
    subject = _resolve_subject(raw_record.get("subject", {}), views, resolver)
    return {
        "schema_version": 1,
        "concept": copy.deepcopy(raw_record.get("concept", {})),
        "subject": subject,
        "views": views,
    }


def _resolve_facts_record(raw_record: dict[str, Any], resolver: SourceResolver) -> dict[str, Any]:
    raw_record = _normalize_first_introduction(raw_record, resolver)
    views = _resolve_views(raw_record.get("views", {}), resolver)
    subject = _resolve_subject(raw_record.get("subject", {}), views, resolver)
    roles = sorted(role for role in raw_record.get("roles", []) if role in SAJANIEMI_ROLES)
    return {
        "schema_version": 1,
        "subject": subject,
        "is_collection": bool(raw_record.get("is_collection", False)),
        "roles": roles,
        "views": views,
    }


def resolve_variable_aware_output(pre_output: Path, code_root: Path) -> dict[str, Any]:
    """Resolve all minimal variable-aware views to exact UTF-8 byte spans."""
    profile_started = time.perf_counter()
    phase_started = profile_started
    profile_phases: dict[str, float] = {}

    def mark(name: str) -> None:
        nonlocal phase_started
        now = time.perf_counter()
        profile_phases[name] = profile_phases.get(name, 0.0) + now - phase_started
        phase_started = now

    raw = json.loads(pre_output.read_text(encoding="utf-8"))
    mark("json_load")
    if not isinstance(raw, dict):
        raise VariableAwareResolutionError("pre-output must be a JSON object")
    raw_roles = raw.get("role_annotations")
    raw_facts = raw.get("variable_facts")
    if not isinstance(raw_roles, list) or not isinstance(raw_facts, list):
        raise VariableAwareResolutionError("pre-output must contain role_annotations and variable_facts lists")
    resolver = SourceResolver(code_root)
    mark("source_index")
    merged_roles = _merge_raw_records(
        [record for record in raw_roles if isinstance(record, dict)],
        include_concept=True,
    )
    merged_facts = _merge_raw_records(
        [record for record in raw_facts if isinstance(record, dict)],
        include_concept=False,
    )
    mark("record_merge")
    role_annotations = [_resolve_role_record(record, resolver) for record in merged_roles]
    variable_facts = [_resolve_facts_record(record, resolver) for record in merged_facts]
    mark("span_resolution")
    # Synthetic frontend members do not have source-backed declaration or identifier spans.
    role_annotations = [
        record
        for record in role_annotations
        if record["views"]["declaration"] and record["views"]["identifier"]
    ]
    variable_facts = [
        record
        for record in variable_facts
        if record["views"]["declaration"] and record["views"]["identifier"]
    ]
    role_annotations = _merge_resolved_records(role_annotations, include_concept=True)
    variable_facts = _merge_resolved_records(variable_facts, include_concept=False)
    role_annotations.sort(
        key=lambda record: (
            record["subject"]["path"],
            record["views"]["declaration"][0]["location"]["start_byte"] if record["views"]["declaration"] else -1,
            record["concept"].get("name", ""),
            record["subject"]["id"],
        )
    )
    variable_facts.sort(key=lambda record: record["subject"]["id"])
    mark("filter_and_sort")
    resolved = {"schema_version": 1, "role_annotations": role_annotations, "variable_facts": variable_facts}
    _emit_profile(
        "variable_aware_resolution",
        profile_started,
        profile_phases,
        {
            "raw_role_annotations": len(raw_roles),
            "raw_variable_facts": len(raw_facts),
            "role_annotations": len(role_annotations),
            "variable_facts": len(variable_facts),
            "source_files_loaded": len(resolver.cache),
        },
    )
    return resolved


def legacy_annotations_from_roles(role_annotations: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """Convert role records to deterministic legacy token annotations."""
    profile_started = time.perf_counter()
    converted: list[dict[str, Any]] = []
    seen: set[tuple[Any, ...]] = set()
    for record in role_annotations:
        concept = record.get("concept", {})
        role = concept.get("name")
        if concept.get("family") != "sajaniemi_role" or role not in SAJANIEMI_ROLES:
            continue
        subject = record.get("subject", {})
        for item in record.get("views", {}).get("identifier", []):
            location = item.get("location", {})
            usage = item.get("usage")
            variants = ["reference"]
            if usage in {"declaration", "parameter"}:
                variants.insert(0, "declaration")
            for variant in variants:
                annotation = {
                    "concept": role,
                    "variant": variant,
                    "path": subject.get("path"),
                    "line": location.get("start_line"),
                    "column": location.get("start_column"),
                    "line_end": location.get("end_line"),
                    "column_end": location.get("end_column"),
                    "name": subject.get("name"),
                    "code": item.get("code"),
                }
                key = tuple(annotation.values())
                if key not in seen:
                    seen.add(key)
                    converted.append(annotation)
    result = sorted(
        converted,
        key=lambda item: (
            item["path"], item["line"], item["column"], item["concept"], item["variant"]
        ),
    )
    _emit_profile(
        "legacy_conversion",
        profile_started,
        {"conversion_and_sort": time.perf_counter() - profile_started},
        {"legacy_annotations": len(result)},
    )
    return result


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
