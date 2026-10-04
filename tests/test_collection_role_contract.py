from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from sajaniemi_extractor.build_language_graphs import LANGUAGES, run_joern_parse
from sajaniemi_extractor.variable_aware import legacy_annotations_from_roles, resolve_variable_aware_output
from tests.scope_validation import FORBIDDEN_FIELDS, validate_output_invariants


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "tests/fixtures/collection_code"
EXPECTATIONS = ROOT / "tests/fixtures/collection_expectations.json"
SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"


def oracle() -> dict:
    return json.loads(EXPECTATIONS.read_text(encoding="utf-8"))


class CollectionExpectationContractTests(unittest.TestCase):
    def test_oracle_is_unique_source_backed_and_discriminating(self) -> None:
        document = oracle()
        self.assertEqual(document["schema_version"], 1)
        self.assertEqual(set(document["languages"]), {"C", "C++", "JavaScript", "Python", "Ruby"})
        categories = Counter()
        for language, spec in document["languages"].items():
            source = FIXTURE_ROOT / spec["source"]
            self.assertTrue(source.is_file(), language)
            text = source.read_text(encoding="utf-8")
            identities = set()
            for case in spec["cases"]:
                identity = (case["name"], case["role"])
                self.assertNotIn(identity, identities, language)
                identities.add(identity)
                self.assertIn(case["name"], text)
                self.assertIn(case["role"], {"fixed_value", "organizer", "container"})
                self.assertIn(case["expectation"], {"present", "absent"})
                if "is_collection" in case:
                    self.assertIsInstance(case["is_collection"], bool)
                categories[(case["role"], case["expectation"])] += 1
            for case in spec["mutation_cases"]:
                self.assertIn(case["name"], text)
                self.assertEqual(case["kinds"], sorted(set(case["kinds"])))
        self.assertGreater(categories[("organizer", "present")], 0)
        self.assertGreater(categories[("organizer", "absent")], 0)
        self.assertGreater(categories[("container", "present")], 0)
        self.assertGreater(categories[("container", "absent")], 0)


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_COLLECTION_FIXTURES") == "1",
    "set RUN_JOERN_COLLECTION_FIXTURES=1 to build the five tiny collection fixtures",
)
class CollectionFrontendIntegrationTests(unittest.TestCase):
    def test_five_frontends_match_collection_oracle(self) -> None:
        if not shutil.which("joern") or not shutil.which("joern-parse"):
            self.skipTest("joern and joern-parse are required")
        selected = {
            name.strip()
            for name in os.environ.get("COLLECTION_LANGUAGES", "").split(",")
            if name.strip()
        }
        report = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-collection-") as directory:
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
                defects = []
                for case in spec["cases"]:
                    candidates = [
                        record for record in records
                        if record["subject"]["name"] == case["name"]
                        and ("kind" not in case or record["subject"]["kind"] == case["kind"])
                    ]
                    if len(candidates) != 1:
                        defects.append({"case": case, "reason": f"subjects={len(candidates)}"})
                        continue
                    present = case["role"] in candidates[0]["roles"]
                    if present != (case["expectation"] == "present"):
                        defects.append({"case": case, "roles": candidates[0]["roles"]})
                    if "is_collection" in case and candidates[0]["is_collection"] != case["is_collection"]:
                        defects.append({"case": case, "actual_is_collection": candidates[0]["is_collection"]})
                for case in spec["mutation_cases"]:
                    candidates = [
                        record for record in records
                        if record["subject"]["name"] == case["name"]
                        and ("kind" not in case or record["subject"]["kind"] == case["kind"])
                    ]
                    if len(candidates) != 1:
                        defects.append({"mutation_case": case, "reason": f"subjects={len(candidates)}"})
                        continue
                    actual_kinds = sorted({
                        mutation["mutation_kind"]
                        for mutation in candidates[0]["views"]["state_mutations"]
                    })
                    if actual_kinds != case["kinds"]:
                        defects.append({"mutation_case": case, "actual_kinds": actual_kinds})
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
                self.assertFalse(defects, f"{language}: {defects}")
                for record in records:
                    collection_roles = {"container", "organizer", "fixed_value"}.intersection(record["roles"])
                    self.assertLessEqual(len(collection_roles), 1, (language, record["subject"], record["roles"]))
                    self.assertIsInstance(record["is_collection"], bool)
                role_records = [
                    record for record in resolved["role_annotations"]
                    if record["subject"]["path"] == spec["source"]
                ]
                collection_records = [record for record in records if record["is_collection"]]
                report[language] = {
                    "variables": len(records),
                    "role_records": len(role_records),
                    "organizer": sum("organizer" in record["roles"] for record in collection_records),
                    "container": sum("container" in record["roles"] for record in collection_records),
                    "fixed_value": sum("fixed_value" in record["roles"] for record in collection_records),
                    "collections_without_role": sum(
                        record["is_collection"] and not {"container", "organizer", "fixed_value"}.intersection(record["roles"])
                        for record in records
                    ),
                    "state_mutations": sum(len(record["views"]["state_mutations"]) for record in records),
                    "views": {
                        view: sum(len(record["views"][view]) for record in records)
                        for view in sorted(records[0]["views"] if records else [])
                    },
                    "legacy": len(legacy_annotations_from_roles(role_records)),
                }
                artifact_directory = os.environ.get("COLLECTION_ARTIFACT_DIR")
                if artifact_directory:
                    artifact_root = Path(artifact_directory)
                    artifact_root.mkdir(parents=True, exist_ok=True)
                    (artifact_root / f"{graph.stem}.resolved.json").write_text(
                        json.dumps(resolved, indent=2, sort_keys=True) + "\n", encoding="utf-8"
                    )
                    shutil.copyfile(pre_output, artifact_root / f"{graph.stem}.pre.json")
        artifact_directory = os.environ.get("COLLECTION_ARTIFACT_DIR")
        if artifact_directory:
            (Path(artifact_directory) / "report.json").write_text(
                json.dumps({"schema_version": 1, "languages": report}, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        print("COLLECTION_FIXTURE_REPORT=" + json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
