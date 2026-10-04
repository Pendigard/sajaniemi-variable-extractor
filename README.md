# sajaniemi-variable-extractor
Static extractor for Sajaniemi’s variable roles across five programming languages, identifying and classifying variables by their semantic roles in source code.

## Run the extractor

Requires Python and the `joern` / `joern-parse` commands on `PATH`. From the repository root, this example extracts the included Python fixtures:

```sh
mkdir -p graph
joern-parse tests/fixtures/code/Python --output graph/python.bin --language pythonsrc
conda run -n torcharm python -m src.python.run_dynamic_extractors \
  --graph-dir graph \
  --code-root tests/fixtures/code \
  --output outputs/roles.json
```

The command writes role annotations to `outputs/roles.json` and `outputs/roles.jsonl`, plus all resolved variables to `outputs/roles.variable_facts.json`.
