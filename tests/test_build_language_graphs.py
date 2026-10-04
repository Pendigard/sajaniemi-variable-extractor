from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from src.python.build_language_graphs import run_joern_parse


class BuildLanguageGraphsTests(unittest.TestCase):
    def test_ruby_sources_are_staged_outside_the_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "ignored-worktree" / "code" / "test" / "Ruby"
            source.mkdir(parents=True)
            (source / "sample.rb").write_text("value = 1\n", encoding="utf-8")
            output = root / "graphs" / "ruby.bin"

            def inspect_command(command: list[str], check: bool) -> None:
                staged_source = Path(command[1])
                self.assertTrue(check)
                self.assertNotEqual(staged_source, source.resolve())
                self.assertEqual(staged_source.name, "Ruby")
                self.assertEqual(
                    (staged_source / "sample.rb").read_text(encoding="utf-8"),
                    "value = 1\n",
                )
                self.assertEqual(command[2:], ["--output", str(output.resolve()), "--language", "rubysrc"])

            with patch("src.python.build_language_graphs.subprocess.run", side_effect=inspect_command):
                run_joern_parse(source, output, "Ruby", force=False, dry_run=False)

    def test_non_ruby_sources_use_the_original_absolute_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Python"
            source.mkdir()
            output = root / "python.bin"

            with patch("src.python.build_language_graphs.subprocess.run") as run:
                run_joern_parse(source, output, "Python", force=False, dry_run=False)

            self.assertEqual(
                run.call_args.args[0],
                [
                    "joern-parse",
                    str(source.resolve()),
                    "--output",
                    str(output.resolve()),
                    "--language",
                    "pythonsrc",
                ],
            )


if __name__ == "__main__":
    unittest.main()
