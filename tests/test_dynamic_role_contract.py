from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from src.python.build_language_graphs import run_joern_parse
from src.python.variable_aware import legacy_annotations_from_roles, resolve_variable_aware_output
from tests.scala_sources import scala_source
from tests.scope_validation import FORBIDDEN_FIELDS, validate_output_invariants


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "tests/fixtures/dynamic_role_code"
EXPECTATIONS = ROOT / "tests/fixtures/dynamic_role_expectations.json"
SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"
TARGET_ROLES = {"stepper", "walker", "most_recent_holder", "most_wanted_holder"}


def oracle() -> dict:
    return json.loads(EXPECTATIONS.read_text(encoding="utf-8"))


class DynamicRoleExpectationTests(unittest.TestCase):
    def test_language_gated_iterator_parser_handles_pathological_javascript(self) -> None:
        if not shutil.which("joern"):
            self.skipTest("joern is required to compile the pure Scala parser test")
        completed = subprocess.run(
            ["joern", "--script", str(ROOT / "tests/joern_iterator_parser.sc"), "--nocolors"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertIn("ITERATOR_PARSER_OK", completed.stdout)

    def test_numeric_iterator_index_is_cached_and_does_not_scan_all_declarations(self) -> None:
        source = scala_source("facts/binding_facts.sc")
        body = source.split("val numericHeaderCache", 1)[1].split("val modeledInitializerKeys", 1)[0]
        self.assertIn("declarationsByPathName.getOrElse", body)
        self.assertIn("numericHeaderCache.get", body)
        self.assertIn("numericHeaderCache.update", body)
        self.assertNotIn("declarations.filter(declaration =>", body)
        self.assertIn("numericLanguageSupported", body)
        self.assertIn("numeric_iterator_language_filtered_calls", body)

    def test_oracle_is_source_backed_and_covers_collisions(self) -> None:
        document = oracle()
        self.assertEqual(document["schema_version"], 1)
        self.assertEqual(set(document["languages"]), {"C", "C++", "JavaScript", "Python", "Ruby"})
        coverage = Counter()
        for language, spec in document["languages"].items():
            source = FIXTURE_ROOT / spec["source"]
            self.assertTrue(source.is_file(), language)
            text = source.read_text(encoding="utf-8")
            for expectation in ("present", "absent"):
                self.assertEqual(set(spec[expectation]), TARGET_ROLES)
                for role, names in spec[expectation].items():
                    self.assertEqual(len(names), len(set(names)), (language, expectation, role))
                    for name in names:
                        self.assertIn(name, text)
                        coverage[(role, expectation)] += 1
        for role in TARGET_ROLES:
            self.assertGreater(coverage[(role, "present")], 0)
            self.assertGreater(coverage[(role, "absent")], 0)

    def test_mrh_uses_one_event_traversal_per_acquisition(self) -> None:
        source = scala_source("facts/succession_facts.sc")
        body = source.split("def mostRecentTraversal(", 1)[1].split("def mostRecentFlowFor(", 1)[0]
        self.assertIn("MostRecentTraversalSummary", body)
        self.assertIn("successfulChains", body)
        self.assertIn("blockingDefinitions", body)
        self.assertIn("mrhMaxVisitedNodes", body)
        self.assertNotIn("target: Long", body)
        self.assertNotIn("Set[Long], String), Option[Int]", source)
        self.assertIn("cfgNextSortedById", source)
        self.assertNotIn("cfgNextById.getOrElse(current, Nil).sorted", source)
        self.assertNotIn("ownWriteRhsNodeIdsByDecl", source)
        self.assertIn("readOwningWriteEventId", source)
        flow = source.split("def mostRecentFlowFor(", 1)[1].split("def controlContextsFor(", 1)[0]
        self.assertIn("acquisitions.map(mostRecentTraversal", flow)
        self.assertNotIn("correctionChainFor", source)
        self.assertNotIn("acquisitionReachesUse", source)
        for reason in (
            "incompatible_write", "state_mutation", "strong_walker",
            "holder_guard", "ambiguous_member_owner", "no_plausible_read",
        ):
            self.assertIn(f'"{reason}"', flow)


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_DYNAMIC_ROLE_FIXTURES") == "1",
    "set RUN_JOERN_DYNAMIC_ROLE_FIXTURES=1 to build the five tiny dynamic-role fixtures",
)
class DynamicRoleFrontendIntegrationTests(unittest.TestCase):
    def test_selected_frontends_match_dynamic_role_oracle(self) -> None:
        if not shutil.which("joern") or not shutil.which("joern-parse"):
            self.skipTest("joern and joern-parse are required")
        selected = {
            item.strip() for item in os.environ.get("DYNAMIC_ROLE_LANGUAGES", "").split(",") if item.strip()
        }
        report = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-dynamic-roles-") as directory:
            temporary = Path(directory)
            for language, spec in oracle()["languages"].items():
                if selected and language not in selected:
                    continue
                graph = temporary / f"{language.replace('+', 'p').lower()}.bin"
                run_joern_parse(FIXTURE_ROOT / language, graph, language, False, False)
                pre_output = temporary / f"{graph.stem}.pre.json"
                environment = os.environ.copy()
                environment["SAJANIEMI_PROFILE"] = "1"
                completed = subprocess.run(
                    [
                        "joern", str(graph), "--script", str(SCRIPT),
                        "--param", f"output={pre_output}",
                        "--param", f"sourceRoot={FIXTURE_ROOT}", "--nocolors",
                    ],
                    check=True,
                    cwd=temporary,
                    timeout=600,
                    env=environment,
                    capture_output=True,
                    text=True,
                )
                resolved = resolve_variable_aware_output(pre_output, FIXTURE_ROOT)
                validate_output_invariants(resolved)
                records = [
                    record for record in resolved["variable_facts"]
                    if record["subject"]["path"] == spec["source"]
                ]
                by_name = {}
                for record in records:
                    by_name.setdefault(record["subject"]["name"], []).append(record)
                defects = []
                for role, names in spec["present"].items():
                    for name in names:
                        if not any(role in record["roles"] for record in by_name.get(name, [])):
                            defects.append({"name": name, "missing": role,
                                            "roles": [record["roles"] for record in by_name.get(name, [])]})
                for role, names in spec["absent"].items():
                    for name in names:
                        if any(role in record["roles"] for record in by_name.get(name, [])):
                            defects.append({"name": name, "unexpected": role,
                                            "roles": [record["roles"] for record in by_name.get(name, [])]})
                self.assertFalse(defects, f"{language}: {defects}")
                for record in resolved["role_annotations"] + resolved["variable_facts"]:
                    self.assertTrue(FORBIDDEN_FIELDS.isdisjoint(record))
                role_records = [
                    record for record in resolved["role_annotations"]
                    if record["subject"]["path"] == spec["source"]
                ]
                counts = Counter(role for record in records for role in record["roles"])
                profile_line = next(
                    (line for line in completed.stderr.splitlines() if line.startswith("SAJANIEMI_SCALA_PROFILE=")),
                    "",
                )
                report[language] = {
                    "variables": len(records),
                    "role_records": len(role_records),
                    "roles": dict(sorted(counts.items())),
                    "legacy": len(legacy_annotations_from_roles(role_records)),
                    "profile": json.loads(profile_line.split("=", 1)[1]) if profile_line else None,
                }
        print("DYNAMIC_ROLE_FIXTURE_REPORT=" + json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
