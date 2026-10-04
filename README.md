# Sajaniemi variable extractor

A Joern-based static extractor that classifies variables by Sajaniemi roles. It parses source code into a code property graph, runs the Scala role analysis, then resolves variable positions against the original source files.

## Install and run

Requirements: Python 3.10 or newer, `joern` and `joern-parse` on `PATH`. From a clone of this repository:

```sh
conda activate torcharm
python -m pip install -e .
sajaniemi-variable-extractor run \
  --source tests/fixtures/code/Python \
  --language Python \
  --output-dir outputs/example
```

`run` accepts a directory of source files, a language (`C`, `C++`, `C#`, `JavaScript`, `Python`, or `Ruby`), and a destination directory. The main result is `roles.json`. The same directory also contains `roles.jsonl`, `variable_facts.json`, `legacy.json`, and `pre.json`; its `graphs/` subdirectory contains the reusable Joern graph. `roles.json` holds role annotations; `variable_facts.json` contains all resolved variables and their views. `pre.json` is the merged, unresolved Scala output. `legacy.json` provides token annotations for older consumers.

An existing graph is reused. Pass `--force` to rebuild it. `--dry-run` prints the exact `joern-parse` command and makes no filesystem changes; for Ruby it also shows the temporary staging copy. Run `sajaniemi-variable-extractor --help` or `<subcommand> --help` for all options.

## Advanced commands

```sh
sajaniemi-variable-extractor parse --source tests/fixtures/code/Python --language Python --output graph/python.bin
sajaniemi-variable-extractor extract --graph-dir graph --source tests/fixtures/code/Python --output-dir outputs/example
sajaniemi-variable-extractor profile --graph-dir graph --source tests/fixtures/code/Python --report outputs/profile.json
sajaniemi-variable-extractor compare --before outputs/baseline --after outputs/example --report outputs/comparison.json
```

`parse` builds one graph. `extract` accepts all `.bin` files in a graph directory and writes the same bundle as `run`. `profile` measures Joern and Python resolution on existing graphs. `compare` reports file and variable-level differences between two complete bundles, exiting with status 1 when they differ.

The Python functions live in `src/sajaniemi_extractor/` (`run_pipeline`, `extract_graphs`, `profile_graphs`, and `compare_bundle`); the CLI only supplies arguments. Joern scripts live in `src/scala/`. Direct profiling and comparison script entry points are in `tools/`. `src/python/` remains as compatibility imports for earlier code. Install with `-e` so the Scala scripts are found beside the Python sources and edits take effect immediately.

## Test

```sh
PYTHONPATH=src conda run -n torcharm python -m pytest -q
```

The ordinary suite runs without Joern integration; opt into the real Joern fixture test with `RUN_JOERN_INTEGRATION=1` when Joern is installed. The tests validate fixtures and contracts, not correctness on an arbitrary corpus.
