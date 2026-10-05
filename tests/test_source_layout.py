from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
import warnings
from pathlib import Path
from unittest.mock import patch

from sajaniemi_extractor.cli import main
from sajaniemi_extractor.source_layout import detect_language, discover_sources


class SourceLayoutTests(unittest.TestCase):
    def test_cpp_source_wins_over_c_and_ambiguous_headers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "project"
            (source / "src").mkdir(parents=True)
            (source / "src" / "main.c").write_text("int x;\n")
            (source / "src" / "extra.cpp").write_text("int y;\n")
            (source / "src" / "api.h").write_text("int z;\n")
            self.assertEqual(detect_language(source), "C++")
            self.assertEqual([(j.source, j.language, j.output_parts) for j in discover_sources(source)],
                             [(source, "C++", ())])

    def test_c_and_header_only_cases(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "api.h").write_text("int x;\n")
            with self.assertRaisesRegex(ValueError, "ambiguous .h"):
                detect_language(root)
            self.assertEqual(detect_language(root, hint="C++"), "C++")
            (root / "main.c").write_text("int x;\n")
            self.assertEqual(detect_language(root), "C")
            (root / "main.cpp").write_text("int y;\n")
            self.assertEqual(detect_language(root), "C++")
            (root / "script.py").write_text("x = 1\n")
            with self.assertRaisesRegex(ValueError, "Several languages"):
                detect_language(root)

    def test_uppercase_cpp_extensions_and_unsupported_sources(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "api.H").write_text("int x;\n")
            self.assertEqual(detect_language(root), "C++")
            (root / "other.cs").write_text("class X {}\n")
            with self.assertRaisesRegex(ValueError, "Unsupported source extensions"):
                detect_language(root)

    def test_mixed_single_tree_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "x.py").write_text("x = 1\n")
            (root / "x.rb").write_text("x = 1\n")
            with self.assertRaisesRegex(ValueError, "Several languages"):
                discover_sources(root)

    def test_same_language_subdirectories_are_one_tree_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("src", "tests"):
                child = root / name
                child.mkdir()
                (child / "example.py").write_text("x = 1\n")
            self.assertEqual(discover_sources(root), [discover_sources(root, "single")[0]])
            self.assertEqual(len(discover_sources(root, "repos")), 2)

    def test_collection_skips_invalid_directories(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, file in [("python-repo", "x.py"), ("ruby-repo", "x.rb"),
                               ("mixed", "x.py")]:
                (root / name).mkdir()
                (root / name / file).write_text("x = 1\n")
            (root / "mixed" / "x.rb").write_text("x = 1\n")
            (root / "empty").mkdir()
            with warnings.catch_warnings(record=True) as caught:
                warnings.simplefilter("always")
                jobs = discover_sources(root)
            self.assertEqual([j.output_parts for j in jobs],
                             [("python-repo",), ("ruby-repo",)])
            self.assertEqual(len(caught), 2)

    def test_dynamic_splits_and_default_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for split in ("training", "validation"):
                for name, file in (("one", "x.py"), ("two", "x.rb")):
                    repo = root / "input" / split / name
                    repo.mkdir(parents=True)
                    (repo / file).write_text("x = 1\n")
            jobs = discover_sources(root / "input")
            self.assertEqual([j.output_parts for j in jobs],
                             [("training", "one"), ("training", "two"),
                              ("validation", "one"), ("validation", "two")])
            with patch("sajaniemi_extractor.cli.run_joern_parse") as parse, \
                 patch("sajaniemi_extractor.cli.extract_group") as extract:
                with patch("sajaniemi_extractor.cli.Path.cwd", return_value=root / "destination"):
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(main(["run", "--source", str(root / "input")]), 0)
            self.assertEqual([call.args[1] for call in parse.call_args_list],
                             [root / "destination" / split / "graphs" / f"{repo}.bin"
                              for split in ("training", "validation") for repo in ("one", "two")])
            self.assertEqual([call.args[2] for call in extract.call_args_list],
                             [root / "destination" / split / "roles.json"
                              for split in ("training", "validation")])
            self.assertTrue(all(len(call.args[0]) == 2 for call in extract.call_args_list))

    def test_batch_continues_after_joern_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("one", "two"):
                repo = root / name
                repo.mkdir()
                (repo / "main.py").write_text("x = 1\n")
            with patch("sajaniemi_extractor.cli.run_joern_parse", side_effect=[RuntimeError("Joern failure"), None]) as run, \
                 patch("sajaniemi_extractor.cli.extract_group") as extract:
                with warnings.catch_warnings(record=True) as caught:
                    warnings.simplefilter("always")
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(main(["run", "--source", str(root), "--layout", "repos",
                                               "--output-dir", str(root / "out")]), 0)
            self.assertEqual(run.call_count, 2)
            self.assertEqual(len(extract.call_args.args[0]), 1)
            self.assertIn("Joern failure", str(caught[0].message))


if __name__ == "__main__":
    unittest.main()
