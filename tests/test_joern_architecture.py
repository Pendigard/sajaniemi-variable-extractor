from __future__ import annotations

import re
import unittest
from pathlib import Path

from tests.scala_sources import SCALA_ROOT, scala_source


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_MODULES = {
    "extract_dynamic_variables.sc",
    "model.sc",
    "profiling.sc",
    "source_index.sc",
    "iterator_parsing.sc",
    "role_predicates.sc",
    "annotation_emission.sc",
    "export_json.sc",
    "pipeline/extraction_pipeline.sc",
    "pipeline/extraction_context.sc",
    "pipeline/fact_assembly.sc",
    "facts/binding_facts.sc",
    "facts/value_lifecycle_facts.sc",
    "facts/collection_facts.sc",
    "facts/follower_facts.sc",
    "facts/succession_facts.sc",
}


class JoernArchitectureTests(unittest.TestCase):
    def test_expected_modules_replace_the_monolith(self) -> None:
        self.assertFalse((SCALA_ROOT / "cpg_facts.sc").exists())
        for relative in EXPECTED_MODULES:
            self.assertTrue((SCALA_ROOT / relative).is_file(), relative)

    def test_entrypoint_directives_resolve_and_cover_every_module(self) -> None:
        entrypoint = SCALA_ROOT / "extract_dynamic_variables.sc"
        text = entrypoint.read_text(encoding="utf-8")
        directives = re.findall(r"^//> using file (.+)$", text, flags=re.MULTILINE)
        self.assertEqual(len(directives), len(set(directives)))
        for relative in directives:
            self.assertTrue((SCALA_ROOT / relative).is_file(), relative)
        self.assertEqual(set(directives), EXPECTED_MODULES - {"extract_dynamic_variables.sc"})

    def test_facts_do_not_depend_on_emission_or_export(self) -> None:
        for path in sorted((SCALA_ROOT / "facts").glob("*.sc")):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("emitVariableAwareOutput", text, path.name)
            self.assertNotIn("exportVariableAwareJson", text, path.name)
            self.assertNotIn("RoleAnnotationHint", text, path.name)

    def test_orchestrator_is_short_and_contains_no_role_heuristics(self) -> None:
        source = scala_source("pipeline/extraction_pipeline.sc")
        self.assertLessEqual(len(source.splitlines()), 200)
        for forbidden in (
            "def stepper", "def walker", "def follower", "def gatherer",
            "def temporary", "def fixedValue", "def collectionFlow",
            "def mostRecent", "def mostWanted", "def oneWayFlag",
        ):
            self.assertNotIn(forbidden, source)
        self.assertIn("classifyRoles", source)
        self.assertIn("emitVariableAwareOutput", source)
        self.assertIn("exportVariableAwareJson", source)

    def test_fact_modules_stay_below_the_architectural_guardrail(self) -> None:
        for path in sorted((SCALA_ROOT / "facts").glob("*.sc")):
            self.assertLessEqual(len(path.read_text(encoding="utf-8").splitlines()), 2000, path.name)

    def test_python_namespace_has_no_old_imports(self) -> None:
        old_import = re.compile(r"(?:from|import)\s+src\.(?:joern|role_extractor)\.")
        for path in sorted((ROOT / "src").rglob("*.py")) + sorted((ROOT / "tests").rglob("*.py")):
            self.assertIsNone(old_import.search(path.read_text(encoding="utf-8")), path)


if __name__ == "__main__":
    unittest.main()
