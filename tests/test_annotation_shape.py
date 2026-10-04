from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_PATH = REPOSITORY_ROOT / "tests/fixtures/expected/role_declarations.json"
CODE_ROOT = REPOSITORY_ROOT / "tests/fixtures/code"
REQUIRED_CONCEPTS = {
    "fixed_value",
    "stepper",
    "gatherer",
    "walker",
    "follower",
    "most_recent_holder",
    "most_wanted_holder",
    "one_way_flag",
    "temporary",
    "organizer",
    "container",
}
KNOWN_CONCEPTS = REQUIRED_CONCEPTS | {"future_mutated_variable"}
IDENTIFIER_PATTERN = re.compile(r"(?<![A-Za-z0-9_$]){}(?![A-Za-z0-9_$])")


class ExpectedAnnotationShapeTests(unittest.TestCase):
    def test_python_variable_aware_golden_is_internally_consistent(self) -> None:
        from tests.test_joern_integration import (
            EXPECTED_LEGACY_COUNT,
            EXPECTED_LEGACY_COUNTS,
            EXPECTED_CONTROL_CONTEXT_VIEW_COUNT,
            EXPECTED_ROLE_RECORD_COUNT,
            EXPECTED_ROLE_RECORD_COUNTS,
            EXPECTED_SCOPE_VIEW_COUNT,
            EXPECTED_STATE_MUTATION_VIEW_COUNT,
            EXPECTED_UPDATE_VIEW_COUNT,
        )

        self.assertEqual(set(EXPECTED_ROLE_RECORD_COUNTS), REQUIRED_CONCEPTS)
        self.assertEqual(sum(EXPECTED_ROLE_RECORD_COUNTS.values()), EXPECTED_ROLE_RECORD_COUNT)
        self.assertEqual(set(EXPECTED_LEGACY_COUNTS), REQUIRED_CONCEPTS)
        self.assertEqual(sum(EXPECTED_LEGACY_COUNTS.values()), EXPECTED_LEGACY_COUNT)
        self.assertTrue(all(count > 0 for count in EXPECTED_ROLE_RECORD_COUNTS.values()))
        self.assertGreater(EXPECTED_UPDATE_VIEW_COUNT, 0)
        self.assertGreater(EXPECTED_STATE_MUTATION_VIEW_COUNT, 0)
        self.assertGreater(EXPECTED_CONTROL_CONTEXT_VIEW_COUNT, 0)
        self.assertGreater(EXPECTED_SCOPE_VIEW_COUNT, 0)

    def test_expected_declarations_have_a_stable_machine_readable_shape(self) -> None:
        manifest = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))

        self.assertEqual(manifest["schema_version"], 1)
        self.assertEqual(set(manifest), {"schema_version", "python", "c"})
        self.assertEqual(
            {annotation["concept"] for annotation in manifest["python"]["must_have"]},
            REQUIRED_CONCEPTS,
        )

        for language in ("python", "c"):
            section = manifest[language]
            self.assertEqual(set(section), {"must_have", "must_not_have", "allowed_extra_roles"})
            self.assertTrue(all(isinstance(role, str) and role for role in section["allowed_extra_roles"]))
            self.assertEqual(len(section["allowed_extra_roles"]), len(set(section["allowed_extra_roles"])))
            self.assertTrue(set(section["allowed_extra_roles"]).issubset(KNOWN_CONCEPTS))
            for expectation_name in ("must_have", "must_not_have"):
                annotations = section[expectation_name]
                self.assertIsInstance(annotations, list)
                identities = set()
                for annotation in annotations:
                    self.assertEqual(set(annotation), {"concept", "variant", "path", "name"})
                    self.assertEqual(annotation["variant"], "declaration")
                    self.assertTrue(all(isinstance(value, str) and value for value in annotation.values()))
                    self.assertIn(annotation["concept"], KNOWN_CONCEPTS)
                    expected_prefix = "Python/" if language == "python" else "C/"
                    self.assertTrue(annotation["path"].startswith(expected_prefix))
                    source_path = CODE_ROOT / annotation["path"]
                    self.assertTrue(source_path.is_file(), f"missing fixture source: {annotation['path']}")
                    source = source_path.read_text(encoding="utf-8")
                    identifier_pattern = re.compile(IDENTIFIER_PATTERN.pattern.format(re.escape(annotation["name"])))
                    self.assertRegex(source, identifier_pattern)
                    identities.add(tuple(annotation[key] for key in ("concept", "variant", "path", "name")))
                self.assertEqual(len(identities), len(annotations))

            positive_identities = {
                tuple(annotation[key] for key in ("concept", "variant", "path", "name"))
                for annotation in section["must_have"]
            }
            negative_identities = {
                tuple(annotation[key] for key in ("concept", "variant", "path", "name"))
                for annotation in section["must_not_have"]
            }
            self.assertTrue(positive_identities.isdisjoint(negative_identities))


if __name__ == "__main__":
    unittest.main()
