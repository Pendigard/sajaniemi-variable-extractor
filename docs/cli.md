# Command-line reference

The installed command is `sajaniemi-variable-extractor`; `python -m sajaniemi_extractor` calls the same parser. Install the checkout as described in the [README](../README.md) and put Joern **4.0.550** executables `joern` and `joern-parse` on `PATH`. Paths below are relative to the working directory unless absolute paths are supplied. All commands accept `-h` or `--help`.

The public CLI supports exactly five modes: `run`, `parse`, `extract`, `profile`, and `compare`. The [module commands](#module-commands) remain available for lower-level or inspection workflows. They are not additional public subcommands.

## Source layout and languages

`run` discovers source files recursively and accepts these input shapes:

```text
single/                  repos/                   splits/
  src/main.py              project_a/main.py        training/project_a/main.py
  tests/test_main.py        project_b/main.rb        evaluation/project_b/main.rb
```

- `single`: one language tree, potentially nested; one output bundle and a graph named for the language, such as `graphs/python.bin`.
- `repos`: each immediate child directory is a repository, potentially in a different supported language; one combined bundle, with graphs such as `graphs/project_a.bin` and paths prefixed by repository name.
- `splits`: each immediate child is a named split containing repository directories; one bundle under each split name, such as `outputs/training/roles.json`.
- `auto` (default): direct source files imply `single`; a directory containing at least two populated nested repository children can imply `splits`; several same-language subdirectories usually imply `single`, while mixed-language children imply `repos`. Use an explicit layout for ambiguous nesting, especially a collection with one repository.

The language detector recognizes C (`.c`, optionally `.h`), C++ (`.cc`, `.cpp`, `.cxx`, `.c++`, uppercase `.C`/`.H`, `.hh`, `.hpp`, `.hxx`, `.h++`, `.ipp`, optionally `.c`/`.h`), JavaScript (`.js`, `.jsx`, `.mjs`, `.cjs`), Python (`.py`), and Ruby (`.rb`). Matching is case-insensitive except for uppercase `.C` and `.H`. A `.h`-only tree needs `--language C` or `C++` with `run --layout single`. A tree containing unsupported source extensions such as `.java` or `.cs`, several unrelated languages, or no supported files is rejected. Hidden files and directories are ignored during discovery. `parse` does not detect a language; supply one explicitly.

## Output bundle

`run` and `extract` write a bundle at their output directory:

| File | Content |
| --- | --- |
| `roles.json` | JSON array of source-resolved role annotations, one per variable-role pair. |
| `roles.jsonl` | The same role annotations, one JSON object per line. |
| `variable_facts.json` | JSON array of resolved variables, including declarations with no role and their source views. |
| `legacy.json` | Older token-annotation representation derived from roles. |
| `pre.json` | Merged, unresolved Scala output (`schema_version`, role annotations, and variable facts). |
| `graphs/*.bin` | Reusable Joern graphs created by `run`; `extract` reads existing graphs elsewhere. |

Source views include declaration, identifier, reads, writes, updates, state mutations, control context, and scope when available. A lack of role labels does not prove semantic absence. Graph reuse is based on the output file's existence, not source freshness; use `--force` after edits. `pre.json` is intended for inspection and may have different counts from resolved output.

## `run`

Parse discovered source trees and extract a complete bundle. It invokes `joern-parse` per repository and then `joern` on each graph with the selected Scala script. For Ruby, parsing uses a temporary isolated copy so an enclosing Git ignore rule does not hide source files.

```text
sajaniemi-variable-extractor run --source PATH [--language NAME]
  [--layout {auto,single,repos,splits}] [--output-dir PATH]
  [--force] [--dry-run] [--scala-script PATH]
```

| Argument | Required | Default | Description |
| --- | --- | --- | --- |
| `--source PATH` | Yes | — | Root of a source tree, repository collection, or named splits. Must exist. |
| `--language NAME` | No | Detect | Single-tree language check or `.h`-only C/C++ disambiguation. Accepts C, C++, JavaScript, Python, Ruby, case-insensitively, or graph stems `c`, `cpp`, `javascript`, `python`, `ruby`. Invalid for grouped repositories/splits. |
| `--layout VALUE` | No | `auto` | `auto`, `single`, `repos`, or `splits`; controls directory interpretation. |
| `--output-dir PATH` | No | Invocation working directory | Root for bundles and `graphs/`. For splits, a subdirectory per split is added. |
| `--force` | No | False | Delete and rebuild an existing graph before extraction. |
| `--dry-run` | No | False | Print parse commands; do not write graphs or bundles. If an existing graph is being reused without `--force`, it prints a skip instead of a parse command. |
| `--scala-script PATH` | No | Bundled `src/scala/extract_dynamic_variables.sc` | Scala extraction script. Resolved from the checkout or installed distribution; must exist for extraction. |

```bash
sajaniemi-variable-extractor run --source tests/fixtures/code/Python \
  --layout single --output-dir outputs/example
```

Malformed child repositories in grouped layouts are warned about and skipped; failure of an individual parse may also be skipped. A failed split may be skipped if other splits complete. The command fails if no bundle completes. For a single tree, a Joern failure is an error. Joern output is normally quiet and included on failure. An existing graph can be stale; `--force` is the explicit refresh mechanism.

## `parse`

Build one `.bin` graph with `joern-parse`, without running the Scala role analysis.

```text
sajaniemi-variable-extractor parse --source PATH --language NAME --output FILE
  [--force] [--dry-run]
```

| Argument | Required | Default | Description |
| --- | --- | --- | --- |
| `--source PATH` | Yes | — | Existing directory passed to `joern-parse`. |
| `--language NAME` | Yes | — | C, C++, JavaScript, Python, Ruby, or their graph-stem aliases; mapped to Joern frontends `c`, `javascript`, `pythonsrc`, or `rubysrc`. C and C++ both use Joern `c`. `parse` does not compare this choice to detected extensions. |
| `--output FILE` | Yes | — | Destination graph file, conventionally `.bin`. |
| `--force` | No | False | Rebuild when the output exists; otherwise an existing graph is skipped. |
| `--dry-run` | No | False | Print the Joern parse command without creating a graph. |

```bash
sajaniemi-variable-extractor parse --source tests/fixtures/code/Python \
  --language Python --output outputs/manual/python.bin
```

The source directory must exist. An invalid language, missing executable, or Joern failure prevents graph creation. `--dry-run` with an existing graph prints a skip unless `--force` is also set.

## `extract`

Run the Scala extractor over every `*.bin` file directly inside a graph directory, resolve source spans, and write a bundle. This does not rebuild graphs. All selected graphs are resolved against the same `--source` root; use `run` to preserve per-repository roots and path prefixes in grouped layouts.

```text
sajaniemi-variable-extractor extract --graph-dir PATH --source PATH
  --output-dir PATH [--scala-script PATH]
```

| Argument | Required | Default | Description |
| --- | --- | --- | --- |
| `--graph-dir PATH` | Yes | — | Existing directory containing one or more direct-child `.bin` graphs. |
| `--source PATH` | Yes | — | Existing original source root used for source-line and exact-span resolution. |
| `--output-dir PATH` | Yes | — | Bundle destination; created as needed. |
| `--scala-script PATH` | No | Bundled script | Existing Scala extraction script. |

```bash
sajaniemi-variable-extractor extract --graph-dir outputs/manual \
  --source tests/fixtures/code/Python --output-dir outputs/manual-results
```

Missing graphs, missing source root, missing script, or failed Joern extraction are errors. The command invokes `joern` once per graph and combines their unresolved output before span resolution.

## `profile`

Measure Joern extraction and Python resolution on existing graphs. This writes a JSON report with per-graph timings, metrics, role counts, digests, and approximate process-tree RSS when `ps` is permitted. It does not write an annotation bundle. The profiler sets `SAJANIEMI_PROFILE=1` for its Joern subprocess.

```text
sajaniemi-variable-extractor profile --graph-dir PATH --source PATH
  --report FILE [--languages STEMS] [--scala-script PATH]
```

| Argument | Required | Default | Description |
| --- | --- | --- | --- |
| `--graph-dir PATH` | Yes | — | Existing directory containing direct-child `.bin` graphs. |
| `--source PATH` | Yes | — | Existing source root for resolution. |
| `--report FILE` | Yes | — | JSON report path; parent directory is created. |
| `--languages STEMS` | No | All direct-child `.bin` stems | Comma-separated graph stems such as `python,ruby`; despite the name, these select filenames, not language detection. |
| `--scala-script PATH` | No | Bundled script | Scala extraction script used during profiling. |

```bash
sajaniemi-variable-extractor profile --graph-dir outputs/manual \
  --source tests/fixtures/code/Python --report outputs/profile.json \
  --languages python
```

Empty selection, a missing selected graph, a failed Joern process, or missing Scala profiling output prevents a report. RSS is sampled and may miss a short peak.

## `compare`

Compare two complete output bundles, including byte-level files, variable roles and views, and subject-level differences. It prints JSON to standard output and optionally saves the same report.

```text
sajaniemi-variable-extractor compare --before PATH --after PATH [--report FILE]
```

| Argument | Required | Default | Description |
| --- | --- | --- | --- |
| `--before PATH` | Yes | — | Existing earlier bundle directory. |
| `--after PATH` | Yes | — | Existing later bundle directory. |
| `--report FILE` | No | Standard output only | Save report JSON here; parent directory is created. |

```bash
sajaniemi-variable-extractor compare --before outputs/baseline \
  --after outputs/example --report outputs/comparison.json
```

All five JSON/JSONL bundle files listed above are expected. A missing file appears as a `missing_output` delta; malformed JSON raises an error. Exit status is `0` only when bundles are equal by the comparator's checks and `1` for differences. A comparison report is still printed for differences.

## Module commands

These are executable Python modules installed with the project (or available from the checkout). They are useful for lower-level workflows and retain their own argument names and defaults. Their defaults may refer to historical `code/` or `graph/` layouts that are not present in a fresh public clone; pass explicit paths for reproducible use.

### Build graphs: `sajaniemi_extractor.build_language_graphs`

```text
python -m sajaniemi_extractor.build_language_graphs --code-root PATH
  --output-dir PATH [--layout {auto,single,repos,splits}] [--force] [--dry-run]
```

`--code-root` and `--output-dir` are required. `--layout` defaults to `auto`; `--force` and `--dry-run` default to false and have the same parse behavior as `run`. It discovers source trees and writes graphs under `OUTPUT_DIR/graphs/` or `OUTPUT_DIR/SPLIT/graphs/`; it does not extract roles. Invalid child repositories are skipped with warnings, and all-invalid input fails.

```bash
python -m sajaniemi_extractor.build_language_graphs \
  --code-root tests/fixtures/code/Python --output-dir outputs/graphs-only
```

### Extract graphs: `sajaniemi_extractor.run_dynamic_extractors`

```text
python -m sajaniemi_extractor.run_dynamic_extractors
  [--graph-dir PATH] [--code-root PATH] [--scala-script PATH]
  [--output FILE] [--jsonl-output FILE] [--variable-facts-output FILE]
  [--legacy-output FILE] [--keep-pre FILE]
```

| Argument | Default | Description |
| --- | --- | --- |
| `--graph-dir` | `graph` | Direct-child `.bin` graphs to extract. |
| `--code-root` | `code` | Original source root. |
| `--scala-script` | Bundled script | Scala extraction script. |
| `--output` | `dynamic_annotations.json` | Resolved role array. |
| `--jsonl-output` | Output name with `.jsonl` suffix | Resolved role JSONL. |
| `--variable-facts-output` | Output stem plus `.variable_facts.json` | Resolved variable facts. |
| `--legacy-output` | None | Optional legacy annotations; omitted by default. |
| `--keep-pre` | None | Optional path to retain unresolved merged Scala output; otherwise temporary. |

This calls `joern` for each graph, then resolves against `--code-root`. The graph and source directories, and Scala script, must exist. For example:

```bash
python -m sajaniemi_extractor.run_dynamic_extractors \
  --graph-dir outputs/manual --code-root tests/fixtures/code/Python \
  --output outputs/direct/roles.json --keep-pre outputs/direct/pre.json
```

### Resolve legacy spans: `sajaniemi_extractor.resolve_spans`

```text
python -m sajaniemi_extractor.resolve_spans PRE_ANNOTATIONS
  [--code-root PATH] [--output FILE]
```

The positional `PRE_ANNOTATIONS` is required. `--code-root` defaults to `code`; `--output` defaults to `dynamic_annotations.json`. This legacy resolver reads pre-annotations and writes resolved annotations, without invoking Joern. Its input is the legacy annotation shape expected by `resolve_annotations`, not the complete `pre.json` bundle created by the public CLI. The output parent is not created automatically.

```bash
python -m sajaniemi_extractor.resolve_spans path/to/legacy-pre-annotations.json \
  --code-root path/to/source --output outputs/resolved.json
```

### Profile: `tools.profile_extraction`

```text
python -m tools.profile_extraction [--graph-dir PATH] [--languages STEMS]
  [--code-root PATH] [--scala-script PATH] --report FILE
```

`--report` is required. Defaults are `graph/test`, `c,cpp,javascript,python,ruby`, `code/test`, and the bundled Scala script for the other arguments, respectively. These graph/source defaults are repository-root based; use `--graph-dir`, `--code-root`, and `--languages` for your own data. It invokes Joern and writes the same profiling report as public `profile`.

```bash
python -m tools.profile_extraction --graph-dir outputs/manual \
  --languages python --code-root tests/fixtures/code/Python \
  --report outputs/module-profile.json
```

### Compare: `tools.compare_annotations`

```text
python -m tools.compare_annotations --before PATH --after PATH [--report FILE]
```

Arguments, output, and exit status are the same as public `compare`. For example:

```bash
python -m tools.compare_annotations --before outputs/baseline --after outputs/example
```

### Inspect annotations: `tools.show_random_annotations`

```text
python -m tools.show_random_annotations [VARIABLE_FACTS] [--split {train,test}]
  [--code-root PATH] [--role ROLE [ROLE ...]] [--exclude-roles]
  [--view VIEW] [--name TEXT] [--name-match-mode MODE]
  [--per-role N | --per-concept N] [--context N]
  [--max-span-lines N] [--seed N]
```

| Argument | Default | Description |
| --- | --- | --- |
| `VARIABLE_FACTS` | `outputs/<split>_variable_facts.json` | Optional positional JSON array of variable records. Public bundles use `variable_facts.json`, so pass its path explicitly. |
| `--split` | `train` | `train` or `test`; affects default variable-facts and source paths only. |
| `--code-root` | `code/<split>` | Source root for displaying snippets. |
| `--role` | `all` | One or more of `fixed_value`, `stepper`, `gatherer`, `walker`, `follower`, `most_recent_holder`, `most_wanted_holder`, `one_way_flag`, `temporary`, `organizer`, `container`, `no-role`, or `all`. Matching multiple choices uses OR; `all` cannot be combined. |
| `--exclude-roles` | False | Invert an explicit `--role` filter; invalid with default `all`. |
| `--view` | `identifier`, or `all` with `--name` | `all`, `declaration`, `identifier`, `reads`, `writes`, `updates`, `state_mutations`, `control_context`, or `scope`. |
| `--name` | None | Filter `subject.name`; when supplied, print every matching view by default. |
| `--name-match-mode` | `contain` | `contain`, `prefix`, `suffix`, or `exact_match`; used with `--name`. |
| `--per-role`, `--per-concept` | 10 normally; unlimited with `--name` | Maximum samples per role; aliases for one option. Must be non-negative. |
| `--context` | 2 | Source lines of context around each view; non-negative. |
| `--max-span-lines` | 20 | Maximum rendered lines per view; `0` means unlimited. |
| `--seed` | None | Random sampling seed for repeatability. |

The module reads files and prints source views; it does not invoke Joern or write output. A missing source file, malformed variable JSON, invalid filter combination, or negative count causes failure.

```bash
python -m tools.show_random_annotations outputs/example/variable_facts.json \
  --code-root tests/fixtures/code/Python --role stepper --seed 1
```
