from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.show_random_annotations import (
    GREEN,
    NO_ROLE,
    RESET,
    all_view_items,
    group_variables,
    limit_variables_per_role,
    matches_name,
    matching_named_variables,
    render,
    view_items,
)


def view_item(start: int, end: int, line: int, column: int, code: str) -> dict:
    return {
        "id": "identifier:0",
        "location": {
            "start_byte": start,
            "end_byte": end,
            "start_line": line,
            "start_column": column,
            "end_line": line,
            "end_column": column + len(code),
        },
        "code": code,
    }


def variable(path: str, roles: list[str], identifier: dict, reads: list[dict] | None = None) -> dict:
    return {
        "schema_version": 1,
        "subject": {"id": path, "name": identifier["code"], "path": path},
        "roles": roles,
        "views": {
            "declaration": [],
            "identifier": [identifier],
            "reads": reads or [],
            "writes": [],
            "updates": [],
            "state_mutations": [],
            "control_context": [],
            "scope": [],
        },
    }


class ShowRandomAnnotationsTests(unittest.TestCase):
    def test_groups_variables_by_role_and_includes_roleless_variables(self) -> None:
        identifier = view_item(0, 1, 1, 1, "x")
        classified = variable("Python/a.py", ["stepper", "walker"], identifier)
        roleless = variable("Python/b.py", [], identifier)

        grouped = group_variables([classified, roleless], ["all"], "identifier")

        self.assertEqual(grouped["stepper"], [classified])
        self.assertEqual(grouped["walker"], [classified])
        self.assertEqual(grouped[NO_ROLE], [roleless])
        self.assertEqual(
            group_variables([classified, roleless], [NO_ROLE], "identifier"),
            {NO_ROLE: [roleless]},
        )

    def test_excludes_variables_without_the_selected_view(self) -> None:
        identifier = view_item(0, 1, 1, 1, "x")
        record = variable("Python/a.py", ["stepper"], identifier)

        self.assertEqual(group_variables([record], ["stepper"], "updates"), {})

    def test_name_matching_modes_use_the_exact_subject_name(self) -> None:
        record = variable("Python/a.py", [], view_item(0, 10, 1, 1, "tmp_value"))

        self.assertTrue(matches_name(record, "value", "contain"))
        self.assertTrue(matches_name(record, "tmp", "prefix"))
        self.assertTrue(matches_name(record, "value", "suffix"))
        self.assertTrue(matches_name(record, "tmp_value", "exact_match"))
        self.assertFalse(matches_name(record, "Tmp", "prefix"))
        self.assertFalse(matches_name(record, "tmp", "exact_match"))

    def test_negative_multirole_filter_excludes_any_intersection(self) -> None:
        identifier = view_item(0, 1, 1, 1, "x")
        stepper = variable("Python/a.py", ["stepper"], identifier)
        multilabel = variable("Python/b.py", ["walker", "temporary"], identifier)
        roleless = variable("Python/c.py", [], identifier)

        matches = matching_named_variables(
            [stepper, multilabel, roleless],
            "x",
            "exact_match",
            ["stepper", "temporary"],
            exclude_roles=True,
        )

        self.assertEqual(matches, [roleless])

    def test_name_mode_can_return_every_item_from_every_view(self) -> None:
        identifier = view_item(0, 1, 1, 1, "x")
        read = view_item(2, 3, 1, 3, "x")
        record = variable("Python/a.py", [], identifier, reads=[read])

        self.assertEqual(
            [(name, item["code"]) for name, item in all_view_items(record)],
            [("identifier", "x"), ("reads", "x")],
        )
        self.assertEqual(view_items(record, "reads"), [("reads", read)])

    def test_name_mode_limit_is_applied_per_role(self) -> None:
        identifier = view_item(0, 1, 1, 1, "x")
        variables = [
            variable(f"Python/{index}.py", ["stepper"], identifier)
            for index in range(3)
        ] + [
            variable(f"Python/w{index}.py", ["walker"], identifier)
            for index in range(3)
        ]

        selected = limit_variables_per_role(
            variables, ["all"], exclude_roles=False, limit=2
        )

        self.assertEqual(sum("stepper" in record["roles"] for record in selected), 2)
        self.assertEqual(sum("walker" in record["roles"] for record in selected), 2)

    def test_render_uses_split_source_path_and_utf8_byte_offsets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            code_root = Path(tmp) / "code" / "train"
            source_path = code_root / "Python" / "utf8.py"
            source_path.parent.mkdir(parents=True)
            source_path.write_text("préfixe = 0\nvaleur = préfixe\n", encoding="utf-8")
            data = source_path.read_bytes()
            start = data.index("préfixe".encode("utf-8"), data.index(b"\n"))
            end = start + len("préfixe".encode("utf-8"))
            item = view_item(start, end, 2, 10, "préfixe")
            record = variable("Python/utf8.py", ["most_recent_holder"], item)

            rendered = render(
                record,
                "most_recent_holder",
                "identifier",
                item,
                code_root,
                context=0,
                max_span_lines=20,
            )

        self.assertIn("code/train/Python/utf8.py:2:10-2", rendered)
        self.assertIn(f"{GREEN}préfixe{RESET}", rendered)


if __name__ == "__main__":
    unittest.main()
