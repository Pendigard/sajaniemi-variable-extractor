from __future__ import annotations

import json
import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from src.python.build_language_graphs import LANGUAGES, run_joern_parse
from src.python.variable_aware import SourceResolver, resolve_variable_aware_output
from tests.scala_sources import scala_source
from tests.scope_validation import (
    FORBIDDEN_FIELDS,
    SCOPE_OUTCOMES,
    final_subject_scope_metrics,
    load_scope_expectations,
    output_metrics,
    scope_boundary_metrics,
    validate_output_invariants,
)


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "tests/fixtures/scope_code"
SCALA_SCRIPT = ROOT / "src/scala/extract_dynamic_variables.sc"
EXPECTATIONS = load_scope_expectations(ROOT)


def records_named(resolved: dict, name: str) -> list[dict]:
    records = [record for record in resolved["variable_facts"] if record["subject"]["name"] == name]
    return sorted(
        records,
        key=lambda record: record["views"]["declaration"][0]["location"]["start_byte"]
        if record["views"]["declaration"] else -1,
    )


def assert_scope_item(test: unittest.TestCase, source: bytes, item: dict, expected: str) -> None:
    test.assertEqual(item["code"], expected)
    test.assertEqual(item["relation"], "direct_scope")
    test.assertIn(item["type"], {"function", "method"})
    location = item["location"]
    test.assertGreaterEqual(location["start_byte"], 0)
    test.assertLessEqual(location["end_byte"], len(source))
    test.assertLess(location["start_byte"], location["end_byte"])
    test.assertEqual(source[location["start_byte"]:location["end_byte"]], expected.encode("utf-8"))
    test.assertNotIn("return ", expected)
    test.assertNotIn("\n    int ", expected.split("{")[0] if "{" in expected else "")


def role_predicates_without_refined_roles(source: str) -> str:
    """Protect predicates outside the explicitly refined role families."""
    context_start = source.index("case class RolePredicateContext(")
    classify_start = source.index("def classifyRoles(", context_start)
    source = source[:context_start] + "// REFINED_ROLE_MODELS_REMOVED\n\n" + source[classify_start:]

    dynamic_markers = ("  // Concept rules usually", "  // All dynamic roles share")
    dynamic_start = next(source.index(marker) for marker in dynamic_markers if marker in source)
    dynamic_end = source.index("  // A non-predictable update inside a loop", dynamic_start)
    source = source[:dynamic_start] + "  // INITIALIZATION_AND_STEPPER_INTERNALS_REMOVED\n" + source[dynamic_end:]

    stepper_start = source.rfind("  //", 0, source.index("  def isStepperFact("))
    stepper_end = source.index("  // An overwrite replaces", stepper_start)
    source = source[:stepper_start] + "  // STEPPER_PREDICATE_REMOVED\n" + source[stepper_end:]

    follower_start = source.index("  lazy val allFactsById =")
    follower_end = source.index("  // `items[i]` is loop data", follower_start)
    source = source[:follower_start] + "  // FOLLOWER_INTERNALS_REMOVED\n\n" + source[follower_end:]

    walker_index_start = source.index("  // `items[i]` is loop data")
    walker_index_end = source.index("  // Follow earlier direct copies", walker_index_start)
    source = source[:walker_index_start] + "  // WALKER_INDEX_INTERNALS_REMOVED\n" + source[walker_index_end:]
    source = source.replace(
        "        factsByMethodName.getOrElse((w.method, sourceName), Nil).exists { source =>",
        "        REFINED_SOURCE_LOOKUP.exists { source =>",
    ).replace(
        "        facts.exists { source =>",
        "        REFINED_SOURCE_LOOKUP.exists { source =>",
    )

    temporary_end_marker = "  // Joern-generated temporaries"
    temporary_end = source.index(temporary_end_marker) if temporary_end_marker in source else source.index("  // Taxonomy concepts")
    temporary_start = max(source.rfind(marker, 0, temporary_end) for marker in (
        "  // Temporaries should", "  // An update carries"
    ))
    source = source[:temporary_start] + "  // TEMPORARY_INTERNALS_REMOVED\n" + source[temporary_end:]

    collection_markers = ("  def collectionFlowIsComplete(f: VarFacts): Boolean =",
                          "  def isVerifiedNonTextCollection(f: VarFacts): Boolean =")
    collection_start = next(source.index(marker) for marker in collection_markers if marker in source)
    collection_end = source.index("  // Taxonomy concepts", collection_start)
    source = source[:collection_start] + source[collection_end:]

    flow_start = source.find("  def monotoneOperand(")
    if flow_start >= 0:
        flow_end = source.index("  val walkerCandidateCount", flow_start)
        source = source[:flow_start] + "  // ONE_WAY_AND_GATHERER_INTERNALS_REMOVED\n" + source[flow_end:]

    follower_metrics_start = source.find('  recordMetric("follower_member_candidates"')
    if follower_metrics_start >= 0:
        follower_metrics_end = source.index('  recordMetric("mrh_guard_rejections"', follower_metrics_start)
        source = source[:follower_metrics_start] + source[follower_metrics_end:]
    flow_metrics_start = source.find("  val oneWayCandidates =")
    if flow_metrics_start >= 0:
        flow_metrics_end = source.index("\n\n  // Taxonomy concepts", flow_metrics_start)
        source = source[:flow_metrics_start] + source[flow_metrics_end:]

    for role, next_role in (
        ("Fixed value", "Stepper"), ("Stepper", "Gatherer"), ("Gatherer", "Walker"),
        ("Walker", "Follower"), ("Follower", "Most-recent holder"),
        ("Most-recent holder", "Most-wanted holder"), ("Most-wanted holder", "One-way flag"),
        ("One-way flag", "Temporary"),
        ("Temporary", "Organizer"), ("Organizer", "Container"),
    ):
        start = source.index(f'    "{role}" ->')
        end = source.index(f'    "{next_role}" ->', start)
        source = source[:start] + f'    "{role}" -> REFINED_ROLE_PREDICATE_REMOVED,\n' + source[end:]
    container_start = source.index('    "Container" ->')
    container_end = source.index("\n  )", container_start)
    source = source[:container_start] + '    "Container" -> REFINED_ROLE_PREDICATE_REMOVED' + source[container_end:]

    one_way_helper_start = source.index("  def isOneWayFlag(f: VarFacts): Boolean =")
    one_way_helper_end = source.index("\n\n", one_way_helper_start)
    source = source[:one_way_helper_start] + "  REFINED_ONE_WAY_HELPER_REMOVED" + source[one_way_helper_end:]

    return_start = source.rfind("  RoleClassification(")
    return_end = source.index("\n}", return_start)
    source = source[:return_start] + "  REFINED_ROLE_CLASSIFICATION_REMOVED" + source[return_end:]
    return source


class ScopeExpectationContractTests(unittest.TestCase):
    def test_final_subject_scope_report_exposes_all_required_outcomes(self) -> None:
        self.assertEqual(
            set(SCOPE_OUTCOMES),
            {
                "resolved", "file_or_module", "class_body",
                "function_defining_variable", "duplicate_merged", "synthetic_wrapper",
                "ambiguous_start", "ambiguous_end", "source_mismatch",
                "unsupported_callable", "unclassified",
            },
        )

    def test_prepatch_baseline_is_machine_readable_and_scope_is_not_a_target(self) -> None:
        historical = json.loads(
            (ROOT / "tests/fixtures/expected/scope_baseline_20883c9.json").read_text(encoding="utf-8")
        )
        self.assertEqual(historical["baseline_kind"], "composite_historical")
        self.assertEqual(historical["scala_commit"], "20883c9")
        self.assertEqual(historical["python_resolver_commit"], "e03126b")
        baseline = json.loads(
            (ROOT / "tests/fixtures/expected/scope_nonregression_e03126b.json").read_text(encoding="utf-8")
        )
        self.assertEqual(baseline["baseline_kind"], "exact_parent_commit")
        self.assertEqual(baseline["scala_commit"], "e03126b")
        self.assertEqual(baseline["python_resolver_commit"], "e03126b")
        self.assertTrue(baseline["generated_at"])
        self.assertEqual(set(baseline["graph_version"]), {"c", "cpp", "javascript", "python", "ruby"})
        self.assertEqual(set(baseline["languages"]), {"c", "cpp", "javascript", "python", "ruby"})
        for metrics in baseline["languages"].values():
            self.assertTrue({"variables", "role_records", "roles", "views", "legacy", "non_scope_digest", "duplicate_role_pairs"}.issubset(metrics))
            self.assertEqual(len(metrics["non_scope_digest"]), 64)
            self.assertEqual(len(metrics["legacy"]["digest"]), 64)
        self.assertNotIn("scope", ("variables", "role_records", "roles", "legacy", "non_scope_digest"))

    def test_non_fixed_non_temporary_role_predicates_and_scientific_scope_ownership_are_unchanged(self) -> None:
        baseline = json.loads(
            (ROOT / "tests/fixtures/expected/scope_nonregression_e03126b.json").read_text(encoding="utf-8")
        )["protected_source"]
        role_source = (ROOT / "src/scala/role_predicates.sc").read_text(encoding="utf-8")
        protected_role_bytes = role_predicates_without_refined_roles(role_source).encode("utf-8")
        self.assertEqual(
            hashlib.sha256(protected_role_bytes).hexdigest(),
            baseline["role_predicates_except_refined_roles_sha256"],
        )
        lines = scala_source("pipeline/extraction_context.sc").splitlines(keepends=True)
        start = next(index for index, line in enumerate(lines) if line.startswith("  def scopeOf(n: AstNode)"))
        end = next(index for index in range(start + 1, len(lines)) if lines[index].rstrip("\r\n") == "  }") + 1
        scope_of = "".join(lines[start:end]).encode("utf-8")
        self.assertEqual(hashlib.sha256(scope_of).hexdigest(), baseline["scope_of_sha256"])

    def test_manifest_has_explicit_positive_text_and_negative_reason(self) -> None:
        self.assertEqual(EXPECTATIONS["schema_version"], 1)
        self.assertEqual(set(EXPECTATIONS["languages"]), {"C", "C++", "JavaScript", "Python", "Ruby"})
        for language, spec in EXPECTATIONS["languages"].items():
            source = FIXTURE_ROOT / spec["source"]
            self.assertTrue(source.is_file(), language)
            text = source.read_text(encoding="utf-8")
            for expected in spec["must_scope"]:
                self.assertTrue(expected["variable"])
                self.assertTrue(expected["code"])
                self.assertIn(expected["code"], text, f"{language}:{expected}")
            for absent in spec["must_not_scope"]:
                self.assertTrue(absent["variable"])
                self.assertIn(absent["reason"], {"file", "module", "class", "wrapper", "ambiguity", "function_defining_variable"})

    def test_expected_headers_have_exact_utf8_offsets_and_exclude_bodies(self) -> None:
        for language, spec in EXPECTATIONS["languages"].items():
            source = (FIXTURE_ROOT / spec["source"]).read_bytes()
            for expected in spec["must_scope"]:
                encoded = expected["code"].encode("utf-8")
                self.assertGreaterEqual(source.find(encoded), 0, f"{language}:{expected['code']}")
                if encoded.endswith(b"{") or encoded.endswith(b":") or language == "Ruby":
                    self.assertNotIn(b"return ", encoded)

    def test_resolver_uses_callable_column_on_minified_javascript(self) -> None:
        source = "function first(v) { let a = v; } function second(v) { let b = v; }\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "JavaScript/minified.js"
            path.parent.mkdir(parents=True)
            path.write_text(source, encoding="utf-8")
            second_start = source.index("function second")
            body_brace = source.index("{", second_start)
            item = {"_hint": {
                "path": "JavaScript/minified.js", "line": 1, "column": second_start + 1,
                "end_line": 1, "end_column": body_brace + 1, "name": "second", "boundary": "block",
            }}
            resolved = SourceResolver(Path(directory)).scope(item)
            self.assertIsNotNone(resolved)
            self.assertEqual(resolved["code"], "function second(v) {")

    def test_resolver_preserves_c_family_declaration_prefixes(self) -> None:
        cases = {
            "extern": (
                'int previous(void);\nextern "C" int parse(const char *text) {\n  return *text;\n}\n',
                "int parse(const char *text) {",
                'extern "C" int parse(const char *text) {',
            ),
            "attribute": (
                "[[nodiscard]] inline Result parse(Input input) {\n  return input;\n}\n",
                "Result parse(Input input) {",
                "[[nodiscard]] inline Result parse(Input input) {",
            ),
            "static": (
                "int previous;\nstatic int compute(int value) {\n  return value;\n}\n",
                "int compute(int value) {",
                "static int compute(int value) {",
            ),
            "macro": (
                "#define API_EXPORT\nAPI_EXPORT Result parse(Input input) {\n  return input;\n}\n",
                "Result parse(Input input) {",
                "API_EXPORT Result parse(Input input) {",
            ),
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for stem, (source, method_code, expected) in cases.items():
                path = root / f"C++/{stem}.cpp"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
                resolver = SourceResolver(root)
                anchor = source.index(method_code)
                brace = source.index("{", anchor)
                line_start = source.rfind("\n", 0, anchor) + 1
                line = source.count("\n", 0, anchor) + 1
                item = {"_hint": {
                    "path": f"C++/{stem}.cpp", "line": line,
                    "column": anchor - line_start + 1,
                    "end_line": source.count("\n", 0, brace) + 1,
                    "end_column": brace - source.rfind("\n", 0, brace),
                    "name": "compute" if stem == "static" else "parse",
                    "boundary": "block", "code": method_code,
                }}
                first = resolver.scope(item)
                second = resolver.scope(item)
                self.assertEqual(first, second, stem)
                self.assertIsNotNone(first, stem)
                assert first is not None
                self.assertEqual(first["code"], expected, stem)
                location = first["location"]
                encoded = source.encode("utf-8")
                self.assertEqual(
                    encoded[location["start_byte"]:location["end_byte"]].decode("utf-8"),
                    expected,
                    stem,
                )
                self.assertNotIn("previous", first["code"], stem)
                self.assertNotIn("return", first["code"], stem)

    def test_resolver_isolates_minified_javascript_properties(self) -> None:
        source = (
            'const holder={first:function(){return call("a,b", [1,2]);},'
            'onLoad:function(){return `x,y`;},onShow:function(){return 3;},'
            '"quoted-name":function(){return 4;},async action(value){return value;}};\n'
        )
        expectations = {
            "onShow": "onShow:function(){",
            "quoted-name": '"quoted-name":function(){',
            "action": "async action(value){",
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "JavaScript/properties.js"
            path.parent.mkdir(parents=True)
            path.write_text(source, encoding="utf-8")
            resolver = SourceResolver(root)
            for name, expected in expectations.items():
                anchor_text = "async action" if name == "action" else "function"
                property_start = source.index(expected.split(":", 1)[0])
                anchor = source.index(anchor_text, property_start)
                brace = source.index("{", anchor)
                item = {"_hint": {
                    "path": "JavaScript/properties.js", "line": 1,
                    "column": anchor + 1, "end_line": 1, "end_column": brace + 1,
                    "name": name if name != "quoted-name" else "",
                    "boundary": "javascript_block", "code": source[anchor:brace + 1],
                }}
                first = resolver.scope(item)
                self.assertEqual(first, resolver.scope(item), name)
                self.assertIsNotNone(first, name)
                assert first is not None
                self.assertEqual(first["code"], expected, name)
                location = first["location"]
                self.assertEqual(location["start_byte"], len(source[:property_start].encode("utf-8")), name)
                self.assertEqual(location["end_byte"], location["start_byte"] + len(expected.encode("utf-8")), name)
                self.assertEqual(
                    source.encode("utf-8")[location["start_byte"]:location["end_byte"]].decode("utf-8"),
                    expected,
                    name,
                )
                self.assertNotIn("onLoad", first["code"], name)
                self.assertFalse(first["code"].startswith(","), name)
                self.assertNotIn("return", first["code"], name)

    def test_c_family_prefix_never_crosses_independent_source_units(self) -> None:
        cases = (
            (
                'int previous(void);\n\nextern "C" int current(int value) {\n  return value;\n}\n',
                'int current(int value) {',
                'extern "C" int current(int value) {',
                ("previous",),
            ),
            (
                '#define UNRELATED_MACRO 1\n\nstatic int current(int value) {\n  return value;\n}\n',
                'int current(int value) {',
                'static int current(int value) {',
                ("UNRELATED_MACRO", "#define"),
            ),
            (
                'int first(int value) { return value; }\nint second(int value) {\n  return value;\n}\n',
                'int second(int value) {',
                'int second(int value) {',
                ("first", "return value"),
            ),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, (source, method_code, expected, forbidden) in enumerate(cases):
                relative = f"C++/separation_{index}.cpp"
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
                anchor = source.index(method_code)
                brace = source.index("{", anchor)
                line_start = source.rfind("\n", 0, anchor) + 1
                item = {"_hint": {
                    "path": relative,
                    "line": source.count("\n", 0, anchor) + 1,
                    "column": anchor - line_start + 1,
                    "end_line": source.count("\n", 0, brace) + 1,
                    "end_column": brace - source.rfind("\n", 0, brace),
                    "name": "current" if index < 2 else "second",
                    "boundary": "block",
                    "code": method_code,
                }}
                resolved = SourceResolver(root).scope(item)
                self.assertIsNotNone(resolved, relative)
                assert resolved is not None
                self.assertEqual(resolved["code"], expected, relative)
                for token in forbidden:
                    self.assertNotIn(token, resolved["code"], relative)

    def test_javascript_property_boundary_ignores_nested_commas(self) -> None:
        source = (
            'const handlers={onLoad:function(){call("a,b", [1,2], {x:3});'
            'const {left,right}=pair;const template=`a,b`;},onShow:function(){let shown=1}};\n'
        )
        expected = "onShow:function(){"
        property_start = source.index("onShow")
        anchor = source.index("function", property_start)
        brace = source.index("{", anchor)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "JavaScript/nested_commas.js"
            path.parent.mkdir(parents=True)
            path.write_text(source, encoding="utf-8")
            resolved = SourceResolver(root).scope({"_hint": {
                "path": "JavaScript/nested_commas.js", "line": 1,
                "column": anchor + 1, "end_line": 1, "end_column": brace + 1,
                "name": "onShow", "boundary": "javascript_block", "code": "function(){",
            }})
            self.assertIsNotNone(resolved)
            assert resolved is not None
            self.assertEqual(resolved["code"], expected)
            self.assertFalse(resolved["code"].startswith(","))
            for forbidden in ("onLoad", "310:280", "getModel", "a,b"):
                self.assertNotIn(forbidden, resolved["code"])
            location = resolved["location"]
            self.assertEqual(
                source.encode("utf-8")[location["start_byte"]:location["end_byte"]],
                expected.encode("utf-8"),
            )

    def test_javascript_unmatched_line_prefix_falls_back_without_crashing(self) -> None:
        source = "call([function(){return 1;}]);\n"
        anchor = source.index("function")
        brace = source.index("{", anchor)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "JavaScript/nested.js"
            path.parent.mkdir(parents=True)
            path.write_text(source, encoding="utf-8")
            resolved = SourceResolver(root).scope({"_hint": {
                "path": "JavaScript/nested.js", "line": 1, "column": anchor + 1,
                "end_line": 1, "end_column": brace + 1, "name": "",
                "boundary": "javascript_block", "code": "function(){",
            }})
            self.assertIsNotNone(resolved)
            assert resolved is not None
            self.assertEqual(resolved["code"], "function(){")

    def test_resolver_does_not_confuse_cpp_initializer_braces(self) -> None:
        source = "Box::Box(int seed) : stored_{seed}, values_{seed, seed + 1} {\n  int local = seed;\n}\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "C++/initializer.cpp"
            path.parent.mkdir(parents=True)
            path.write_text(source, encoding="utf-8")
            body_brace = source.rindex("{")
            item = {"_hint": {
                "path": "C++/initializer.cpp", "line": 1, "column": 1,
                "end_line": 1, "end_column": body_brace + 1, "name": "Box", "boundary": "block",
            }}
            resolved = SourceResolver(Path(directory)).scope(item)
            self.assertIsNotNone(resolved)
            self.assertEqual(resolved["code"], "Box::Box(int seed) : stored_{seed}, values_{seed, seed + 1} {")

    def test_resolver_accepts_ruby_default_parameter_and_operator(self) -> None:
        sources = {
            "default": ("def with_default(value = 1)\n  local = value\nend\n", "with_default", "def with_default(value = 1)"),
            "operator": ("def +(other)\n  local = other\nend\n", "+", "def +(other)"),
            "indexed": ("def []=(key, value)\n  local = value\nend\n", "[]=", "def []=(key, value)"),
            "endless": ("def compact(value = 1) = value + 1\n", "compact", "def compact(value = 1) ="),
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for stem, (source, name, expected) in sources.items():
                path = root / f"Ruby/{stem}.rb"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
                resolved = SourceResolver(root).scope({"_hint": {
                    "path": f"Ruby/{stem}.rb", "line": 1, "column": 1,
                    "end_line": 1, "end_column": 1, "name": name, "boundary": "ruby_def",
                }})
                self.assertIsNotNone(resolved, stem)
                self.assertEqual(resolved["code"], expected, stem)

    def test_resolver_handles_prototypes_and_anonymous_callable_headers(self) -> None:
        cases = {
            "C/prototype.h": (
                "int compute(\n  int value\n);\n", "compute", "prototype", 1, 1, 3, 2,
                "int compute(\n  int value\n);",
            ),
            "C++/lambda.cpp": (
                "auto fn = [seed](int value) { return value; };\n", "", "cpp_lambda_block", 1, 11, 1, 29,
                "[seed](int value) {",
            ),
            "JavaScript/arrow.js": (
                "const fn = (value) => value + 1;\n", "", "javascript_arrow_expression", 1, 12, 1, 34,
                "const fn = (value) =>",
            ),
            "Python/lambda.py": (
                "owner = lambda value: value + 1\n", "", "python_lambda", 1, 9, 1, 1,
                "lambda value:",
            ),
            "Ruby/block.rb": (
                "values.each do |value|\n  local = value\nend\n", "", "ruby_do", 1, 1, 1, 1,
                "values.each do |value|",
            ),
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path, (source, *_rest) in cases.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source, encoding="utf-8")
            resolver = SourceResolver(root)
            for path, (source, name, boundary, line, column, end_line, end_column, expected) in cases.items():
                method_code = source[source.find("["):] if boundary == "cpp_lambda_block" else source
                if boundary == "python_lambda":
                    method_code = "<empty>"
                resolved = resolver.scope({"_hint": {
                    "path": path, "line": line, "column": column,
                    "end_line": end_line, "end_column": end_column,
                    "name": name, "boundary": boundary, "code": method_code.rstrip(),
                }})
                self.assertIsNotNone(resolved, path)
                assert resolved is not None
                self.assertEqual(resolved["code"], expected, path)
                location = resolved["location"]
                self.assertEqual(
                    source.encode("utf-8")[location["start_byte"]:location["end_byte"]].decode("utf-8"),
                    expected,
                    path,
                )


@unittest.skipUnless(
    os.environ.get("RUN_JOERN_SCOPE_FIXTURES") == "1",
    "set RUN_JOERN_SCOPE_FIXTURES=1 to build and validate the five tiny Joern fixtures",
)
class TinyMultilingualScopeIntegrationTests(unittest.TestCase):
    def _validate_language(self, language: str, spec: dict, temporary: Path) -> dict:
        graph = temporary / f"{language.replace('+', 'p').lower()}.bin"
        run_joern_parse(FIXTURE_ROOT / language, graph, language, force=False, dry_run=False)
        pre_output = temporary / f"{graph.stem}.pre.json"
        subprocess.run([
            "joern", str(graph), "--script", str(SCALA_SCRIPT),
            "--param", f"output={pre_output}", "--param", f"sourceRoot={FIXTURE_ROOT}", "--nocolors",
        ], check=True, cwd=temporary, timeout=600)
        pre = json.loads(pre_output.read_text(encoding="utf-8"))
        resolved = resolve_variable_aware_output(pre_output, FIXTURE_ROOT)
        validate_output_invariants(resolved)
        source = (FIXTURE_ROOT / spec["source"]).read_bytes()
        for expected in spec["must_scope"]:
            with self.subTest(language=language, variable=expected["variable"], expected=expected["code"]):
                matches = records_named(resolved, expected["variable"])
                occurrence = expected.get("occurrence", 1)
                self.assertGreaterEqual(len(matches), occurrence, f"{language}:{expected}")
                scopes = matches[occurrence - 1]["views"]["scope"]
                self.assertEqual(len(scopes), 1, f"{language}:{expected}")
                assert_scope_item(self, source, scopes[0], expected["code"])
        for absent in spec["must_not_scope"]:
            with self.subTest(language=language, absent=absent["variable"], reason=absent["reason"]):
                matches = records_named(resolved, absent["variable"])
                self.assertTrue(matches, f"missing negative subject {language}:{absent}")
                for record in matches:
                    self.assertEqual(record["views"]["scope"], [], f"{language}:{absent}")
        for record in resolved["role_annotations"] + resolved["variable_facts"]:
            self.assertTrue(FORBIDDEN_FIELDS.isdisjoint(record), language)
            scopes = record["views"]["scope"]
            identities = [(item["location"]["start_byte"], item["location"]["end_byte"], item["code"]) for item in scopes]
            self.assertEqual(len(identities), len(set(identities)), record["subject"]["id"])
        return {
            **output_metrics(resolved, pre),
            "scope_boundaries": scope_boundary_metrics(pre, FIXTURE_ROOT),
            "final_subject_scope": final_subject_scope_metrics(pre, resolved, FIXTURE_ROOT),
        }

    def test_real_frontends_match_semantic_scope_oracle(self) -> None:
        if shutil.which("joern") is None or shutil.which("joern-parse") is None:
            self.skipTest("joern and joern-parse are required")
        summaries = {}
        with tempfile.TemporaryDirectory(prefix="sajaniemi-scope-fixtures-") as directory:
            temporary = Path(directory)
            for language, spec in EXPECTATIONS["languages"].items():
                summaries[language] = self._validate_language(language, spec, temporary)
        print("Tiny multilingual scope summary: " + json.dumps(summaries, sort_keys=True))
        report_output = os.environ.get("SCOPE_REPORT_OUTPUT")
        if report_output:
            Path(report_output).write_text(
                json.dumps(summaries, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                encoding="utf-8",
            )


if __name__ == "__main__":
    unittest.main()
