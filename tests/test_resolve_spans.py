from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from src.python.resolve_spans import resolve_annotations


class ResolveSpansTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_source(self, relative_path: str, text: str) -> Path:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def resolve(self, annotations: list[object]) -> list[dict]:
        pre_annotations = self.root / "pre_annotations.json"
        pre_annotations.write_text(json.dumps(annotations), encoding="utf-8")
        return resolve_annotations(pre_annotations, self.root)

    @staticmethod
    def annotation(name: str, column: object, **overrides: object) -> dict:
        annotation = {
            "concept": "temporary",
            "variant": "reference",
            "path": "Python/example.py",
            "line": 1,
            "column": column,
            "name": name,
        }
        annotation.update(overrides)
        return annotation

    def test_resolves_two_occurrences_of_same_identifier_on_one_line(self) -> None:
        source = "pair = repeated + repeated\n"
        self.write_source("Python/example.py", source)

        resolved = self.resolve([
            self.annotation("repeated", 8),
            self.annotation("repeated", 19),
        ])

        self.assertEqual([item["span_start"] for item in resolved], [7, 18])
        self.assertEqual([source[item["span_start"]:item["span_end"]] for item in resolved], ["repeated", "repeated"])
        self.assertEqual([item["column"] for item in resolved], [8, 19])

    def test_keeps_nearby_identifier_names_distinct(self) -> None:
        source = "item = items[item_index] + item_value\n"
        self.write_source("Python/example.py", source)
        names = ["item", "items", "item_index", "item_value"]
        columns = [1, 8, 14, 28]

        resolved = self.resolve([
            self.annotation(name, column, concept="fixed_value")
            for name, column in zip(names, columns)
        ])

        extracted = [source[item["span_start"]:item["span_end"]] for item in resolved]
        self.assertEqual(extracted, names)

    def test_uses_full_relative_path_for_basename_collisions(self) -> None:
        python_source = "language_value = 1\n"
        c_source = "int language_value = 2;\n"
        self.write_source("Python/shared.py", python_source)
        self.write_source("C/shared.py", c_source)

        resolved = self.resolve([
            self.annotation("language_value", 1, path="Python/shared.py"),
            self.annotation("language_value", 5, path="C/shared.py"),
        ])

        self.assertEqual([item["path"] for item in resolved], ["C/shared.py", "Python/shared.py"])
        self.assertEqual([item["column"] for item in resolved], [5, 1])

    def test_accepts_an_unambiguous_basename_path(self) -> None:
        source = "basename_value = basename_value + 1\n"
        self.write_source("Python/nested/example.py", source)

        resolved = self.resolve([
            self.annotation("basename_value", 18, path="example.py"),
        ])

        self.assertEqual(len(resolved), 1)
        self.assertEqual(resolved[0]["path"], "Python/nested/example.py")
        self.assertEqual(source[resolved[0]["span_start"]:resolved[0]["span_end"]], "basename_value")
        self.assertEqual(resolved[0]["column"], 18)

    def test_deduplicates_identical_annotations_with_stable_sorting(self) -> None:
        source = "second = first + second\n"
        self.write_source("Python/example.py", source)
        duplicate = self.annotation("second", 18, concept="gatherer")

        first_run = self.resolve([
            duplicate,
            self.annotation("first", 10, concept="fixed_value"),
            dict(duplicate),
        ])
        second_run = self.resolve([
            dict(duplicate),
            self.annotation("first", 10, concept="fixed_value"),
            duplicate,
        ])

        self.assertEqual(first_run, second_run)
        self.assertEqual(len(first_run), 2)
        self.assertEqual(
            [source[item["span_start"]:item["span_end"]] for item in first_run],
            ["first", "second"],
        )

    def test_skips_missing_files_and_unresolvable_or_malformed_annotations(self) -> None:
        self.write_source("Python/example.py", "present_name = 1\n")

        resolved = self.resolve([
            self.annotation("present_name", 1),
            self.annotation("present_name", 1, path="Python/missing.py"),
            self.annotation("absent_name", 1),
            self.annotation("present_name", 1, line=99),
            self.annotation("present_name", "not-a-column"),
            {"path": "Python/example.py", "line": "not-a-line", "name": "present_name"},
            "not-an-annotation",
        ])

        self.assertEqual(len(resolved), 1)
        self.assertEqual(resolved[0]["span_start"], 0)
        self.assertEqual(resolved[0]["span_end"], len("present_name"))


if __name__ == "__main__":
    unittest.main()
