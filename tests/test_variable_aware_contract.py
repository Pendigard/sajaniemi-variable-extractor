from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from src.python.variable_aware import (
    MINIMAL_VIEW_KEYS,
    SAJANIEMI_ROLES,
    SourceResolver,
    legacy_annotations_from_roles,
    resolve_variable_aware_output,
)
from tests.test_joern_integration import looks_like_python_variable_introduction

FORBIDDEN_ANNOTATION_FIELDS = {
    "facts",
    "evidence",
    "role_evidence",
    "extra_fact_evidence",
}
ROLE_RECORD_FIELDS = {"schema_version", "concept", "subject", "views"}
VARIABLE_FACT_RECORD_FIELDS = {"schema_version", "subject", "is_collection", "roles", "views"}


def assert_obsolete_fields_absent(test: unittest.TestCase, record: dict) -> None:
    for field in FORBIDDEN_ANNOTATION_FIELDS:
        test.assertNotIn(field, record)


def location(start: int, end: int, line: int, start_column: int, end_column: int) -> dict:
    return {
        "start_byte": start,
        "end_byte": end,
        "start_line": line,
        "start_column": start_column,
        "end_line": line,
        "end_column": end_column,
    }


ROLE_ANNOTATIONS = [
    {
        "schema_version": 1,
        "concept": {"family": "sajaniemi_role", "name": "most_recent_holder"},
        "subject": {
            "id": "Python/example.py::choose::local::state:2:5",
            "joern_id": 101,
            "name": "state",
            "kind": "local",
            "path": "Python/example.py",
            "scope": {"type": "function", "name": "choose", "qualified_name": "choose"},
        },
        "views": {
            "declaration": [
                {"id": "declaration:0", "location": location(24, 36, 2, 5, 17), "code": "state = None"}
            ],
            "identifier": [
                {"id": "identifier:0", "usage": "declaration", "location": location(24, 29, 2, 5, 10), "code": "state"},
                {"id": "identifier:1", "usage": "write", "location": location(70, 75, 4, 9, 14), "code": "state"},
                {"id": "identifier:2", "usage": "read", "location": location(94, 99, 5, 11, 16), "code": "state"},
            ],
            "reads": [
                {"id": "read:0", "location": location(88, 100, 5, 5, 17), "code": "print(state)"}
            ],
            "writes": [
                {"id": "write:0", "location": location(70, 83, 4, 9, 22), "code": "state = value"}
            ],
            "updates": [],
            "state_mutations": [],
            "control_context": [],
            "scope": [
                {
                    "id": "scope:0",
                    "location": location(0, 19, 1, 1, 20),
                    "code": "def choose(values):",
                    "type": "function",
                    "relation": "direct_scope",
                }
            ],
        },
    }
]

VARIABLE_FACTS = [
    {
        "schema_version": 1,
        "subject": ROLE_ANNOTATIONS[0]["subject"],
        "is_collection": False,
        "roles": ["most_recent_holder"],
        "views": ROLE_ANNOTATIONS[0]["views"],
    },
    {
        "schema_version": 1,
        "subject": {
            "id": "Python/example.py::choose::parameter::values:1:12",
            "joern_id": 102,
            "name": "values",
            "kind": "parameter",
            "path": "Python/example.py",
            "scope": {"type": "function", "name": "choose", "qualified_name": "choose"},
        },
        "is_collection": False,
        "roles": [],
        "views": {key: [] for key in MINIMAL_VIEW_KEYS},
    },
]

EXPECTED_LEGACY = [
    {"concept": "most_recent_holder", "variant": "declaration", "path": "Python/example.py", "line": 2, "column": 5, "line_end": 2, "column_end": 10, "name": "state", "code": "state"},
    {"concept": "most_recent_holder", "variant": "reference", "path": "Python/example.py", "line": 2, "column": 5, "line_end": 2, "column_end": 10, "name": "state", "code": "state"},
    {"concept": "most_recent_holder", "variant": "reference", "path": "Python/example.py", "line": 4, "column": 9, "line_end": 4, "column_end": 14, "name": "state", "code": "state"},
    {"concept": "most_recent_holder", "variant": "reference", "path": "Python/example.py", "line": 5, "column": 11, "line_end": 5, "column_end": 16, "name": "state", "code": "state"},
]

SOURCE = """def choose(values):
    state = None
    for value in values:
        state = value
    print(state)
"""


class VariableAwareContractTests(unittest.TestCase):
    def test_scope_resolver_handles_multiline_headers_across_languages(self) -> None:
        cases = {
            "C/example.c": (
                "int compute_total(\n    const int *values,\n    size_t count\n) {\n    int total = 0;\n}\n",
                "compute_total", "block", 1, 1, 4, 3,
                "int compute_total(\n    const int *values,\n    size_t count\n) {",
            ),
            "C++/example.cpp": (
                "Parser::Parser(\n    const Input& input\n) : cache_{input.id} {\n    int state = 0;\n}\n",
                "Parser", "block", 1, 1, 3, 22,
                "Parser::Parser(\n    const Input& input\n) : cache_{input.id} {",
            ),
            "JavaScript/example.js": (
                "function computeTotal(\n  values\n) {\n  let total = 0;\n}\n",
                "computeTotal", "block", 1, 1, 3, 3,
                "function computeTotal(\n  values\n) {",
            ),
            "Python/example.py": (
                "def compute_total(\n    values: list[int],\n) -> int:\n    total = 0\n",
                "compute_total", "python_def", 1, 1, 1, 1,
                "def compute_total(\n    values: list[int],\n) -> int:",
            ),
            "Ruby/example.rb": (
                "def compute_total(\n  values\n)\n  total = 0\nend\n",
                "compute_total", "ruby_def", 1, 1, 1, 1,
                "def compute_total(\n  values\n)",
            ),
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path, (source, *_rest) in cases.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source, encoding="utf-8")
            resolver = SourceResolver(root)
            for path, (
                source, name, boundary, line, column, end_line, end_column, expected
            ) in cases.items():
                item = {
                    "usage": "scope",
                    "type": "function",
                    "relation": "direct_scope",
                    "_hint": {
                        "path": path,
                        "line": line,
                        "column": column,
                        "end_line": end_line,
                        "end_column": end_column,
                        "name": name,
                        "boundary": boundary,
                    },
                }
                resolved = resolver.scope(item)
                self.assertIsNotNone(resolved, path)
                assert resolved is not None
                self.assertEqual(resolved["code"], expected, path)
                span = resolved["location"]
                encoded = source.encode("utf-8")
                self.assertEqual(
                    encoded[span["start_byte"]:span["end_byte"]].decode("utf-8"),
                    expected,
                    path,
                )
                self.assertNotIn("total = 0", resolved["code"], path)

    def test_scope_resolver_omits_ambiguous_or_synthetic_headers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "JavaScript/example.js"
            source_path.parent.mkdir(parents=True)
            source_path.write_text("if (ready) {\n  let value = 1;\n}\n", encoding="utf-8")
            resolver = SourceResolver(root)
            item = {
                "_hint": {
                    "path": "JavaScript/example.js",
                    "line": 1,
                    "column": 1,
                    "end_line": 1,
                    "end_column": 12,
                    "name": "computeTotal",
                    "boundary": "block",
                }
            }
            self.assertIsNone(resolver.scope(item))

    def test_scope_view_is_an_exact_single_line_function_header(self) -> None:
        scope_item = ROLE_ANNOTATIONS[0]["views"]["scope"][0]
        self.assertTrue({"type", "relation", "location", "code"}.issubset(scope_item))
        self.assertEqual(scope_item["type"], "function")
        self.assertEqual(scope_item["relation"], "direct_scope")
        self.assertEqual(scope_item["code"], "def choose(values):")
        self.assertNotIn("\n", scope_item["code"])
        span = scope_item["location"]
        self.assertEqual(SOURCE[span["start_byte"]:span["end_byte"]], scope_item["code"])

    def test_state_mutations_are_separate_from_binding_changes(self) -> None:
        mutations = {
            "items.append(value)",
            "items.sort()",
            "items[i] = normalize(items[i])",
        }
        views = {
            "state_mutations": mutations,
            "writes": {"items = new_items"},
            "updates": {"items = items + new_items"},
        }
        self.assertTrue(views["state_mutations"].isdisjoint(views["writes"]))
        self.assertTrue(views["state_mutations"].isdisjoint(views["updates"]))

    def test_control_context_items_are_headers_with_required_metadata(self) -> None:
        contexts = [
            {"type": "loop", "relation": "contains_read", "location": location(0, 20, 1, 1, 21), "code": "for value in values:"},
            {"type": "loop", "relation": "contains_update", "location": location(21, 50, 2, 1, 30), "code": "while stepper < len(values):"},
            {"type": "conditional", "relation": "guards_write", "location": location(51, 67, 3, 1, 17), "code": "if value > best:"},
        ]
        for item in contexts:
            self.assertEqual(set(item), {"type", "relation", "location", "code"})
            self.assertNotIn("\n", item["code"])
            self.assertTrue(item["code"].rstrip().endswith(":"))

    def test_subject_scope_has_stable_minimal_shape(self) -> None:
        for record in ROLE_ANNOTATIONS + VARIABLE_FACTS:
            scope = record["subject"]["scope"]
            self.assertEqual(set(scope), {"type", "name", "qualified_name"})
            self.assertTrue(all(isinstance(value, str) and value for value in scope.values()))

    def test_updates_are_separate_from_plain_writes_and_reads(self) -> None:
        views_by_variable = {
            "stepper": {
                "updates": ["stepper += 1"], "writes": [], "reads": []
            },
            "gatherer": {
                "updates": ["gatherer += value"], "writes": [], "reads": []
            },
            "left_self": {
                "updates": ["left_self = left_self + value"], "writes": [], "reads": []
            },
            "right_self": {
                "updates": ["right_self = value + right_self"], "writes": [], "reads": []
            },
            "state": {
                "updates": [], "writes": ["state = new_state"], "reads": []
            },
        }

        for variable, views in views_by_variable.items():
            updates = set(views["updates"])
            writes = set(views["writes"])
            reads = set(views["reads"])
            self.assertTrue(updates.isdisjoint(writes), variable)
            self.assertTrue(updates.isdisjoint(reads), variable)
        self.assertEqual(views_by_variable["state"]["writes"], ["state = new_state"])

    def test_python_introduction_shape_rejects_callee_expressions(self) -> None:
        self.assertTrue(looks_like_python_variable_introduction("value = source", "value"))
        self.assertTrue(looks_like_python_variable_introduction("value += source", "value"))
        self.assertTrue(looks_like_python_variable_introduction("for value in values:", "value"))
        for callee, code in {
            "print": "print(value)",
            "len": "len(values)",
            "max": "max(a, b)",
            "range": "range(n)",
        }.items():
            self.assertFalse(looks_like_python_variable_introduction(code, callee))

    def test_role_annotations_have_one_record_per_variable_and_role(self) -> None:
        identities = set()
        for record in ROLE_ANNOTATIONS:
            self.assertEqual(set(record), ROLE_RECORD_FIELDS)
            assert_obsolete_fields_absent(self, record)
            self.assertEqual(record["concept"]["family"], "sajaniemi_role")
            self.assertIn(record["concept"]["name"], SAJANIEMI_ROLES)
            self.assertNotEqual(record["concept"]["name"], "future_mutated_variable")
            self.assertTrue(MINIMAL_VIEW_KEYS.issubset(record["views"]))
            identity = (record["subject"]["id"], record["concept"]["name"])
            self.assertNotIn(identity, identities)
            identities.add(identity)

    def test_variable_facts_include_an_unannotated_variable(self) -> None:
        self.assertTrue(any(record["roles"] == [] for record in VARIABLE_FACTS))
        for record in VARIABLE_FACTS:
            self.assertEqual(set(record), VARIABLE_FACT_RECORD_FIELDS)
            assert_obsolete_fields_absent(self, record)
            self.assertTrue(MINIMAL_VIEW_KEYS.issubset(record["views"]))
            self.assertTrue(set(record["roles"]).issubset(SAJANIEMI_ROLES))

    def test_minimal_views_keep_token_and_context_semantics_distinct(self) -> None:
        views = ROLE_ANNOTATIONS[0]["views"]
        name = ROLE_ANNOTATIONS[0]["subject"]["name"]
        self.assertTrue(all(item["code"] == name for item in views["identifier"]))
        self.assertEqual(views["declaration"][0]["code"], "state = None")
        self.assertEqual(views["reads"][0]["code"], "print(state)")
        self.assertEqual(views["writes"][0]["code"], "state = value")
        for view_name in MINIMAL_VIEW_KEYS:
            for item in views[view_name]:
                span = item["location"]
                self.assertEqual(SOURCE[span["start_byte"]:span["end_byte"]], item["code"])

    def test_legacy_conversion_uses_identifier_views_only(self) -> None:
        converted = legacy_annotations_from_roles(ROLE_ANNOTATIONS)
        self.assertEqual(converted, EXPECTED_LEGACY)
        self.assertTrue(all(item["concept"] != "future_mutated_variable" for item in converted))

    def test_resolver_produces_exact_utf8_token_and_statement_spans(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = "def choose(value):\n    café = value\n    print(café)\n    café = value\n"
            source_path = root / "Python/example.py"
            source_path.parent.mkdir(parents=True)
            source_path.write_text(source, encoding="utf-8")

            subject = {
                "id": "example",
                "joern_id": 7,
                "name": "café",
                "kind": "local",
                "path": "example.py",
                "scope": {"type": "file", "name": "example.py", "qualified_name": "example.py"},
                "_declaration_line": 2,
                "_declaration_column": 5,
            }
            hint = lambda line, column, usage: {
                "usage": usage,
                "_hint": {
                    "path": "example.py",
                    "line": line,
                    "column": column,
                    "name": "café",
                    "code": "café",
                },
            }
            views = {
                "declaration": [hint(2, 5, "declaration")],
                "identifier": [
                    hint(2, 5, "declaration"),
                    hint(3, 11, "read"),
                    hint(4, 5, "write"),
                ],
                "reads": [hint(3, 11, "read")],
                "writes": [hint(4, 5, "write")],
                "updates": [],
                "state_mutations": [],
                "control_context": [],
                "scope": [{
                    "usage": "scope",
                    "type": "function",
                    "relation": "direct_scope",
                    "_hint": {
                        "path": "example.py",
                        "line": 1,
                        "column": 1,
                        "name": "choose",
                        "code": "def choose(value):",
                        "end_line": 1,
                        "end_column": 19,
                        "language": "PYTHONSRC",
                        "boundary": "python_def",
                    },
                }],
            }
            role = {
                "schema_version": 1,
                "concept": {"family": "sajaniemi_role", "name": "most_recent_holder"},
                "subject": subject,
                "views": views,
            }
            facts = {
                "schema_version": 1,
                "subject": subject,
                "roles": ["most_recent_holder"],
                "views": views,
            }
            pre_output = root / "pre.json"
            pre_output.write_text(
                json.dumps({"schema_version": 1, "role_annotations": [role], "variable_facts": [facts]}),
                encoding="utf-8",
            )

            resolved = resolve_variable_aware_output(pre_output, root)
            record = resolved["role_annotations"][0]
            self.assertEqual(set(record), ROLE_RECORD_FIELDS)
            assert_obsolete_fields_absent(self, record)
            self.assertEqual(set(resolved["variable_facts"][0]), VARIABLE_FACT_RECORD_FIELDS)
            assert_obsolete_fields_absent(self, resolved["variable_facts"][0])
            self.assertEqual(set(record["views"]), set(MINIMAL_VIEW_KEYS))
            self.assertEqual(record["subject"]["path"], "Python/example.py")
            self.assertEqual(record["views"]["declaration"][0]["code"], "café = value")
            self.assertEqual(record["views"]["reads"][0]["code"], "print(café)")
            self.assertEqual(record["views"]["writes"][0]["code"], "café = value")
            self.assertEqual(record["views"]["updates"], [])
            self.assertEqual(record["views"]["scope"][0]["code"], "def choose(value):")
            self.assertEqual(record["views"]["scope"][0]["id"], "scope:0")
            self.assertEqual(record["views"]["scope"][0]["type"], "function")
            self.assertEqual(record["views"]["scope"][0]["relation"], "direct_scope")
            identifier = record["views"]["identifier"][1]
            encoded = source.encode("utf-8")
            span = identifier["location"]
            self.assertEqual(encoded[span["start_byte"]:span["end_byte"]].decode("utf-8"), "café")
            self.assertGreater(span["end_byte"] - span["start_byte"], len("café"))

    def test_resolver_merges_frontend_duplicates_and_uses_loop_header_declaration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "Python/example.py"
            source_path.parent.mkdir(parents=True)
            source_path.write_text(
                "for organizer_index in range(2):\n"
                "    items[organizer_index] = items[organizer_index]\n",
                encoding="utf-8",
            )

            def hint(line: int, column: int, usage: str) -> dict:
                return {
                    "usage": usage,
                    "_hint": {
                        "path": "example.py",
                        "line": line,
                        "column": column,
                        "name": "organizer_index",
                        "code": "organizer_index",
                    },
                }

            subject = {
                "id": "frontend-local",
                "joern_id": 8,
                "name": "organizer_index",
                "kind": "local",
                "path": "example.py",
                "scope": {
                    "type": "file",
                    "name": "example.py",
                    "qualified_name": "example.py",
                },
                "_declaration_line": 2,
                "_declaration_column": 42,
            }
            views = {
                "declaration": [hint(2, 42, "declaration")],
                "identifier": [
                    hint(1, 5, "read"),
                    hint(2, 11, "read"),
                    hint(2, 36, "declaration"),
                ],
                "reads": [hint(1, 5, "read"), hint(2, 11, "read")],
                "writes": [],
                "updates": [],
                "state_mutations": [],
                "control_context": [],
                "scope": [],
            }
            duplicate = {
                "schema_version": 1,
                "subject": {**subject, "joern_id": 9},
                "is_collection": True,
                "roles": [],
                "views": {
                    "declaration": [hint(2, 36, "declaration")],
                    "identifier": [hint(2, 36, "declaration")],
                    "reads": [],
                    "writes": [],
                    "updates": [],
                    "state_mutations": [],
                    "control_context": [],
                    "scope": [],
                },
            }
            facts = {
                "schema_version": 1,
                "subject": subject,
                "is_collection": False,
                "roles": ["temporary"],
                "views": views,
            }
            role = {
                "schema_version": 1,
                "concept": {"family": "sajaniemi_role", "name": "temporary"},
                "subject": subject,
                "views": views,
            }
            pre_output = root / "pre.json"
            pre_output.write_text(
                json.dumps({
                    "schema_version": 1,
                    "role_annotations": [role],
                    "variable_facts": [facts, duplicate],
                }),
                encoding="utf-8",
            )

            resolved = resolve_variable_aware_output(pre_output, root)
            self.assertEqual(len(resolved["variable_facts"]), 1)
            record = resolved["variable_facts"][0]
            self.assertEqual(set(record), VARIABLE_FACT_RECORD_FIELDS)
            assert_obsolete_fields_absent(self, record)
            self.assertEqual(record["roles"], ["temporary"])
            self.assertIs(record["is_collection"], True)
            self.assertEqual(record["views"]["declaration"][0]["code"], "for organizer_index in range(2):")
            declaration_tokens = [
                item for item in record["views"]["identifier"] if item["usage"] == "declaration"
            ]
            self.assertEqual(len(declaration_tokens), 1)
            self.assertEqual(declaration_tokens[0]["location"]["start_line"], 1)
            self.assertEqual(
                [item["code"] for item in record["views"]["reads"]],
                ["items[organizer_index] = items[organizer_index]"],
            )

    def test_same_line_same_name_in_distinct_callables_is_not_merged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "JavaScript/example.js"
            source_path.parent.mkdir(parents=True)
            source = "const left = () => { let item = 1; return item; }; const right = () => { let item = 2; return item; };\n"
            source_path.write_text(source, encoding="utf-8")

            def record(joern_id: int, column: int, scope_name: str) -> dict:
                hint = {
                    "usage": "declaration",
                    "_hint": {"path": "example.js", "line": 1, "column": column,
                              "name": "item", "code": "item"},
                }
                subject = {
                    "id": f"local:{joern_id}", "joern_id": joern_id, "name": "item", "kind": "local",
                    "path": "example.js", "scope": {"type": "function", "name": scope_name,
                    "qualified_name": scope_name}, "_declaration_line": 1, "_declaration_column": column,
                }
                views = {name: [] for name in MINIMAL_VIEW_KEYS}
                views["declaration"] = [hint]
                views["identifier"] = [hint]
                return {"schema_version": 1, "subject": subject, "is_collection": False,
                        "roles": ["fixed_value"], "views": views}

            first_column = source.index("item") + 1
            second_column = source.index("item", source.index("const right")) + 1
            facts = [record(1, first_column, "left"), record(2, second_column, "right")]
            pre_output = root / "pre.json"
            pre_output.write_text(json.dumps({"schema_version": 1, "role_annotations": [],
                                              "variable_facts": facts}), encoding="utf-8")
            resolved = resolve_variable_aware_output(pre_output, root)
            self.assertEqual(len(resolved["variable_facts"]), 2)
            self.assertEqual({item["subject"]["scope"]["qualified_name"]
                              for item in resolved["variable_facts"]}, {"left", "right"})


if __name__ == "__main__":
    unittest.main()
