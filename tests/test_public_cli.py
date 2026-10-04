from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sajaniemi_extractor.build_language_graphs import run_joern_parse
from sajaniemi_extractor.cli import main


class PublicCliTests(unittest.TestCase):
    def test_profile_command_calls_tool_implementation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            graphs = root / "graphs"
            source = root / "source"
            graphs.mkdir()
            source.mkdir()
            (graphs / "python.bin").write_bytes(b"graph")
            report = root / "profile.json"
            with patch("sajaniemi_extractor.cli.profile_graphs") as profile:
                self.assertEqual(main(["profile", "--graph-dir", str(graphs), "--source", str(source),
                                       "--report", str(report)]), 0)
            self.assertEqual(profile.call_args.args[:4], (graphs, source, report, ["python"]))

    def test_compare_command_calls_tool_implementation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = root / "before"
            after = root / "after"
            before.mkdir()
            after.mkdir()
            with patch("sajaniemi_extractor.cli.compare_bundle", return_value={"equal": True}) as compare:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(main(["compare", "--before", str(before), "--after", str(after)]), 0)
            compare.assert_called_once_with(before, after)

    def test_force_dry_run_keeps_graph_and_directory_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Python"
            source.mkdir()
            (source / "example.py").write_text("a = 1\n", encoding="utf-8")
            output = root / "new-output" / "python.bin"
            captured = io.StringIO()
            with patch("sajaniemi_extractor.build_language_graphs.subprocess.run") as run:
                with contextlib.redirect_stdout(captured):
                    run_joern_parse(source, output, "Python", force=True, dry_run=True)
            self.assertFalse(output.parent.exists())
            run.assert_not_called()
            self.assertIn(f"joern-parse {source.resolve()} --output {output.resolve()} --language pythonsrc", captured.getvalue())

            output.parent.mkdir()
            output.write_bytes(b"existing graph")
            with patch("sajaniemi_extractor.build_language_graphs.subprocess.run") as run:
                with contextlib.redirect_stdout(captured):
                    run_joern_parse(source, output, "Python", force=True, dry_run=True)
            self.assertEqual(output.read_bytes(), b"existing graph")
            run.assert_not_called()

    def test_run_dry_run_does_not_create_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            output = root / "bundle"
            with patch("sajaniemi_extractor.build_language_graphs.subprocess.run") as run:
                self.assertEqual(main(["run", "--source", str(source), "--language", "python",
                                       "--output-dir", str(output), "--dry-run"]), 0)
            self.assertFalse(output.exists())
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
