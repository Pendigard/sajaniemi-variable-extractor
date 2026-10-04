from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from sajaniemi_extractor.build_language_graphs import run_joern_parse
from sajaniemi_extractor.variable_aware import legacy_annotations_from_roles, resolve_variable_aware_output
from tests.scala_sources import all_scala_sources, scala_source
from tests.scope_validation import FORBIDDEN_FIELDS, validate_output_invariants


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "tests/fixtures/flow_role_code"
EXPECTATIONS = ROOT / "tests/fixtures/flow_role_expectations.json"
SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"


def oracle() -> dict:
    return json.loads(EXPECTATIONS.read_text(encoding="utf-8"))


class FlowRoleContractTests(unittest.TestCase):
    def test_parallel_follower_contract_is_internal_and_structural(self) -> None:
        model = (ROOT / "src/scala/model.sc").read_text(encoding="utf-8")
        facts = scala_source("facts/follower_facts.sc")
        self.assertIn("case class ParallelAssignmentGroup", model)
        self.assertIn("case class AtomicFollowerTransition", model)
        self.assertIn("parallelComponentByWriteEventId", facts)
        self.assertIn("logicalRhsCandidates", facts)
        self.assertIn("completionCfgNodeId", facts)
        self.assertNotIn("last_dir", facts)

    def test_oracle_is_source_backed_for_five_frontends(self) -> None:
        document = oracle()
        self.assertEqual(set(document["languages"]), {"C", "C++", "JavaScript", "Python", "Ruby"})
        for language, spec in document["languages"].items():
            source = FIXTURE_ROOT / spec["source"]
            self.assertTrue(source.is_file(), language)
            text = source.read_text(encoding="utf-8")
            for role in ("one_way_flag", "gatherer", "most_wanted_holder"):
                for expectation in ("present", "absent"):
                    names = document["common"].get(role, {}).get(expectation, []) + spec.get(role, {}).get(expectation, [])
                    for name in names:
                        self.assertIn(name, text, (language, role, name))

    def test_internal_flows_are_structured_and_not_exported(self) -> None:
        model = (ROOT / "src/scala/model.sc").read_text(encoding="utf-8")
        predicates = (ROOT / "src/scala/role_predicates.sc").read_text(encoding="utf-8")
        exporter = (ROOT / "src/scala/export_json.sc").read_text(encoding="utf-8")
        for structure in (
            "sealed trait BooleanTransition", "case class OneWayFlagFlow",
            "case class InitializationSeed", "case class GathererReset",
            "case class DataAccumulationUpdate", "case class StableAccumulatorTransform",
            "case class GathererFinalization",
            "case class AccumulationSpan", "case class GathererFlow",
            "case class GathererAdmissibility",
            "sealed trait MostWantedSeedKind", "case class MostWantedSeed",
            "sealed trait SelectionDirection", "sealed trait MostWantedReplacementKind",
            "case class CandidateFingerprint", "case class CandidateRelation",
            "case class SelectionGuardAnalysis", "case class MostWantedReplacement",
            "case class MostWantedSelectionEpoch", "case class MostWantedFlow",
            "case class MostWantedFallback", "case class ComparatorSummary",
        ):
            self.assertIn(structure, model)
            self.assertNotIn(structure.split()[-1], exporter)
        self.assertIn("isOneWayFlagFlow", predicates)
        self.assertIn("isNumericGathererFlow", predicates)
        self.assertIn("f.gathererAdmissibility.admitted", predicates)
        self.assertIn("f.mostWantedFlow", predicates)
        self.assertIn("flow.explainedEventIds == allWriteIds", predicates)
        self.assertNotIn("hasArithmeticGathererUpdate(f)", predicates.split("val conceptPredicates", 1)[1])

    def test_flow_phases_and_prefilters_are_profiled(self) -> None:
        source = all_scala_sources()
        predicates = (ROOT / "src/scala/role_predicates.sc").read_text(encoding="utf-8")
        for phase in (
            "follower_member_flow", "temporary_admissibility",
            "one_way_flag_flow", "gatherer_flow", "most_wanted_flow",
        ):
            self.assertIn(f'"{phase}"', source)
        for counter in (
            "temporary_admissibility_rejected", "one_way_flag_candidates",
            "one_way_flag_prefiltered", "gatherer_candidates", "gatherer_spans",
            "gatherer_target_uses",
            "mwh_prefiltered_candidates", "mwh_guarded_replacements",
            "mwh_minmax_replacements", "mwh_rejected_candidate_mismatch",
            "mwh_rejected_incompatible_writes", "mwh_rejected_incomplete_guard",
            "mwh_null_sentinels", "mwh_extreme_sentinels", "mwh_outer_loop_seeds",
            "mwh_epochs_built", "mwh_bootstrap_selection_guards",
            "mwh_equivalent_fingerprints", "mwh_rejected_intra_epoch_reset",
            "mwh_rejected_impure_call", "mwh_rejected_direction_mismatch",
            "mwh_multi_expression_guards", "mwh_conjunctions_with_eligibility",
            "mwh_stable_external_seeds", "mwh_nested_loops_recovered",
            "mwh_comparators_summarized", "mwh_comparators_rejected",
            "mwh_terminal_selections", "mwh_fallbacks_explained",
            "mwh_rejected_missing_terminal_exit",
            "mwh_none_bootstraps", "mwh_indexed_candidate_paths",
            "mwh_member_name_fallback_paths",
        ):
            self.assertIn(f'"{counter}"', source + predicates)


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_FLOW_ROLE_FIXTURES") == "1",
    "set RUN_JOERN_FLOW_ROLE_FIXTURES=1 to build the five flow-role fixtures",
)
class FlowRoleFrontendIntegrationTests(unittest.TestCase):
    def test_selected_frontends_match_flow_role_oracle(self) -> None:
        if not shutil.which("joern") or not shutil.which("joern-parse"):
            self.skipTest("joern and joern-parse are required")
        document = oracle()
        selected = {
            item.strip() for item in os.environ.get("FLOW_ROLE_LANGUAGES", "").split(",") if item.strip()
        }
        report = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-flow-roles-") as directory:
            temporary = Path(directory)
            for language, spec in document["languages"].items():
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
                roles = set(document["common"]) | {
                    key for key, value in spec.items() if key != "source" and isinstance(value, dict)
                }
                for role in sorted(roles):
                    expectations = document["common"].get(role, {"present": [], "absent": []})
                    language_expectations = spec.get(role, {})
                    for name in expectations["present"] + language_expectations.get("present", []):
                        if not any(role in record["roles"] for record in by_name.get(name, [])):
                            defects.append({"name": name, "missing": role,
                                            "roles": [r["roles"] for r in by_name.get(name, [])]})
                    for name in expectations["absent"] + language_expectations.get("absent", []):
                        if any(role in record["roles"] for record in by_name.get(name, [])):
                            defects.append({"name": name, "unexpected": role,
                                            "roles": [r["roles"] for r in by_name.get(name, [])]})
                self.assertFalse(defects, f"{language}: {defects}")
                for record in resolved["role_annotations"] + resolved["variable_facts"]:
                    self.assertTrue(FORBIDDEN_FIELDS.isdisjoint(record))
                role_records = [
                    record for record in resolved["role_annotations"]
                    if record["subject"]["path"] == spec["source"]
                ]
                counts = Counter(role for record in records for role in record["roles"])
                if language == "Python":
                    canonical_home = [record for record in by_name.get("home_url", [])
                                      if record["subject"]["kind"] == "member"]
                    self.assertEqual(len(canonical_home), 1)
                    self.assertEqual(
                        [record for record in by_name.get("home_url", [])
                         if record["subject"]["kind"] == "local"], []
                    )
                    self.assertIn("self.home_url = value", {
                        view["code"] for view in canonical_home[0]["views"]["writes"]
                    })
                    canonical_collection = by_name.get("unique_together", [])
                    self.assertEqual(len(canonical_collection), 1)
                    self.assertTrue(canonical_collection[0]["is_collection"])
                    same_name_kinds = sorted(record["subject"]["kind"]
                                             for record in by_name.get("same_name", []))
                    self.assertEqual(same_name_kinds, ["local", "member"])
                if language == "C++":
                    parameter_names = [record["subject"]["name"] for record in records
                                       if record["subject"]["kind"] == "parameter"]
                    self.assertTrue(all(name and not name.startswith("<")
                                        for name in parameter_names))
                    retained = [record for record in by_name.get("retained_parameter", [])
                                if record["subject"]["kind"] == "parameter"]
                    self.assertEqual(len(retained), 1)
                    self.assertTrue(retained[0]["views"]["identifier"])
                profile_line = next((line for line in completed.stderr.splitlines()
                                     if line.startswith("SAJANIEMI_SCALA_PROFILE=")), "")
                report[language] = {
                    "variables": len(records),
                    "role_records": len(role_records),
                    "roles": dict(sorted(counts.items())),
                    "legacy": len(legacy_annotations_from_roles(role_records)),
                    "profile": json.loads(profile_line.split("=", 1)[1]) if profile_line else None,
                }
        print("FLOW_ROLE_FIXTURE_REPORT=" + json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
