from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from src.python.compare_annotations import FILES, compare_bundle


def write_bundle(directory: Path, *, role: str = "stepper", byte_variant: bool = False) -> None:
    subject = {
        "id": "Python/a.py::scope::local::x::1:1::1",
        "joern_id": 1,
        "name": "x",
        "kind": "local",
        "path": "a.py",
        "scope": "scope",
    }
    item = {
        "id": "identifier:1",
        "code": "x",
        "type": "identifier",
        "relation": "subject",
        "span": {"byte_start": 0, "byte_end": 1, "line": 1, "column": 1, "line_end": 1, "column_end": 2},
    }
    views = {name: ([item] if name == "identifier" else []) for name in (
        "declaration", "identifier", "reads", "writes", "updates", "state_mutations", "control_context", "scope"
    )}
    fact = {"subject": subject, "is_collection": False, "roles": [role], "views": views}
    role_record = {"subject": subject, "concept": {"family": "sajaniemi_role", "name": role}, "views": views}
    pre = {"schema_version": 1, "role_annotations": [role_record], "variable_facts": [fact]}
    documents = {
        "pre.json": pre,
        "variable_facts.json": [fact],
        "roles.json": [role_record],
        "legacy.json": [{"concept": role, "path": "a.py", "span": [0, 1]}],
    }
    for name, value in documents.items():
        indent = None if byte_variant else 2
        (directory / name).write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=indent) + "\n", encoding="utf-8")
    (directory / "roles.jsonl").write_text(json.dumps(role_record, ensure_ascii=False) + "\n", encoding="utf-8")


class CompareAnnotationsTests(unittest.TestCase):
    def test_equal_bundle_requires_byte_equality_for_every_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after = root / "before", root / "after"
            before.mkdir()
            after.mkdir()
            write_bundle(before)
            write_bundle(after)
            report = compare_bundle(before, after)
            self.assertTrue(report["equal"])
            self.assertEqual(set(report["files"]), set(FILES))

    def test_role_delta_is_reported_by_subject(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after = root / "before", root / "after"
            before.mkdir()
            after.mkdir()
            write_bundle(before, role="stepper")
            write_bundle(after, role="walker")
            report = compare_bundle(before, after)
            self.assertFalse(report["equal"])
            kinds = {delta["kind"] for delta in report["deltas"]}
            self.assertIn("roles_removed", kinds)
            self.assertIn("roles_added", kinds)
            self.assertIn("role_record_removed", kinds)
            self.assertIn("role_record_added", kinds)


if __name__ == "__main__":
    unittest.main()
