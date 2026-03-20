# LangIdentify — Python packages

This directory contains two pip-installable Python packages:

| Directory | PyPI package | Description |
|-----------|-------------|-------------|
| `core/` | `langidentify` | The detection library, bundled with the lite model (~17 MB). |
| `full-model/` | `langidentify-full-model` | Full model data (~89 MB) for higher accuracy. |

There is also an `eval/` directory with command-line tools for evaluation and
benchmarking. These tools use the installed `langidentify` pip package (and its
bundled model data), so run `pip install langidentify` (or `pip install "langidentify[full]"`)
or `make install` first.

For the main project documentation, see the [top-level README](../README.md).

| Tool | Description |
|------|-------------|
| `eval/adhoc.py` | Detect the language of a single phrase with detailed score breakdown. |
| `eval/phrase_eval.py` | Evaluate detection accuracy against test-phrase files. |
| `eval/detection_speed.py` | Benchmark detection throughput (Mwords/s). |
| `eval/model_load_speed.py` | Benchmark model loading time and memory consumption. |

Example usage:

```bash
python3 eval/adhoc.py --languages efigs --phrase "Bonjour le monde"
python3 eval/phrase_eval.py --languages europe_west_common --testdir /path/to/phrases --misses
python3 eval/detection_speed.py --languages efigs --testdir /path/to/phrases --duration 30
python3 eval/model_load_speed.py --languages efigs --model lite
```

## Development

```bash
make install       # copies lite model + installs core in editable mode
make install-full  # copies both models + installs core and full-model
make test          # runs pytest
```

See [core/README.md](core/README.md) for full API documentation.
