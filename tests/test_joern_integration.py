from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from src.python.variable_aware import (
    MINIMAL_VIEW_KEYS,
    SAJANIEMI_ROLES,
    legacy_annotations_from_roles,
    resolve_variable_aware_output,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CODE_ROOT = REPOSITORY_ROOT / "tests/fixtures/code"
PYTHON_SOURCE_ROOT = CODE_ROOT / "Python"
SCALA_SCRIPT = REPOSITORY_ROOT / "src/scala/extract_dynamic_variables.sc"
EXPECTED_PATH = REPOSITORY_ROOT / "tests/fixtures/expected/role_declarations.json"
EXPECTED_ROLE_RECORD_COUNT = 49
EXPECTED_PRE_VARIABLE_COUNT = 68
EXPECTED_VARIABLE_COUNT = 52
EXPECTED_UPDATE_VIEW_COUNT = 7
EXPECTED_STATE_MUTATION_VIEW_COUNT = 5
EXPECTED_CONTROL_CONTEXT_VIEW_COUNT = 75
EXPECTED_SCOPE_VIEW_COUNT = 52
EXPECTED_LEGACY_COUNT = 200
EXPECTED_LEGACY_SHA256 = "445de8d02d10af81804abedcc577b43784618a98b76689e5b01d848946913b20"
EXPECTED_ROLE_RECORD_COUNTS = {
    "container": 1,
    "fixed_value": 14,
    "follower": 2,
    "gatherer": 2,
    "most_recent_holder": 3,
    "most_wanted_holder": 3,
    "one_way_flag": 3,
    "organizer": 2,
    "stepper": 3,
    "temporary": 1,
    "walker": 15,
}
EXPECTED_LEGACY_COUNTS = {
    "container": 6,
    "fixed_value": 62,
    "follower": 9,
    "gatherer": 9,
    "most_recent_holder": 12,
    "most_wanted_holder": 15,
    "one_way_flag": 13,
    "organizer": 8,
    "stepper": 15,
    "temporary": 3,
    "walker": 48,
}
FORBIDDEN_CALLEE_SUBJECTS = {"len", "list", "print", "float", "max", "range"}
FORBIDDEN_OUTPUT_FIELDS = {
    "facts",
    "evidence",
    "role_evidence",
    "extra_fact_evidence",
}
ROLE_RECORD_FIELDS = {"schema_version", "concept", "subject", "views"}
VARIABLE_FACT_RECORD_FIELDS = {"schema_version", "subject", "is_collection", "roles", "views"}
REQUIRED_SOURCE_VARIABLES = {
    "stepper",
    "gatherer",
    "walker",
    "wanted_value",
    "organizer_index",
    "container",
    "permuted_organizer_items",
    "gathered_value",
    "container_value",
}


def looks_like_python_variable_introduction(code: str, name: str) -> bool:
    """Accept source binding forms, never a bare call that merely mentions a name."""
    quoted = re.escape(name)
    assignment = rf"^\s*{quoted}\s*(?::\s*[^=]+)?\s*(?:\*\*=|//=|>>=|<<=|[+\-*/%@&|^]=|:=|=(?!=)).*$"
    loop_binding = rf"^\s*(?:async\s+)?for\s+(?:\(\s*)?{quoted}\s+in\b.*$"
    return bool(re.match(assignment, code, flags=re.DOTALL) or re.match(loop_binding, code, flags=re.DOTALL))


def assert_view_spans(test: unittest.TestCase, records: list[dict]) -> None:
    source_bytes = {
        f"Python/{path.name}": path.read_bytes()
        for path in PYTHON_SOURCE_ROOT.glob("*.py")
    }
    for record in records:
        test.assertEqual(set(record["views"]), set(MINIMAL_VIEW_KEYS))
        for view_name, items in record["views"].items():
            for item in items:
                location = item["location"]
                extracted = source_bytes[record["subject"]["path"]][
                    location["start_byte"]:location["end_byte"]
                ].decode("utf-8")
                test.assertEqual(extracted, item["code"])
                if view_name == "identifier":
                    test.assertEqual(extracted, record["subject"]["name"])


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_INTEGRATION") == "1",
    "set RUN_JOERN_INTEGRATION=1 to run the real Joern batch integration test",
)
class JoernBatchIntegrationTests(unittest.TestCase):
    def test_python_fixture_builds_and_extracts_variable_aware_annotations(self) -> None:
        if shutil.which("joern-parse") is None or shutil.which("joern") is None:
            self.skipTest("joern and joern-parse are required")

        with tempfile.TemporaryDirectory(prefix="sajaniemi-joern-test-") as directory:
            temporary_root = Path(directory)
            graph = temporary_root / "python.bin"
            pre_output = temporary_root / "pre_output.json"
            subprocess.run(
                ["joern-parse", str(PYTHON_SOURCE_ROOT), "--output", str(graph), "--language", "pythonsrc"],
                check=True,
                cwd=temporary_root,
                timeout=240,
            )
            subprocess.run(
                [
                    "joern",
                    str(graph),
                    "--script",
                    str(SCALA_SCRIPT),
                    "--param",
                    f"output={pre_output}",
                    "--param",
                    f"sourceRoot={CODE_ROOT}",
                    "--nocolors",
                ],
                check=True,
                cwd=temporary_root,
                timeout=240,
            )

            raw = json.loads(pre_output.read_text(encoding="utf-8"))
            self.assertEqual(set(raw), {"schema_version", "role_annotations", "variable_facts"})
            self.assertEqual(len(raw["role_annotations"]), EXPECTED_ROLE_RECORD_COUNT)
            self.assertEqual(len(raw["variable_facts"]), EXPECTED_PRE_VARIABLE_COUNT)
            for record in raw["role_annotations"]:
                self.assertEqual(set(record), ROLE_RECORD_FIELDS)
                for field in FORBIDDEN_OUTPUT_FIELDS:
                    self.assertNotIn(field, record)
            for record in raw["variable_facts"]:
                self.assertEqual(set(record), VARIABLE_FACT_RECORD_FIELDS)
                for field in FORBIDDEN_OUTPUT_FIELDS:
                    self.assertNotIn(field, record)
            raw_subject_names = {
                record["subject"]["name"]
                for section in ("role_annotations", "variable_facts")
                for record in raw[section]
            }
            self.assertTrue(FORBIDDEN_CALLEE_SUBJECTS.isdisjoint(raw_subject_names))

            resolved = resolve_variable_aware_output(pre_output, CODE_ROOT)
            roles = resolved["role_annotations"]
            facts = resolved["variable_facts"]
            self.assertEqual(len(roles), EXPECTED_ROLE_RECORD_COUNT)
            self.assertEqual(len(facts), EXPECTED_VARIABLE_COUNT)
            role_subject_names = {record["subject"]["name"] for record in roles}
            fact_subject_names = {record["subject"]["name"] for record in facts}
            self.assertTrue(FORBIDDEN_CALLEE_SUBJECTS.isdisjoint(role_subject_names))
            self.assertTrue(FORBIDDEN_CALLEE_SUBJECTS.isdisjoint(fact_subject_names))
            self.assertTrue(REQUIRED_SOURCE_VARIABLES.issubset(fact_subject_names))
            self.assertEqual(
                dict(Counter(record["concept"]["name"] for record in roles)),
                EXPECTED_ROLE_RECORD_COUNTS,
            )
            self.assertNotIn("future_mutated_variable", {record["concept"]["name"] for record in roles})
            self.assertTrue(all(record["concept"]["name"] in SAJANIEMI_ROLES for record in roles))
            self.assertEqual(
                len({(record["subject"]["id"], record["concept"]["name"]) for record in roles}),
                len(roles),
            )
            self.assertEqual(len({record["subject"]["id"] for record in facts}), len(facts))
            self.assertTrue(
                all(
                    set(record["subject"]["scope"]) == {"type", "name", "qualified_name"}
                    for record in roles + facts
                )
            )
            self.assertTrue(all(record["subject"]["kind"] in {"local", "parameter"} for record in facts))
            fact_ids = {record["subject"]["id"] for record in facts}
            self.assertTrue(all(record["subject"]["id"] in fact_ids for record in roles))
            self.assertTrue(any(not record["roles"] for record in facts))
            for record in roles:
                self.assertEqual(set(record), ROLE_RECORD_FIELDS)
                for field in FORBIDDEN_OUTPUT_FIELDS:
                    self.assertNotIn(field, record)
            for record in facts:
                self.assertEqual(set(record), VARIABLE_FACT_RECORD_FIELDS)
                for field in FORBIDDEN_OUTPUT_FIELDS:
                    self.assertNotIn(field, record)
            assert_view_spans(self, roles)
            assert_view_spans(self, facts)
            for record in roles + facts:
                codes_by_view = {
                    view: {item["code"] for item in record["views"][view]}
                    for view in ("reads", "writes", "updates")
                }
                self.assertTrue(
                    codes_by_view["updates"].isdisjoint(codes_by_view["writes"]),
                    f"updates duplicated as writes for {record['subject']['id']}",
                )
                self.assertTrue(
                    codes_by_view["updates"].isdisjoint(codes_by_view["reads"]),
                    f"self-updates duplicated as reads for {record['subject']['id']}",
                )
            for record in roles + facts:
                declaration_tokens = [
                    item
                    for item in record["views"]["identifier"]
                    if item["usage"] in {"declaration", "parameter"}
                ]
                self.assertEqual(len(declaration_tokens), 1)
            for record in facts:
                if record["subject"]["kind"] != "local":
                    continue
                declarations = record["views"]["declaration"]
                self.assertEqual(len(declarations), 1)
                self.assertTrue(
                    looks_like_python_variable_introduction(
                        declarations[0]["code"], record["subject"]["name"]
                    ),
                    f"not a source-level variable introduction: {record['subject']['name']!r} "
                    f"from {declarations[0]['code']!r}",
                )

            expected = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))["python"]
            actual_role_variables = {
                (record["concept"]["name"], record["subject"]["path"], record["subject"]["name"])
                for record in roles
            }
            must_have = {
                (item["concept"], item["path"], item["name"])
                for item in expected["must_have"]
            }
            must_not_have = {
                (item["concept"], item["path"], item["name"])
                for item in expected["must_not_have"]
            }
            self.assertTrue(must_have.issubset(actual_role_variables))
            self.assertTrue(must_not_have.isdisjoint(actual_role_variables))

            legacy = legacy_annotations_from_roles(roles)
            self.assertEqual(len(legacy), EXPECTED_LEGACY_COUNT)
            self.assertEqual(
                dict(Counter(annotation["concept"] for annotation in legacy)),
                EXPECTED_LEGACY_COUNTS,
            )
            canonical_legacy = sorted(
                legacy,
                key=lambda annotation: tuple(str(annotation[key]) for key in sorted(annotation)),
            )
            digest_payload = json.dumps(
                canonical_legacy,
                sort_keys=True,
                separators=(",", ":"),
                ensure_ascii=False,
            ).encode("utf-8")
            self.assertEqual(hashlib.sha256(digest_payload).hexdigest(), EXPECTED_LEGACY_SHA256)
            self.assertTrue(all(annotation["concept"] != "future_mutated_variable" for annotation in legacy))

            by_name = {record["subject"]["name"]: record for record in facts}
            self.assertEqual(by_name["fixed_value"]["views"]["declaration"][0]["code"], "fixed_value = 10")
            self.assertIn("print(temporary)", {item["code"] for item in by_name["temporary"]["views"]["reads"]})
            self.assertIn(
                "most_recent_holder = recent_value",
                {item["code"] for item in by_name["most_recent_holder"]["views"]["writes"]},
            )
            self.assertIn(
                "previous = current",
                {item["code"] for item in by_name["previous"]["views"]["writes"]},
            )
            self.assertIn(
                "gatherer += gathered_value",
                {item["code"] for item in by_name["gatherer"]["views"]["updates"]},
            )
            self.assertNotIn(
                "gatherer += gathered_value",
                {item["code"] for item in by_name["gatherer"]["views"]["writes"]},
            )
            self.assertIn(
                "stepper += 1",
                {item["code"] for item in by_name["stepper"]["views"]["updates"]},
            )
            self.assertNotIn(
                "stepper += 1",
                {item["code"] for item in by_name["stepper"]["views"]["writes"]},
            )
            self.assertIn(
                "gatherer_assignment = gatherer_assignment + assignment_value",
                {item["code"] for item in by_name["gatherer_assignment"]["views"]["updates"]},
            )
            self.assertNotIn(
                "gatherer_assignment = gatherer_assignment + assignment_value",
                {item["code"] for item in by_name["gatherer_assignment"]["views"]["writes"]},
            )
            update_code = {
                item["code"]
                for record in facts
                for item in record["views"]["updates"]
            }
            update_view_count = sum(len(record["views"]["updates"]) for record in facts)
            self.assertEqual(update_view_count, EXPECTED_UPDATE_VIEW_COUNT)
            self.assertFalse(any(".append(" in code or ".sort(" in code for code in update_code))
            self.assertNotIn(
                "indexed_organizer_items[organizer_index] = normalize(indexed_organizer_items[organizer_index])",
                update_code,
            )
            mutation_code = {
                item["code"]
                for record in facts
                for item in record["views"]["state_mutations"]
            }
            state_mutation_view_count = sum(
                len(record["views"]["state_mutations"]) for record in facts
            )
            self.assertIn("container_items.append(container_value)", mutation_code)
            self.assertIn("container_items.pop()", mutation_code)
            self.assertIn("organizer_items.sort()", mutation_code)
            self.assertIn("permuted_organizer_items.reverse()", mutation_code)
            indexed_mutation = (
                "indexed_organizer_items[organizer_index] = "
                "normalize(indexed_organizer_items[organizer_index])"
            )
            self.assertIn(indexed_mutation, mutation_code)
            binding_change_code = {
                item["code"]
                for record in facts
                for view_name in ("writes", "updates")
                for item in record["views"][view_name]
            }
            self.assertTrue(mutation_code.isdisjoint(binding_change_code))
            self.assertEqual(
                {
                    item["mutation_kind"]
                    for item in by_name["container_items"]["views"]["state_mutations"]
                },
                {"append", "pop"},
            )
            self.assertEqual(
                by_name["indexed_organizer_items"]["views"]["state_mutations"][0]["mutation_kind"],
                "element_transform",
            )
            self.assertNotIn("organizer", by_name["indexed_organizer_items"]["roles"])
            self.assertIn("organizer", by_name["permuted_organizer_items"]["roles"])
            self.assertEqual(state_mutation_view_count, EXPECTED_STATE_MUTATION_VIEW_COUNT)

            stepper_headers = by_name["stepper"]["views"]["control_context"]
            self.assertIn("while stepper < len(values):", {item["code"] for item in stepper_headers})
            self.assertIn("contains_update", {item["relation"] for item in stepper_headers})
            wanted_headers = by_name["most_wanted_holder"]["views"]["control_context"]
            self.assertIn(
                "if wanted_value > most_wanted_holder:",
                {item["code"] for item in wanted_headers},
            )
            self.assertIn("guards_write", {item["relation"] for item in wanted_headers})
            self.assertTrue(
                all(
                    "\n" not in item["code"]
                    for record in facts
                    for item in record["views"]["control_context"]
                )
            )
            control_context_view_count = sum(
                len(record["views"]["control_context"]) for record in facts
            )
            self.assertEqual(control_context_view_count, EXPECTED_CONTROL_CONTEXT_VIEW_COUNT)
            for record in facts:
                for item in record["views"]["control_context"]:
                    self.assertTrue({"type", "relation", "location", "code"}.issubset(item))
                    self.assertIn(item["type"], {"loop", "conditional"})
                    self.assertTrue(item["relation"])

            scope_view_count = sum(len(record["views"]["scope"]) for record in facts)
            self.assertEqual(scope_view_count, EXPECTED_SCOPE_VIEW_COUNT)
            for record in facts:
                self.assertEqual(len(record["views"]["scope"]), 1)
                for item in record["views"]["scope"]:
                    self.assertTrue({"type", "relation", "location", "code"}.issubset(item))
                    self.assertEqual(item["type"], "function")
                    self.assertEqual(item["relation"], "direct_scope")
                    self.assertNotIn("\n", item["code"])
                    self.assertRegex(item["code"], r"^(?:async\s+)?def\s+")
                scope_locations = {
                    (
                        item["location"]["start_byte"],
                        item["location"]["end_byte"],
                    )
                    for item in record["views"]["scope"]
                }
                control_locations = {
                    (
                        item["location"]["start_byte"],
                        item["location"]["end_byte"],
                    )
                    for item in record["views"]["control_context"]
                }
                self.assertTrue(
                    scope_locations.isdisjoint(control_locations),
                    f"scope confused with control context for {record['subject']['id']}",
                )

            for name in {
                "stepper",
                "gatherer",
                "walker",
                "wanted_value",
                "organizer_items",
                "container_items",
            }:
                scope = by_name[name]["subject"]["scope"]
                self.assertEqual(scope["type"], "function")
                self.assertEqual(scope["name"], "role_examples")
                self.assertTrue(scope["qualified_name"].endswith(".role_examples"))
                self.assertEqual(
                    by_name[name]["views"]["scope"][0]["code"],
                    "def role_examples(values):",
                )

            by_path_name = {
                (record["subject"]["path"], record["subject"]["name"]): record
                for record in facts
            }
            for name in {"reverting_flag", "container", "unqualified_maximum"}:
                record = by_path_name[("Python/sajaniemi_negative_cases.py", name)]
                self.assertEqual(record["subject"]["scope"]["type"], "function")
                self.assertEqual(record["subject"]["scope"]["name"], "negative_role_examples")
                self.assertTrue(
                    record["subject"]["scope"]["qualified_name"].endswith(
                        ".negative_role_examples"
                    )
                )
                self.assertEqual(
                    record["views"]["scope"][0]["code"],
                    "def negative_role_examples(values, panel):",
                )

            print(
                f"Validated {len(roles)} role records, {len(facts)} variables, "
                f"{len(legacy)} converted legacy annotations, {update_view_count} update views, "
                f"{state_mutation_view_count} state mutations, "
                f"{control_context_view_count} control contexts, "
                f"{scope_view_count} scope headers, and exact spans for all eight views."
            )


if __name__ == "__main__":
    unittest.main()
