# Evaluation and benchmarking tools

Command-line tools for evaluating detection accuracy and benchmarking
performance. All tools require `langidentify` to be installed (see the
[parent README](../README.md) for setup instructions).

All tools support `--model lite|full|best` to select the model variant
(default: `best`, which auto-detects the best available).

## adhoc.py

Detect the language of a single phrase, with a detailed per-language score
breakdown showing ngram scores, topword scores, and hit counts.

```bash
python3 adhoc.py --languages efigs --phrase "Bonjour le monde"
python3 adhoc.py --languages europe_west_common --phrase "Das ist ein Test" --model lite
```

## phrase_eval.py

Evaluate detection accuracy against a directory of test files. Each file is
named `{lang_code}.txt` and contains one test phrase per line. Reports
per-language accuracy with cross-detection breakdown, and flags known
cross-detection pairs (e.g. Malay/Indonesian, Afrikaans/Dutch,
Nynorsk/Norwegian).

```bash
python3 phrase_eval.py --languages efigs --testdir /path/to/phrases
python3 phrase_eval.py --languages europe_west_common --testdir /path/to/phrases --misses
python3 phrase_eval.py --languages efigs --testdir /path/to/phrases --removeaccents
```

Options:
- `--misses` — print each misdetected phrase to stderr
- `--removeaccents` — strip accents before detection

## detection_speed.py

Benchmark detection throughput. Loads test phrases into memory and repeatedly
detects them for a fixed duration, reporting Mwords/s and ns/word every 5
seconds.

```bash
python3 detection_speed.py --languages efigs --testdir /path/to/phrases
python3 detection_speed.py --languages efigsnp --testdir /path/to/phrases --duration 60 --model full
```

Options:
- `--duration <sec>` — benchmark duration in seconds (default: 30)
- `--removeaccents` — strip accents before detection

## model_load_speed.py

Benchmark model loading time and memory consumption. Loads the model multiple
times, reporting tracemalloc and RSS memory for each iteration.

```bash
python3 model_load_speed.py --languages efigs --model lite
python3 model_load_speed.py --languages europe_west_common --iterations 5
python3 model_load_speed.py --languages all --model full
```

Options:
- `--iterations <n>` — number of loading iterations to average (default: 3)
