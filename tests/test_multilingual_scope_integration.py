from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from src.python.variable_aware import MINIMAL_VIEW_KEYS, resolve_variable_aware_output
from tests.scope_validation import (
    final_subject_scope_metrics,
    output_metrics,
    scope_boundary_metrics,
    validate_output_invariants,
)


ROOT = Path(__file__).resolve().parents[1]
CODE_ROOT = ROOT / "code/test"
GRAPH_ROOT = ROOT / "graph/test"
SCALA_SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"
BASELINE_PATH = ROOT / "tests/fixtures/expected/scope_nonregression_e03126b.json"
LANGUAGES = ("c", "cpp", "javascript", "python", "ruby")
NON_SCOPE_METRIC_KEYS = (
    "variables", "role_records", "roles", "legacy", "non_scope_digest",
    "duplicate_role_pairs",
)


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_MULTILINGUAL") == "1",
    "set RUN_JOERN_MULTILINGUAL=1 to regress the five cached Joern corpus graphs",
)
class MultilingualScopeCorpusRegressionTests(unittest.TestCase):
    def test_cached_graphs_preserve_everything_except_scope(self) -> None:
        if shutil.which("joern") is None:
            self.skipTest("joern is required")
        baseline_document = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
        baseline = baseline_document["languages"]
        subject_digests = baseline_document["subject_digests"]
        report = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-scope-corpus-") as directory:
            temporary = Path(directory)
            for stem in LANGUAGES:
                graph = GRAPH_ROOT / f"{stem}.bin"
                self.assertTrue(graph.is_file(), stem)
                pre_path = temporary / f"{stem}.pre.json"
                subprocess.run([
                    "joern", str(graph), "--script", str(SCALA_SCRIPT),
                    "--param", f"output={pre_path}", "--param", f"sourceRoot={CODE_ROOT}", "--nocolors",
                ], check=True, cwd=temporary, timeout=600)
                pre = json.loads(pre_path.read_text(encoding="utf-8"))
                resolved = resolve_variable_aware_output(pre_path, CODE_ROOT)
                metrics = output_metrics(resolved, pre)
                with self.subTest(language=stem, invariant="shape-order-uniqueness"):
                    # The cached corpora contain a few pre-existing normalized-ID
                    # collisions. Tiny fixtures retain the strict uniqueness check;
                    # this regression run reports, but does not rewrite, old output.
                    validate_output_invariants(resolved, require_unique_roles=False)
                for key in NON_SCOPE_METRIC_KEYS:
                    with self.subTest(language=stem, metric=key):
                        self.assertEqual(metrics[key], baseline[stem][key], f"{stem}:{key}")
                for key in ("subject_ids_digest", "subject_scopes_digest"):
                    with self.subTest(language=stem, metric=key):
                        self.assertEqual(metrics[key], subject_digests[stem][key], f"{stem}:{key}")
                for view in MINIMAL_VIEW_KEYS:
                    if view != "scope":
                        with self.subTest(language=stem, view=view):
                            self.assertEqual(metrics["views"][view], baseline[stem]["views"][view], f"{stem}:{view}")
                exact_spans = 0
                source_backed_variables = 0
                for record in resolved["variable_facts"]:
                    path = CODE_ROOT / record["subject"]["path"]
                    if not path.is_file():
                        continue
                    source_backed_variables += 1
                    source = path.read_bytes()
                    for item in record["views"]["scope"]:
                        location = item["location"]
                        with self.subTest(language=stem, subject=record["subject"]["id"], scope=item["code"]):
                            self.assertEqual(source[location["start_byte"]:location["end_byte"]], item["code"].encode("utf-8"))
                            self.assertEqual(item["relation"], "direct_scope")
                            self.assertNotIn("<metaClass", item["code"])
                        exact_spans += 1
                if stem == "cpp":
                    expected_cpp = (
                        'extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data,\n'
                        '                                      std::size_t size)\n'
                        '{'
                    )
                    real_cpp_scopes = {
                        item["code"]
                        for record in resolved["variable_facts"]
                        if record["subject"]["path"].endswith("000101_set-gc.cpp")
                        for item in record["views"]["scope"]
                    }
                    self.assertIn(expected_cpp, real_cpp_scopes)
                    llvm_scopes = {code for code in real_cpp_scopes if "LLVMFuzzerTestOneInput" in code}
                    self.assertEqual(llvm_scopes, {expected_cpp})
                if stem == "javascript":
                    real_javascript_scopes = {
                        item["code"]
                        for record in resolved["variable_facts"]
                        if record["subject"]["path"].endswith("000181_table.js")
                        for item in record["views"]["scope"]
                    }
                    self.assertIn("onShow:function(){", real_javascript_scopes)
                    on_show_scopes = {
                        code for code in real_javascript_scopes if code.endswith("onShow:function(){")
                    }
                    self.assertEqual(on_show_scopes, {"onShow:function(){"})
                    for forbidden in (
                        ",onShow:function(){",
                        "onLoad:function(){...},onShow:function(){",
                        "310:280,getModel:function(d){...},onShow:function(){",
                    ):
                        self.assertNotIn(forbidden, real_javascript_scopes)
                report[stem] = {
                    **metrics,
                    "source_backed_variables": source_backed_variables,
                    "resolved_scopes": metrics["views"]["scope"],
                    "eligible_scopes": metrics["scala_scope_candidates"],
                    "unresolved_or_ineligible": metrics["variables"] - metrics["views"]["scope"],
                    "legitimate_absences": None,
                    "exact_scope_spans": exact_spans,
                    "scope_boundaries": scope_boundary_metrics(pre, CODE_ROOT),
                    "final_subject_scope": final_subject_scope_metrics(pre, resolved, CODE_ROOT),
                }
        print("SCOPE_CORPUS_REPORT=" + json.dumps(report, ensure_ascii=False, sort_keys=True))
        report_output = os.environ.get("SCOPE_REPORT_OUTPUT")
        if report_output:
            Path(report_output).write_text(
                json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                encoding="utf-8",
            )


if __name__ == "__main__":
    unittest.main()
