#!/usr/bin/env python3
"""Show random source views for variables, grouped by Sajaniemi role."""

from __future__ import annotations

import argparse
import json
import random
from collections import defaultdict
from pathlib import Path
from typing import Any, Sequence

GREEN = "\033[32m"
DIM = "\033[2m"
RESET = "\033[0m"

ROLES = (
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
)
VIEW_NAMES = (
    "declaration",
    "identifier",
    "reads",
    "writes",
    "updates",
    "state_mutations",
    "control_context",
    "scope",
)
NO_ROLE = "no-role"
NAME_MATCH_MODES = ("contain", "prefix", "suffix", "exact_match")


def load_json(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError(f"{path} must contain a JSON array")
    return data


def line_offsets(data: bytes) -> list[int]:
    offsets = [0]
    for index, byte in enumerate(data):
        if byte == ord("\n"):
            offsets.append(index + 1)
    return offsets


def line_for_span(offsets: list[int], span_start: int) -> int:
    lo, hi = 0, len(offsets)
    while lo + 1 < hi:
        mid = (lo + hi) // 2
        if offsets[mid] <= span_start:
            lo = mid
        else:
            hi = mid
    return lo + 1


def variable_roles(variable: dict[str, Any]) -> list[str]:
    roles = variable.get("roles")
    if not isinstance(roles, list):
        raise ValueError("each variable fact must contain a roles array")
    return [str(role) for role in roles] if roles else [NO_ROLE]


def matches_role_filter(
    variable: dict[str, Any], requested_roles: Sequence[str], exclude_roles: bool
) -> bool:
    if "all" in requested_roles:
        return not exclude_roles
    matches = bool(set(variable_roles(variable)).intersection(requested_roles))
    return not matches if exclude_roles else matches


def matches_name(variable: dict[str, Any], query: str, mode: str) -> bool:
    subject = variable.get("subject")
    if not isinstance(subject, dict) or not isinstance(subject.get("name"), str):
        raise ValueError("each variable fact must contain subject.name")
    name = subject["name"]
    if mode == "contain":
        return query in name
    if mode == "prefix":
        return name.startswith(query)
    if mode == "suffix":
        return name.endswith(query)
    if mode == "exact_match":
        return name == query
    raise ValueError(f"unknown name match mode: {mode}")


def available_views(variable: dict[str, Any], requested_view: str) -> list[str]:
    views = variable.get("views")
    if not isinstance(views, dict):
        raise ValueError("each variable fact must contain a views object")
    names = VIEW_NAMES if requested_view == "all" else (requested_view,)
    return [name for name in names if isinstance(views.get(name), list) and views[name]]


def group_variables(
    variables: list[dict[str, Any]],
    requested_roles: Sequence[str],
    requested_view: str,
    exclude_roles: bool = False,
) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for variable in variables:
        if not available_views(variable, requested_view) or not matches_role_filter(
            variable, requested_roles, exclude_roles
        ):
            continue
        if exclude_roles:
            label = "not " + ",".join(requested_roles)
            grouped[label].append(variable)
            continue
        selected = variable_roles(variable)
        if "all" not in requested_roles:
            selected = [role for role in selected if role in requested_roles]
        for role in selected:
            grouped[role].append(variable)
    return grouped


def matching_named_variables(
    variables: list[dict[str, Any]],
    query: str,
    mode: str,
    requested_roles: Sequence[str],
    exclude_roles: bool,
) -> list[dict[str, Any]]:
    return [
        variable
        for variable in variables
        if matches_name(variable, query, mode)
        and matches_role_filter(variable, requested_roles, exclude_roles)
    ]


def role_label(variable: dict[str, Any]) -> str:
    return ",".join(variable_roles(variable))


def view_items(
    variable: dict[str, Any], requested_view: str
) -> list[tuple[str, dict[str, Any]]]:
    views = variable.get("views")
    if not isinstance(views, dict):
        raise ValueError("each variable fact must contain a views object")
    names = VIEW_NAMES if requested_view == "all" else (requested_view,)
    return [
        (view_name, view_item)
        for view_name in names
        for view_item in views.get(view_name, [])
    ]


def all_view_items(variable: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    return view_items(variable, "all")


def limit_variables_per_role(
    variables: list[dict[str, Any]],
    requested_roles: Sequence[str],
    exclude_roles: bool,
    limit: int | None,
) -> list[dict[str, Any]]:
    if limit is None:
        return variables
    shuffled = list(variables)
    random.shuffle(shuffled)
    counts: dict[str, int] = defaultdict(int)
    selected: list[dict[str, Any]] = []
    for variable in shuffled:
        if exclude_roles:
            labels = ["excluded-role-selection"]
        elif "all" in requested_roles:
            labels = variable_roles(variable)
        else:
            labels = [role for role in variable_roles(variable) if role in requested_roles]
        if labels and all(counts[label] < limit for label in labels):
            selected.append(variable)
            for label in labels:
                counts[label] += 1
    return selected


def choose_view(
    variable: dict[str, Any], requested_view: str
) -> tuple[str, dict[str, Any]]:
    names = available_views(variable, requested_view)
    if not names:
        raise ValueError("selected variable has no item in the requested view")
    view_name = random.choice(names)
    return view_name, random.choice(variable["views"][view_name])


def render(
    variable: dict[str, Any],
    role: str,
    view_name: str,
    view_item: dict[str, Any],
    code_root: Path,
    context: int,
    max_span_lines: int,
) -> str:
    subject = variable.get("subject")
    if not isinstance(subject, dict) or not isinstance(subject.get("path"), str):
        raise ValueError("each variable fact must contain subject.path")
    location = view_item.get("location")
    if not isinstance(location, dict):
        raise ValueError("each view item must contain a location object")

    source_path = code_root / subject["path"]
    data = source_path.read_bytes()
    offsets = line_offsets(data)
    span_start = int(location["start_byte"])
    span_end = int(location["end_byte"])
    line_no = line_for_span(offsets, span_start)
    line_end = line_for_span(offsets, max(span_start, span_end - 1))
    first = max(1, line_no - context)
    last = min(len(offsets), line_end + context)
    visible_lines: list[int | None] = list(range(first, last + 1))
    if max_span_lines > 0 and len(visible_lines) > max_span_lines:
        head = max_span_lines // 2
        tail = max_span_lines - head
        visible_lines = visible_lines[:head] + [None] + visible_lines[-tail:]

    start_line = int(location.get("start_line", line_no))
    start_column = int(location.get("start_column", 1))
    out = [
        f"{role} / {view_name}  "
        f"{source_path}:{start_line}:{start_column}-{line_end}"
    ]
    for current in visible_lines:
        if current is None:
            out.append(f"{DIM}     | ...{RESET}")
            continue
        start = offsets[current - 1]
        end = data.find(b"\n", start)
        if end == -1:
            end = len(data)
        visible_end = end - 1 if end > start and data[end - 1 : end] == b"\r" else end
        highlighted = start < span_end and visible_end > span_start
        marker = ">" if highlighted else " "
        if highlighted:
            a = min(visible_end, max(start, span_start))
            b = min(visible_end, max(a, span_end))
            line = (
                data[start:a].decode("utf-8", errors="replace")
                + GREEN
                + data[a:b].decode("utf-8", errors="replace")
                + RESET
                + data[b:visible_end].decode("utf-8", errors="replace")
            )
        else:
            line = data[start:visible_end].decode("utf-8", errors="replace")
        out.append(f"{DIM}{marker}{current:4d}|{RESET} {line}")
    return "\n".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Show random source views from a split's variable_facts.json output."
    )
    parser.add_argument(
        "variable_facts",
        nargs="?",
        type=Path,
        help="Override the default outputs/<split>_variable_facts.json file",
    )
    parser.add_argument("--split", choices=("train", "test"), default="train")
    parser.add_argument(
        "--code-root",
        type=Path,
        help="Override the default code/<split> source directory",
    )
    parser.add_argument(
        "--role",
        nargs="+",
        choices=("all", NO_ROLE, *ROLES),
        default=["all"],
        help="One or more roles; matching uses OR for multilabel variables",
    )
    parser.add_argument(
        "--exclude-roles",
        action="store_true",
        help="Invert --role and keep variables having none of the selected roles",
    )
    parser.add_argument(
        "--view",
        choices=("all", *VIEW_NAMES),
        default=None,
        help="Filter the view (default: identifier normally, all with --name)",
    )
    parser.add_argument(
        "--name",
        help="Filter on subject.name and show matching views (all by default)",
    )
    parser.add_argument(
        "--name-match-mode",
        choices=NAME_MATCH_MODES,
        default="contain",
    )
    parser.add_argument(
        "--per-role",
        "--per-concept",
        dest="per_role",
        type=int,
        default=None,
        help="Maximum variables per role (default: 10 normally, unlimited with --name)",
    )
    parser.add_argument("--context", type=int, default=2)
    parser.add_argument(
        "--max-span-lines",
        type=int,
        default=20,
        help="Maximum rendered lines per view; use 0 for unlimited",
    )
    parser.add_argument("--seed", type=int, default=None)
    args = parser.parse_args()

    if args.per_role is not None and args.per_role < 0:
        parser.error("--per-role must be non-negative")
    if args.context < 0:
        parser.error("--context must be non-negative")
    if args.max_span_lines < 0:
        parser.error("--max-span-lines must be non-negative")
    if "all" in args.role and len(args.role) > 1:
        parser.error("--role all cannot be combined with another role")
    if args.exclude_roles and args.role == ["all"]:
        parser.error("--exclude-roles requires one or more explicit roles")
    if args.seed is not None:
        random.seed(args.seed)

    variable_facts_path = args.variable_facts or Path(
        f"outputs/{args.split}_variable_facts.json"
    )
    code_root = args.code_root or Path("code") / args.split
    variables = load_json(variable_facts_path)

    if args.name is not None:
        requested_view = args.view or "all"
        all_matches = matching_named_variables(
            variables,
            args.name,
            args.name_match_mode,
            args.role,
            args.exclude_roles,
        )
        all_matches = [
            variable
            for variable in all_matches
            if available_views(variable, requested_view)
        ]
        matches = limit_variables_per_role(
            all_matches,
            args.role,
            args.exclude_roles,
            args.per_role,
        )
        print(
            f"\n{'=' * 80}\n"
            f"name {args.name_match_mode} {args.name!r}: "
            f"{len(matches)} shown from {len(all_matches)} variables\n"
            f"{'=' * 80}"
        )
        for variable in matches:
            for view_name, view_item in view_items(variable, requested_view):
                print(
                    render(
                        variable,
                        role_label(variable),
                        view_name,
                        view_item,
                        code_root,
                        args.context,
                        args.max_span_lines,
                    )
                )
                print()
        return

    requested_view = args.view or "identifier"
    per_role = args.per_role if args.per_role is not None else 10
    grouped = group_variables(
        variables, args.role, requested_view, exclude_roles=args.exclude_roles
    )

    roles = sorted(grouped)
    if not args.exclude_roles and args.role != ["all"]:
        roles = list(args.role)
    elif args.exclude_roles and not roles:
        roles = ["not " + ",".join(args.role)]
    for role in roles:
        candidates = grouped.get(role, [])
        sample = random.sample(candidates, min(per_role, len(candidates)))
        print(
            f"\n{'=' * 80}\n"
            f"{role}: {len(sample)} shown from {len(candidates)}\n"
            f"{'=' * 80}"
        )
        for variable in sample:
            view_name, view_item = choose_view(variable, requested_view)
            displayed_role = role_label(variable) if args.exclude_roles else role
            print(
                render(
                    variable,
                    displayed_role,
                    view_name,
                    view_item,
                    code_root,
                    args.context,
                    args.max_span_lines,
                )
            )
            print()


if __name__ == "__main__":
    main()
