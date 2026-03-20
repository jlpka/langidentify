# LangIdentify (Python)

A Python implementation of the [langidentify](https://github.com/jlpka/langidentify)
language detection library. Detects 80+ languages using a combination of ngram frequency
analysis and whole-word ("topwords") frequency signals, both trained on the Wikipedia corpus.
Runs entirely offline with no network calls.

This is a pure-Python port of the Java library, producing equivalent detection results
from the same model data files. For maximum performance, consider the
[Rust implementation](https://github.com/jlpka/langidentify/tree/main/rust/langidentify)
or the [Rust FFI bindings](https://github.com/jlpka/langidentify/tree/main/rust/langidentify/langidentify-ffi)
for use from C/C++.

Most language detection libraries rely solely on character ngram models. While
ngrams are an excellent primary signal, they struggle with short or ambiguous
text. LangIdentify augments ngram scoring with a topwords signal that identifies
common whole words from each language, giving it higher accuracy on short
sentences than other approaches -- even on two-word phrases.

## Quick start

### Install

```bash
pip install langidentify
```

For the full (higher accuracy) model:

```bash
pip install "langidentify[full]"
```

### Basic usage

```python
from langidentify import Detector, Model, Language

# Load the model for the languages you care about.
languages = Language.from_comma_separated("en,fr,de,es,it")
model = Model.load(languages)

# Create a detector (lightweight, not thread-safe -- use one per thread).
detector = Detector(model)

# Detect.
lang = detector.detect("Bonjour le monde")
print(lang)            # Language.FRENCH
print(lang.iso_code)   # fr
```

### Inspecting results

After detection, `detector.results` provides scoring details:

```python
detector.detect("The quick brown fox")
results = detector.results
print(results.result)  # Language.ENGLISH
print(results.gap)     # confidence gap (0.0 = close, 1.0 = decisive)
```

### Incremental detection

For streaming or multi-part text:

```python
detector.clear_scores()
detector.add_text("Bonjour")
detector.add_text(" le monde")
result = detector.compute_result()  # Language.FRENCH
```

### Language boosts

When you have prior context (e.g. an HTTP Accept-Language header), you can bias
detection toward expected languages:

```python
boosts = model.build_boost_array({Language.FRENCH: 0.08})
lang = detector.detect("message", boosts)  # FRENCH
# Without the boost, "message" is ambiguous between English and French.
```

### Loading from a filesystem path

If you prefer to point directly at model data files instead of using the
bundled package data:

```python
model = Model.load_from_path("/path/to/models/lite", languages)
```

## Choosing languages

**Configure only the languages you actually need.** Each additional language
increases loading time and memory usage. Closely related languages can
cross-detect on very short phrases -- for example, adding Luxembourgish when
you only need German may cause short German phrases to be misidentified.

Group aliases are supported for convenience:

| Alias | Languages |
|-------|-----------|
| `efigs` | English, French, Italian, German, Spanish |
| `efigsnp` | EFIGS + Dutch, Portuguese |
| `nordic` | Danish, Swedish, Norwegian, Finnish |
| `cjk` | Chinese (Simplified), Chinese (Traditional), Japanese, Korean |
| `europe_west_common` | EFIGSNP + Nordic |
| `europe_east_latin` | Albanian, Croatian, Czech, Estonian, Hungarian, Latvian, Lithuanian, Polish, Romanian, Slovak, Slovenian |
| `europe_cyrillic` | Belarusian, Bulgarian, Macedonian, Russian, Serbian, Ukrainian |
| `europe_common` | Western + Eastern European + Cyrillic |
| `europe_latin` | All European Latin-script languages |
| `europe` | All European languages (Latin + Cyrillic) |
| `latin_alphabet` | All Latin-script languages |
| `cyrillic_alphabet` | All Cyrillic-script languages |
| `arabic_alphabet` | Arabic, Pashto, Persian, Urdu |
| `unique_alphabet` | Languages where the script implies the language (Thai, Greek, Armenian, Georgian, etc.) |
| `all` | All 84 languages |

```python
languages = Language.from_comma_separated("europe_west_common,cjk")
```

## Lite vs. full model

Both models are trained from the same Wikipedia data but cropped at different
probability floors:

| | Lite | Full |
|---|---|---|
| Log-probability floor | -12 | -15 |
| Disk size (all languages) | ~17 MB | ~89 MB |
| Best for | Most use cases | Maximum accuracy when memory is not a concern |

By default, `Model.load()` auto-discovers which model variant is available,
preferring the full model. To force a variant:

```python
model = Model.load_lite(languages)   # recommended default
model = Model.load_full(languages)   # higher accuracy, more memory
```

### Getting the full model

The lite model is sufficient for most use cases. If you want the full model
for maximum accuracy, install the companion package:

```bash
pip install "langidentify[full]"
```

This installs the `langidentify-full-model` package, which provides the full
model data. Once installed, `Model.load()` will automatically prefer the full
model, or you can request it explicitly:

```python
model = Model.load_full(languages)
```

## CJK detection

Chinese/Japanese disambiguation is handled by the
[cjclassifier](https://pypi.org/project/cjclassifier/) package, which is
installed automatically as a dependency. Korean uses the distinct Hangul script
and is identified by alphabet alone.

## Thread safety

`Model` caches loaded data in a module-level dict protected by a lock.
`Detector` is lightweight to construct and intentionally **not thread-safe**.
For concurrent detection, use a separate instance per thread:

```python
import threading

model = Model.load(languages)  # shared, thread-safe

local = threading.local()

def get_detector():
    if not hasattr(local, "detector"):
        local.detector = Detector(model)
    return local.detector

# In each thread:
lang = get_detector().detect(text)
```

## Model load time

The language model needs to be loaded before the first detection, the
expensive part is the initial load, subsequent accesses are cached.
Load only the languages you need; each additional language adds to both load time
and memory, though the "unique_alphabet" languages are mostly free (e.g. Thai or Greek can be deduced from their alphabets).

### Load time and memory (lite model)

Measured on an Mac M4 with Python 3.13. Memory figures are from `tracemalloc`
(Python heap only; RSS will be higher because RSS isn't aggressively reclaimed).

| Language set | Languages | Load time | Memory | Ngram entries | Topword entries |
|---|---|---|---|---|---|
| `efigs` | 5 | ~0.25s | ~30 MB | 92K | 46K |
| `europe_west_common` | 11 | ~0.7s | ~55 MB | 165K | 91K |
| `all` | 84 | ~5.5s | ~300 MB | 986K | 604K |

The full model uses roughly 5x more memory and takes proportionally longer to
load. For many applications the lite model is recommended.

### Detection throughput

As a pure-Python implementation, detection runs at roughly ~180K words/s (~5,500
ns/word) with 10 languages on the lite model. For comparison, the Java and Rust
implementations run at ~3 Mwords/s (~330 ns/word) — about 14× faster. For
latency-sensitive applications, consider the
[Rust FFI bindings](https://github.com/jlpka/langidentify/tree/main/rust/langidentify/langidentify-ffi).

## Requirements

- Python 3.9+
- [cjclassifier](https://pypi.org/project/cjclassifier/) (installed automatically)

## License

Apache License 2.0 -- see [LICENSE](LICENSE).

The bundled models contain statistical parameters derived from Wikipedia text.
The models do not contain or reproduce Wikipedia text.
