from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sajaniemi_extractor.run_dynamic_extractors import extract_group


class GroupedOutputTests(unittest.TestCase):
    def test_one_split_bundle_contains_both_repositories_with_distinct_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            split = root / "training"
            repositories = [split / "alpha", split / "beta"]
            for repo in repositories:
                repo.mkdir(parents=True)
                (repo / "main.py").write_text("value = 1\n", encoding="utf-8")

            def fake_joern(graph: Path, script: Path, pre: Path, source: Path) -> None:
                hint = {"path": "main.py", "line": 1, "column": 1,
                        "name": "value", "code": "value"}
                record = {
                    "schema_version": 1,
                    "concept": {"family": "sajaniemi_role", "name": "fixed_value"},
                    "subject": {"id": "main.py::value", "joern_id": 1, "name": "value",
                                "kind": "local", "path": "main.py",
                                "scope": {"type": "file", "name": "main.py",
                                          "qualified_name": "main.py"},
                                "_declaration_line": 1, "_declaration_column": 1},
                    "views": {"declaration": [{"usage": "declaration", "_hint": hint}],
                              "identifier": [{"usage": "declaration", "_hint": hint}],
                              "reads": [], "writes": [], "updates": [],
                              "state_mutations": [], "control_context": [], "scope": []},
                }
                pre.write_text(json.dumps({"schema_version": 1, "role_annotations": [record],
                                           "variable_facts": []}), encoding="utf-8")

            output = root / "results" / "training"
            graphs = [(root / "graphs" / f"{repo.name}.bin", repo) for repo in repositories]
            with patch("sajaniemi_extractor.run_dynamic_extractors.run_joern", side_effect=fake_joern):
                extract_group(graphs, split, output / "roles.json",
                              jsonl_output=output / "roles.jsonl",
                              variable_facts_output=output / "variable_facts.json",
                              legacy_output=output / "legacy.json",
                              keep_pre=output / "pre.json")

            roles = json.loads((output / "roles.json").read_text(encoding="utf-8"))
            self.assertEqual({record["subject"]["path"] for record in roles},
                             {"alpha/main.py", "beta/main.py"})
            self.assertEqual(len({record["subject"]["id"] for record in roles}), 2)
            pre = json.loads((output / "pre.json").read_text(encoding="utf-8"))
            self.assertEqual({record["subject"]["path"] for record in pre["role_annotations"]},
                             {"alpha/main.py", "beta/main.py"})
            self.assertEqual(len({record["subject"]["id"] for record in pre["role_annotations"]}), 2)
            self.assertEqual(len((output / "roles.jsonl").read_text().splitlines()), 2)
            self.assertFalse((output / "alpha").exists())
            self.assertFalse((output / "beta").exists())


if __name__ == "__main__":
    unittest.main()
