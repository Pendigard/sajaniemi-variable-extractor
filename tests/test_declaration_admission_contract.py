from __future__ import annotations

import unittest
from pathlib import Path

from tests.scala_sources import all_scala_sources, scala_source


ROOT = Path(__file__).resolve().parents[1]


class DeclarationAdmissionContractTests(unittest.TestCase):
    def test_member_alias_decision_is_central_and_events_are_canonicalized(self) -> None:
        source = all_scala_sources()
        self.assertIn("case class CanonicalMemberIdentity", source)
        self.assertIn("canonicalMemberIdentityByPseudoLocalId", source)
        self.assertIn("def canonicalDeclarationId", source)
        self.assertIn("canonicalMemberLocalWrites", source)
        self.assertIn("canonicalMemberIdentifierOccurrences", source)
        self.assertIn('Set("<body>", "<clinit>")', source)
        self.assertIn("member.pos.column == local.pos.column", source)

    def test_anonymous_and_synthetic_parameters_are_rejected_at_admission(self) -> None:
        source = scala_source("pipeline/extraction_context.sc")
        admission = source.split("def isAnnotableSourceParameter", 1)[1].split(
            "val sourceParameterNodes", 1
        )[0]
        self.assertIn("ordinaryName", admission)
        self.assertIn("hasSourceToken(p, name)", admission)
        self.assertIn('position.path != "<unknown>"', admission)
        self.assertIn("position.line > 0", admission)
        self.assertNotIn("typeFullName", admission)


if __name__ == "__main__":
    unittest.main()
