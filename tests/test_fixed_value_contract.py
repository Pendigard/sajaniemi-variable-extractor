from __future__ import annotations

import json, os, shutil, subprocess, tempfile, time, unittest
from collections import Counter
from pathlib import Path

from src.python.build_language_graphs import run_joern_parse
from src.python.variable_aware import MINIMAL_VIEW_KEYS, legacy_annotations_from_roles, resolve_variable_aware_output
from tests.scope_validation import canonical_json_digest, duplicate_role_pair_count, validate_output_invariants

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/fixtures/fixed_value_code"
ORACLE_PATH = ROOT / "tests/fixtures/fixed_value_expectations.json"
BASELINE_PATH = ROOT / "tests/fixtures/expected/fixed_value_prepatch_23c24ff.json"
SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"
GRAPH_ROOT = ROOT / "graph/test"
CODE_ROOT = ROOT / "code/test"
FORBIDDEN = {"facts", "evidence", "role_evidence", "extra_fact_evidence"}

def oracle(): return json.loads(ORACLE_PATH.read_text(encoding="utf-8"))

def matches(record: dict, case: dict) -> bool:
    subject = record["subject"]
    owner = case["owner"]
    metadata = subject.get("scope", {})
    scope_name = str(metadata.get("name", ""))
    qualified = str(metadata.get("qualified_name", ""))
    expected_kind = {"local": "local", "parameter": "parameter", "member": "member"}.get(case["category"])
    kind_matches = expected_kind is None or subject.get("kind") == expected_kind
    if not kind_matches or subject["name"] != case["name"]: return False
    if owner.startswith("<"): return True
    if case["category"] == "member": return owner in qualified or owner in subject["id"]
    return scope_name == owner or qualified == owner or qualified.endswith("." + owner) or owner in qualified

def neutralized(resolved: dict) -> dict:
    data = json.loads(json.dumps(resolved))
    data["role_annotations"] = [r for r in data["role_annotations"] if r["concept"]["name"] not in {"fixed_value", "temporary"}]
    for record in data["variable_facts"]:
        record["roles"] = [r for r in record["roles"] if r not in {"fixed_value", "temporary"}]
    return {"roles": data["role_annotations"], "variables": data["variable_facts"]}

def assert_spans(test: unittest.TestCase, resolved: dict, root: Path) -> None:
    for record in resolved["role_annotations"] + resolved["variable_facts"]:
        test.assertEqual(set(record["views"]), set(MINIMAL_VIEW_KEYS))
        test.assertTrue(FORBIDDEN.isdisjoint(record))
        path = root / record["subject"]["path"]
        if not path.is_file(): continue
        source = path.read_bytes()
        for view, items in record["views"].items():
            for item in items:
                loc = item["location"]
                test.assertEqual(source[loc["start_byte"]:loc["end_byte"]], item["code"].encode("utf-8"))
                if view == "identifier": test.assertEqual(item["code"], record["subject"]["name"])

class FixedValueContractTests(unittest.TestCase):
    def test_oracle_is_unique_source_backed_and_covers_contract(self):
        document = oracle(); self.assertEqual(document["schema_version"], 1)
        categories = Counter(); expectations = Counter()
        for language, spec in document["languages"].items():
            source = (FIXTURES / spec["source"]).read_text(encoding="utf-8")
            keys = set()
            for case in spec["cases"]:
                self.assertEqual(set(case) - {"limitation"}, {"name","owner","category","expectation","reason"})
                key = (case["owner"], case["name"]); self.assertNotIn(key, keys); keys.add(key)
                self.assertIn(case["name"], source)
                self.assertIn(case["expectation"], {"present","absent","indeterminate"})
                categories[case["category"]] += 1; expectations[case["expectation"]] += 1
        self.assertTrue({"local","parameter","global","member"}.issubset(categories))
        self.assertGreater(expectations["present"], 0); self.assertGreater(expectations["absent"], 0)

    def test_baseline_is_observational_and_has_neutral_digests(self):
        baseline = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
        self.assertIn("never a semantic oracle", baseline["purpose"])
        self.assertEqual(set(baseline["languages"]), {"c","cpp","javascript","python","ruby"})
        for metrics in baseline["languages"].values():
            self.assertEqual(sum(metrics["kinds"].values()), metrics["fixed_value"])
            for key in ("digest","neutral_digest","legacy_neutral_digest"): self.assertEqual(len(metrics[key]), 64)

    def test_oracle_and_report_shapes_are_json_and_jsonl_readable(self):
        cases = [case for spec in oracle()["languages"].values() for case in spec["cases"]]
        encoded = "\n".join(json.dumps(case, sort_keys=True) for case in cases) + "\n"
        self.assertEqual([json.loads(line) for line in encoded.splitlines()], cases)

@unittest.skipUnless(os.environ.get("RUN_JOERN_FIXED_VALUE_FIXTURES") == "1", "set RUN_JOERN_FIXED_VALUE_FIXTURES=1")
class FixedValueFrontendIntegrationTests(unittest.TestCase):
    def test_five_frontends_match_identity_oracle(self):
        if not shutil.which("joern") or not shutil.which("joern-parse"): self.skipTest("Joern required")
        selected = {x for x in os.environ.get("FIXED_VALUE_LANGUAGES", "").split(",") if x}
        report = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-fixed-value-") as directory:
            temporary = Path(directory)
            for language, spec in oracle()["languages"].items():
                if selected and language not in selected: continue
                started = time.monotonic(); graph = temporary / f"{language.replace('+','p').lower()}.bin"
                run_joern_parse(FIXTURES/language, graph, language, False, False)
                pre = temporary / f"{graph.stem}.json"
                subprocess.run(["joern",str(graph),"--script",str(SCRIPT),"--param",f"output={pre}","--param",f"sourceRoot={FIXTURES}","--nocolors"],check=True,cwd=temporary,timeout=600)
                resolved = resolve_variable_aware_output(pre, FIXTURES)
                validate_output_invariants(resolved); assert_spans(self,resolved,FIXTURES)
                variables = [r for r in resolved["variable_facts"] if r["subject"]["path"] == spec["source"]]
                tp=tn=fp=fn=indeterminate=0; defects=[]
                category_metrics = {category: Counter() for category in ("local", "parameter", "global", "member")}
                for case in spec["cases"]:
                    candidates=[r for r in variables if matches(r,case)]
                    direct=[r for r in candidates if r["subject"].get("scope",{}).get("name")==case["owner"]]
                    if direct: candidates=direct
                    if case["category"]=="global" and len(candidates)>1:
                        candidates=sorted(candidates,key=lambda r:(
                            r["views"]["declaration"][0]["location"]["start_line"],
                            r["views"]["declaration"][0]["location"]["start_column"],
                            r["subject"]["id"],
                        ))[:1]
                    with self.subTest(language=language,owner=case["owner"],name=case["name"]):
                        category = category_metrics[case["category"]]
                        if case["expectation"]=="indeterminate":
                            indeterminate+=1; category["indeterminate"] += 1; continue
                        if len(candidates) != 1:
                            indeterminate += 1; category["indeterminate"] += 1
                            defects.append({"case":case,"observed":"identity_count","candidate_count":len(candidates),"candidate_ids":[r["subject"]["id"] for r in candidates]})
                            self.assertEqual(len(candidates),1,case)
                            continue
                        observed="fixed_value" in candidates[0]["roles"]
                        expected=case["expectation"]=="present"
                        if expected and observed: tp+=1; category["tp"] += 1
                        elif not expected and not observed: tn+=1; category["tn"] += 1
                        elif expected: fn+=1; category["fn"] += 1; defects.append({"case":case,"observed":"absent"})
                        else: fp+=1; category["fp"] += 1; defects.append({"case":case,"observed":"present"})
                        self.assertEqual(observed,expected,case)
                legacy=legacy_annotations_from_roles(resolved["role_annotations"])
                report[language]={"variables":len(variables),"role_records":len(resolved["role_annotations"]),"fixed_value":sum("fixed_value" in r["roles"] for r in variables),"tp":tp,"tn":tn,"fp":fp,"fn":fn,"indeterminate":indeterminate,"precision":tp/(tp+fp) if tp+fp else None,"recall":tp/(tp+fn) if tp+fn else None,"by_category":{key:dict(value) for key,value in category_metrics.items()},"views":{v:sum(len(r["views"][v]) for r in variables) for v in MINIMAL_VIEW_KEYS},"legacy":len(legacy),"seconds":round(time.monotonic()-started,3),"defects":defects}
        print("FIXED_VALUE_FIXTURE_REPORT="+json.dumps(report,sort_keys=True))
        if path:=os.environ.get("FIXED_VALUE_REPORT_OUTPUT"): Path(path).write_text(json.dumps(report,indent=2,sort_keys=True)+"\n",encoding="utf-8")

@unittest.skipUnless(os.environ.get("RUN_JOERN_FIXED_VALUE_CORPUS") == "1", "set RUN_JOERN_FIXED_VALUE_CORPUS=1")
class FixedValueCorpusRegressionTests(unittest.TestCase):
    def test_cached_graphs_change_only_fixed_value_and_priority_temporary(self):
        baseline=json.loads(BASELINE_PATH.read_text(encoding="utf-8"))["languages"]
        selected={x for x in os.environ.get("FIXED_VALUE_CORPUS_LANGUAGES","").split(",") if x}
        report={}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-fixed-corpus-") as directory:
            temporary=Path(directory)
            for stem in baseline:
                if selected and stem not in selected: continue
                pre=temporary/f"{stem}.json"
                subprocess.run(["joern",str(GRAPH_ROOT/f"{stem}.bin"),"--script",str(SCRIPT),"--param",f"output={pre}","--param",f"sourceRoot={CODE_ROOT}","--nocolors"],check=True,cwd=temporary,timeout=900)
                resolved=resolve_variable_aware_output(pre,CODE_ROOT)
                assert_spans(self,resolved,CODE_ROOT)
                legacy=legacy_annotations_from_roles(resolved["role_annotations"])
                role_counts=Counter(record["concept"]["name"] for record in resolved["role_annotations"])
                metrics={"variables":len(resolved["variable_facts"]),"role_records":len(resolved["role_annotations"]),"fixed_value":role_counts["fixed_value"],"role_counts":dict(sorted(role_counts.items())),"views":{view:sum(len(record["views"][view]) for record in resolved["variable_facts"]) for view in MINIMAL_VIEW_KEYS},"legacy":len(legacy),"duplicates":duplicate_role_pair_count(resolved),"digest":canonical_json_digest(resolved),"neutral_digest":canonical_json_digest(neutralized(resolved))}
                self.assertEqual(metrics["variables"],baseline[stem]["variables"])
                self.assertEqual(metrics["duplicates"],baseline[stem]["duplicates"])
                self.assertEqual(metrics["neutral_digest"],baseline[stem]["neutral_digest"])
                report[stem]=metrics
        print("FIXED_VALUE_CORPUS_REPORT="+json.dumps(report,sort_keys=True))
        if path:=os.environ.get("FIXED_VALUE_CORPUS_REPORT_OUTPUT"): Path(path).write_text(json.dumps(report,indent=2,sort_keys=True)+"\n",encoding="utf-8")

if __name__ == "__main__": unittest.main()
