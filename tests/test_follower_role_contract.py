from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from sajaniemi_extractor.build_language_graphs import run_joern_parse
from sajaniemi_extractor.variable_aware import legacy_annotations_from_roles, resolve_variable_aware_output
from tests.scope_validation import canonical_json_digest, validate_output_invariants


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "tests/fixtures/follower_code"
EXPECTATIONS = ROOT / "tests/fixtures/follower_expectations.json"
SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"
FORBIDDEN_FIELDS = {"facts", "evidence", "role_evidence", "extra_fact_evidence"}


def oracle() -> dict:
    return json.loads(EXPECTATIONS.read_text(encoding="utf-8"))


def matches(record: dict, case: dict) -> bool:
    subject = record["subject"]
    if subject["name"] != case["name"]:
        return False
    scope = subject.get("scope", {})
    owner = case["owner"]
    qualified = str(scope.get("qualified_name", ""))
    owner_token = re.compile(rf"(?:^|[.:]){re.escape(owner)}(?:[.:]|$)")
    return (
        scope.get("name") == owner
        or qualified.endswith("." + owner)
        or qualified.endswith("::" + owner)
        or bool(owner_token.search(qualified))
        or (subject.get("kind") == "member" and bool(owner_token.search(subject["id"])))
    )


class FollowerExpectationContractTests(unittest.TestCase):
    def test_oracle_is_unique_source_backed_and_discriminating(self) -> None:
        document = oracle()
        self.assertEqual(document["schema_version"], 1)
        categories: Counter[str] = Counter()
        expectations: Counter[str] = Counter()
        self.assertEqual(set(document["languages"]), {"C", "C++", "JavaScript", "Python", "Ruby"})
        for language, spec in document["languages"].items():
            source_path = FIXTURE_ROOT / spec["source"]
            self.assertTrue(source_path.is_file(), language)
            source = source_path.read_text(encoding="utf-8")
            identities: set[tuple[str, str]] = set()
            for case in spec["cases"]:
                self.assertEqual(set(case), {"owner", "name", "category", "expectation"})
                identity = (case["owner"], case["name"])
                self.assertNotIn(identity, identities, (language, identity))
                identities.add(identity)
                self.assertIn(case["name"], source)
                self.assertIn(case["expectation"], {"present", "absent", "indeterminate"})
                categories[case["category"]] += 1
                expectations[case["expectation"]] += 1
        self.assertGreater(expectations["present"], 0)
        self.assertGreater(expectations["absent"], 0)
        self.assertTrue({
            "direct_before", "direct_after", "multiple_sites", "multiple_loops",
            "source_plus_literal", "literal_plus_source", "source_minus_literal",
            "fixed_variable_offset", "field_projection", "indexed_literal", "indexed_fixed",
            "persistent_member", "two_sources", "dynamic_offset", "fixed_minus_source",
            "multiplication", "call", "duplicated_source", "self_dependency",
            "state_mutation", "outside_loop", "master_static", "member_reset",
            "dynamic_index", "compound_index", "homonymous_member",
            "no_post_master_read", "read_before_master_update",
            "follower_overwrite_before_read", "master_depends_on_follower",
            "follower_guard", "noncontrolling_conditional_read",
            "nested_field_path", "mixed_field_index_path", "sibling_path_update",
            "different_literal_index", "ancestor_follower_guard",
            "master_mutation_depends_on_follower", "transitive_master_dependency",
            "parallel_atomic", "parallel_backedge", "parallel_three",
            "parallel_no_master_evolution", "parallel_no_post_read",
            "parallel_read_before_only", "parallel_other_source",
            "parallel_master_depends_on_follower", "parallel_starred_target",
            "parallel_nested_target",
        }.issubset(categories))


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_FOLLOWER_FIXTURES") == "1",
    "set RUN_JOERN_FOLLOWER_FIXTURES=1 to build the five tiny Follower fixtures",
)
class FollowerFrontendIntegrationTests(unittest.TestCase):
    def test_five_frontends_match_identity_oracle(self) -> None:
        if not shutil.which("joern") or not shutil.which("joern-parse"):
            self.skipTest("joern and joern-parse are required")
        selected = {
            name.strip()
            for name in os.environ.get("FOLLOWER_LANGUAGES", "").split(",")
            if name.strip()
        }
        report: dict[str, dict] = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-follower-") as directory:
            temporary = Path(directory)
            for language, spec in oracle()["languages"].items():
                if selected and language not in selected:
                    continue
                graph = temporary / f"{language.replace('+', 'p').lower()}.bin"
                run_joern_parse(FIXTURE_ROOT / language, graph, language, False, False)
                pre_output = temporary / f"{graph.stem}.pre.json"
                subprocess.run(
                    [
                        "joern", str(graph), "--script", str(SCRIPT),
                        "--param", f"output={pre_output}",
                        "--param", f"sourceRoot={FIXTURE_ROOT}", "--nocolors",
                    ],
                    check=True,
                    cwd=temporary,
                    timeout=600,
                )
                resolved = resolve_variable_aware_output(pre_output, FIXTURE_ROOT)
                validate_output_invariants(resolved)
                records = [
                    record for record in resolved["variable_facts"]
                    if record["subject"]["path"] == spec["source"]
                ]
                for record in resolved["role_annotations"] + resolved["variable_facts"]:
                    self.assertTrue(FORBIDDEN_FIELDS.isdisjoint(record))
                    source_path = FIXTURE_ROOT / record["subject"]["path"]
                    if not source_path.is_file():
                        continue
                    source = source_path.read_bytes()
                    for items in record["views"].values():
                        for item in items:
                            location = item["location"]
                            self.assertEqual(
                                source[location["start_byte"]:location["end_byte"]],
                                item["code"].encode("utf-8"),
                            )
                outcomes = Counter()
                defects = []
                for case in spec["cases"]:
                    named = [
                        record for record in records
                        if record["subject"]["name"] == case["name"]
                    ]
                    owner_matched = [record for record in named if matches(record, case)]
                    # NEWC may expose a member's lexical subject scope as <global>
                    # even though TYPE_DECL ownership was used internally. Accept
                    # that presentation only when the source fixture has one member
                    # with this name; homonymous-member cases must match the owner.
                    candidates = owner_matched or (
                        named if len(named) == 1 and named[0]["subject"].get("kind") == "member" else []
                    )
                    actual = any("follower" in record["roles"] for record in candidates)
                    if case["expectation"] == "indeterminate":
                        outcomes["indeterminate"] += 1
                        continue
                    expected = case["expectation"] == "present"
                    outcomes["tp" if expected and actual else "tn" if not expected and not actual else "fn" if expected else "fp"] += 1
                    if expected != actual:
                        defects.append({"case": case, "candidate_ids": [r["subject"]["id"] for r in candidates]})
                    with self.subTest(language=language, owner=case["owner"], name=case["name"]):
                        self.assertTrue(candidates, (language, case))
                        self.assertEqual(actual, expected, defects[-1:] if defects else None)
                followers = [
                    record for record in resolved["role_annotations"]
                    if record["concept"]["name"] == "follower"
                ]
                legacy = legacy_annotations_from_roles(resolved["role_annotations"])
                report[language] = {
                    **dict(outcomes),
                    "variables": len(resolved["variable_facts"]),
                    "role_records": len(resolved["role_annotations"]),
                    "followers": len(followers),
                    "follower_subjects": sorted(record["subject"]["id"] for record in followers),
                    "legacy_count": len(legacy),
                    "legacy_digest": canonical_json_digest(legacy),
                    "defects": defects,
                }
        print("FOLLOWER_FIXTURE_REPORT=" + json.dumps(report, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
