from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from sajaniemi_extractor.build_language_graphs import run_joern_parse
from sajaniemi_extractor.variable_aware import (
    MINIMAL_VIEW_KEYS,
    SAJANIEMI_ROLES,
    legacy_annotations_from_roles,
    resolve_variable_aware_output,
)


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "tests/fixtures/temporary_code"
EXPECTATIONS_PATH = ROOT / "tests/fixtures/temporary_expectations.json"
BASELINE_PATH = ROOT / "tests/fixtures/expected/temporary_prepatch_e03126b.json"
SCALA_SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"
FORBIDDEN_FIELDS = {"facts", "evidence", "role_evidence", "extra_fact_evidence"}
EXPECTED_CATEGORIES = {
    "all_writes_valid",
    "assignment_shape",
    "conflict_fixed",
    "conflict_gatherer",
    "conflict_most_recent",
    "conflict_stepper",
    "continue_after_write",
    "continue_before_write",
    "distance_five_boundary",
    "distance_six",
    "definition_without_reachable_read",
    "five_reads_boundary",
    "function_definition_artifact",
    "indirect_write",
    "loop_all_branches_write",
    "loop_branch_without_write",
    "loop_conditional_write",
    "loop_previous_iteration",
    "loop_redefined_before_read",
    "nested_loop_escape",
    "minified_intervals",
    "one_invalid_write",
    "one_interval_over_limit",
    "same_line_read",
    "six_reads",
    "unread_before_exit",
    "unread_before_redefinition",
    "update_plus_equal",
    "update_post_increment",
    "update_pre_increment",
    "update_self_left",
    "update_self_right",
    "module_scope",
    "class_body_descriptor",
    "class_body_attribute",
    "instance_member",
    "callable_method",
    "callable_closure",
    "program_scope",
    "top_level_scope",
}


def load_expectations() -> dict:
    return json.loads(EXPECTATIONS_PATH.read_text(encoding="utf-8"))


def canonical_digest(value: object) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def output_metrics(resolved: dict) -> dict:
    roles = resolved["role_annotations"]
    variables = resolved["variable_facts"]
    legacy = legacy_annotations_from_roles(roles)
    without_scope = json.loads(json.dumps(resolved))
    for section in ("role_annotations", "variable_facts"):
        for record in without_scope[section]:
            record["views"]["scope"] = []
    return {
        "variables": len(variables),
        "role_records": len(roles),
        "roles": dict(sorted(Counter(record["concept"]["name"] for record in roles).items())),
        "views": {
            view: sum(len(record["views"][view]) for record in variables)
            for view in sorted(MINIMAL_VIEW_KEYS)
        },
        "temporary_subjects": sorted(
            record["subject"]["id"]
            for record in roles
            if record["concept"]["name"] == "temporary"
        ),
        "legacy_count": len(legacy),
        "legacy_digest": canonical_digest(legacy),
        "non_scope_digest": canonical_digest(without_scope),
    }


def assert_output_invariants(test: unittest.TestCase, resolved: dict, fixture_root: Path) -> None:
    test.assertEqual(set(resolved), {"schema_version", "role_annotations", "variable_facts"})
    roles = resolved["role_annotations"]
    variables = resolved["variable_facts"]
    pairs = [(record["subject"]["id"], record["concept"]["name"]) for record in roles]
    test.assertEqual(len(pairs), len(set(pairs)))
    role_order = [
        (
            record["subject"]["path"],
            record["views"]["declaration"][0]["location"]["start_byte"],
            record["concept"]["name"],
            record["subject"]["id"],
        )
        for record in roles
    ]
    test.assertEqual(role_order, sorted(role_order))
    test.assertNotIn("future_mutated_variable", {role for _, role in pairs})
    variable_roles = {record["subject"]["id"]: set(record["roles"]) for record in variables}
    for record in roles:
        test.assertIn(record["concept"]["name"], SAJANIEMI_ROLES)
        test.assertIn(record["concept"]["name"], variable_roles[record["subject"]["id"]])
    for record in roles + variables:
        test.assertTrue(FORBIDDEN_FIELDS.isdisjoint(record))
        test.assertEqual(set(record["views"]), set(MINIMAL_VIEW_KEYS))
        source = (fixture_root / record["subject"]["path"]).read_bytes()
        for items in record["views"].values():
            for item in items:
                location = item["location"]
                test.assertGreaterEqual(location["start_byte"], 0)
                test.assertLessEqual(location["end_byte"], len(source))
                test.assertEqual(
                    source[location["start_byte"]:location["end_byte"]],
                    item["code"].encode("utf-8"),
                )


class TemporaryExpectationContractTests(unittest.TestCase):
    def test_prepatch_baseline_is_observational_and_machine_readable(self) -> None:
        baseline = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
        self.assertEqual(baseline["schema_version"], 1)
        self.assertIn("not acceptance targets", baseline["purpose"])
        self.assertEqual(baseline["role_counts"]["temporary"], 18)
        self.assertEqual(baseline["temporary_overlap_counts"], {"fixed_value": 2, "walker": 15})
        self.assertEqual(len(baseline["temporary_only"]), 1)
        self.assertEqual(len(baseline["temporary_and_fixed_value"]), 2)
        self.assertEqual(len(baseline["temporary_and_walker"]), 13)

    def test_manifest_is_complete_unique_and_source_backed(self) -> None:
        manifest = load_expectations()
        self.assertEqual(manifest["schema_version"], 1)
        categories = set()
        for language, spec in manifest["languages"].items():
            source_path = FIXTURE_ROOT / spec["path"]
            self.assertTrue(source_path.is_file(), language)
            source = source_path.read_text(encoding="utf-8")
            names = [case["name"] for case in spec["cases"]]
            self.assertEqual(len(names), len(set(names)), language)
            for case in spec["cases"]:
                self.assertEqual(
                    set(case),
                    {"name", "category", "temporary", "must_have_roles", "must_not_have_roles"},
                )
                self.assertIn(case["name"], source)
                categories.add(case["category"])
                positive = set(case["must_have_roles"])
                negative = set(case["must_not_have_roles"])
                self.assertTrue(positive.isdisjoint(negative))
                self.assertEqual(case["temporary"], "temporary" in positive)
                self.assertEqual(not case["temporary"], "temporary" in negative)
                self.assertTrue((positive | negative).issubset(SAJANIEMI_ROLES))
        self.assertEqual(categories, EXPECTED_CATEGORIES)

    def test_python_matrix_separates_every_new_temporary_condition(self) -> None:
        cases = load_expectations()["languages"]["Python"]["cases"]
        by_category = {case["category"]: case["temporary"] for case in cases}
        expected = {
            "assignment_shape": True,
            "all_writes_valid": True,
            "one_invalid_write": False,
            "update_plus_equal": False,
            "update_self_left": False,
            "update_self_right": False,
            "same_line_read": True,
            "minified_intervals": True,
            "function_definition_artifact": False,
            "indirect_write": False,
            "unread_before_redefinition": False,
            "unread_before_exit": False,
            "definition_without_reachable_read": False,
            "five_reads_boundary": True,
            "six_reads": False,
            "distance_five_boundary": True,
            "distance_six": False,
            "one_interval_over_limit": False,
            "loop_redefined_before_read": True,
            "loop_previous_iteration": False,
            "loop_conditional_write": False,
            "loop_all_branches_write": True,
            "loop_branch_without_write": False,
            "continue_before_write": True,
            "continue_after_write": True,
            "nested_loop_escape": False,
        }
        self.assertTrue(expected.items() <= by_category.items())

    def test_conflict_oracle_is_explicit_in_both_directions(self) -> None:
        cases = load_expectations()["languages"]["Python"]["cases"]
        by_category = {case["category"]: case for case in cases}
        self.assertEqual(by_category["conflict_fixed"]["must_have_roles"], ["temporary"])
        self.assertIn("fixed_value", by_category["conflict_fixed"]["must_not_have_roles"])
        for category, surviving_role in (
            ("conflict_stepper", "stepper"),
            ("conflict_gatherer", "gatherer"),
            ("conflict_most_recent", "most_recent_holder"),
        ):
            self.assertIn(surviving_role, by_category[category]["must_have_roles"])
            self.assertIn("temporary", by_category[category]["must_not_have_roles"])


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_TEMPORARY_FIXTURES") == "1",
    "set RUN_JOERN_TEMPORARY_FIXTURES=1 to build the tiny Temporary fixtures",
)
class TemporaryMultilingualIntegrationTests(unittest.TestCase):
    def test_real_frontends_match_temporary_oracle(self) -> None:
        if shutil.which("joern") is None or shutil.which("joern-parse") is None:
            self.skipTest("joern and joern-parse are required")
        manifest = load_expectations()
        selected = {
            name.strip()
            for name in os.environ.get("TEMPORARY_LANGUAGES", "").split(",")
            if name.strip()
        }
        summaries = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-temporary-") as directory:
            temporary = Path(directory)
            for language, spec in manifest["languages"].items():
                if selected and language not in selected:
                    continue
                graph = temporary / f"{language.lower()}.bin"
                run_joern_parse(FIXTURE_ROOT / language, graph, language, force=False, dry_run=False)
                pre_output = temporary / f"{language.lower()}.pre.json"
                subprocess.run(
                    [
                        "joern", str(graph), "--script", str(SCALA_SCRIPT),
                        "--param", f"output={pre_output}",
                        "--param", f"sourceRoot={FIXTURE_ROOT}", "--nocolors",
                    ],
                    check=True,
                    cwd=temporary,
                    timeout=600,
                )
                resolved = resolve_variable_aware_output(pre_output, FIXTURE_ROOT)
                with self.subTest(language=language, invariant="output_contract"):
                    assert_output_invariants(self, resolved, FIXTURE_ROOT)
                facts_by_name = {}
                for record in resolved["variable_facts"]:
                    if record["subject"]["path"] == spec["path"]:
                        facts_by_name.setdefault(record["subject"]["name"], []).append(record)
                annotations = {
                    (record["subject"]["name"], record["concept"]["name"])
                    for record in resolved["role_annotations"]
                    if record["subject"]["path"] == spec["path"]
                }
                summaries[language] = output_metrics(resolved)
                for case in spec["cases"]:
                    with self.subTest(language=language, case=case["category"], variable=case["name"]):
                        self.assertIn(case["name"], facts_by_name, (language, case))
                        candidates = facts_by_name[case["name"]]
                        fact_roles = set().union(*(set(record["roles"]) for record in candidates))
                        for role in case["must_have_roles"]:
                            self.assertIn(role, fact_roles, (language, case))
                            self.assertIn((case["name"], role), annotations, (language, case))
                        for role in case["must_not_have_roles"]:
                            self.assertNotIn(role, fact_roles, (language, case))
                            self.assertNotIn((case["name"], role), annotations, (language, case))
        print("Temporary fixture summary: " + json.dumps(summaries, sort_keys=True))
        report_path = os.environ.get("TEMPORARY_REPORT_OUTPUT")
        if report_path:
            Path(report_path).write_text(
                json.dumps(summaries, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                encoding="utf-8",
            )


if __name__ == "__main__":
    unittest.main()
