from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

from src.python.profile_extraction import parse_profiles, process_tree_rss_bytes
from src.python.variable_aware import legacy_annotations_from_roles
from tests.scala_sources import all_scala_sources, scala_source


class ExtractionProfilingTests(unittest.TestCase):
    def test_rss_is_explicitly_unavailable_when_ps_is_restricted(self) -> None:
        denied = subprocess.CompletedProcess(["ps"], returncode=1, stdout="", stderr="denied")
        with patch("src.python.profile_extraction.subprocess.run", return_value=denied):
            self.assertIsNone(process_tree_rss_bytes(123))

    def test_manual_swap_analysis_has_no_per_variable_global_scan_or_cfg_search(self) -> None:
        source = scala_source("facts/collection_facts.sc")
        all_source = all_scala_sources()
        body = source.split("def manualSwapPermutationSpans(", 1)[1].split("def collectionFlowFor(", 1)[0]
        self.assertNotIn("allCallNodes", body)
        self.assertNotIn("writeCalls", body)
        self.assertNotIn("assignmentWrites.filter", body)
        self.assertNotIn("shortestCfgDistance", body)
        self.assertIn("manualSwapLoadsByCollectionMethod", source)
        self.assertIn("collectionElementPathCache", all_source)

    def test_dynamic_role_profiler_has_aggregate_phases_and_distributions(self) -> None:
        root = Path(__file__).parents[1]
        source = all_scala_sources()
        profiler = (root / "src/scala/profiling.sc").read_text(encoding="utf-8")
        for phase in (
            "facts_build", "binding_partition", "stepper_flow", "walker_flow",
            "most_recent_flow", "follower_flow", "iterator_contexts",
            "implicit_iterator_detection", "numeric_iterator_detection",
            "semantic_read_index", "dynamic_role_fact_indexes",
        ):
            self.assertIn(f'"{phase}"', source)
        for counter in (
            "mrh_candidate_declarations", "mrh_acquisitions", "mrh_corrections",
            "mrh_semantic_reads", "mrh_cfg_searches", "mrh_cfg_visited_volume",
            "mrh_max_writes_per_candidate", "mrh_max_reads_per_candidate",
            "mrh_max_acquisitions_per_candidate", "mrh_methods_with_candidates",
            "mrh_rejected_before_cfg", "mrh_rejected_after_cfg",
            "numeric_iterator_candidate_lines", "numeric_iterator_language_filtered_calls",
            "numeric_iterator_cache_hits", "numeric_iterator_candidate_controls",
            "numeric_iterator_fallback_declarations", "numeric_iterator_max_header_length",
            "numeric_iterator_cache_entries", "numeric_iterator_resolved",
        ):
            self.assertIn(f'"{counter}"', source)
        self.assertIn("def timedAccumulating", profiler)
        accumulating = profiler.split("def timedAccumulating", 1)[1].split("def increment", 1)[0]
        self.assertNotIn("sampleHeap()", accumulating)

    def test_python_profiling_is_opt_in_and_does_not_change_results(self) -> None:
        disabled_stderr = io.StringIO()
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("SAJANIEMI_PROFILE", None)
            with contextlib.redirect_stderr(disabled_stderr):
                disabled_result = legacy_annotations_from_roles([])
        self.assertEqual(disabled_result, [])
        self.assertEqual(disabled_stderr.getvalue(), "")

        enabled_stderr = io.StringIO()
        with patch.dict(os.environ, {"SAJANIEMI_PROFILE": "1"}, clear=False):
            with contextlib.redirect_stderr(enabled_stderr):
                enabled_result = legacy_annotations_from_roles([])
        self.assertEqual(enabled_result, disabled_result)
        profiles = parse_profiles(enabled_stderr.getvalue())
        self.assertEqual(len(profiles), 1)
        self.assertEqual(profiles[0]["component"], "legacy_conversion")
        self.assertEqual(profiles[0]["counters"], {"legacy_annotations": 0})

    def test_profile_lines_are_separate_machine_readable_json(self) -> None:
        payload = {"schema_version": 1, "component": "test", "total_ns": 7}
        stderr = "noise\nSAJANIEMI_PYTHON_PROFILE=" + json.dumps(payload) + "\n"
        self.assertEqual(parse_profiles(stderr), [payload])


if __name__ == "__main__":
    unittest.main()
