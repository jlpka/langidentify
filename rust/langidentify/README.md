# langidentify (Rust)

A Rust implementation of the [langidentify](https://github.com/jlpka/langidentify) language
detection library. Detects 80+ languages using a combination of character n-gram frequency
analysis and whole-word ("topwords") frequency signals, both trained on the Wikipedia corpus.
Runs entirely offline with no network calls.

This is a port of the Java library, producing equivalent detection results from the same
model data files.

## Quick start

### Adding the dependency

Add langidentify to your `Cargo.toml` with a feature flag to select the model:

```toml
[dependencies]
langidentify = { version = "0.1", features = ["lite"] }
```

The model data is pulled in transitively via the feature flag — no need to list model
crates separately.

For the **full model** (not published to crates.io due to its ~89 MB size), use a git
dependency instead:

```toml
[dependencies]
langidentify = { git = "https://github.com/jlpka/langidentify", features = ["full"] }
```

Or, if you have a local checkout:

```toml
[dependencies]
langidentify = { path = "/path/to/langidentify/rust/langidentify/langidentify", features = ["lite"] }
# or features = ["full"] with a local checkout
```

### Basic usage

```rust
use langidentify::{Language, Model, Detector};
use std::sync::Arc;

let languages = Language::from_comma_separated("en,fr,de,es").unwrap();
let model = Arc::new(Model::load_lite(&languages).unwrap());
let mut detector = Detector::new(model);

assert_eq!(Language::French, detector.detect("Bonjour le monde"));
assert_eq!(Language::English, detector.detect("The quick brown fox"));
```

Use `Model::load_full()` for the full model (requires the `full` feature via git dependency).

## Inspecting results

After detection, `detector.results()` provides scoring details:

```rust
detector.detect("The quick brown fox");
let results = detector.results();
println!("{:?}", results.result);  // English
println!("{}", results.gap);       // confidence gap (0.0 = close, 1.0 = decisive)
println!("{}", results.scores.num_words);
```

## Incremental detection

For streaming or multi-part text, use the `add_text` API:

```rust
detector.clear_scores();
detector.add_text("Bonjour");
detector.add_text(" le monde");
let result = detector.compute_result();  // French
```

## Language boosts

When you have prior context (e.g. an HTTP Accept-Language header), you can bias detection
toward expected languages:

```rust
let fr_boost = model.build_boost_single(Language::French, 0.08);
let lang = detector.detect_with_boosts("message", &fr_boost);  // French
// Without the boost, "message" is ambiguous between English and French.
```

## Choosing languages

**Configure only the languages you actually need.** Each additional language increases model
loading time, memory usage, and detection latency. Closely related languages can cross-detect
on short phrases -- for example, adding Luxembourgish when you only need German may cause
short German phrases to be misidentified.

### Group aliases

Languages can be specified individually by ISO code or using group aliases:

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

```rust
let languages = Language::from_comma_separated("europe_west_common,cjk").unwrap();
```

### Supported languages (84)

**Latin script:** Afrikaans, Albanian, Azerbaijani, Basque, Catalan, Croatian, Czech, Danish,
Dutch, English, Esperanto, Estonian, Finnish, French, Ganda, German, Hungarian, Icelandic,
Indonesian, Irish, Italian, Latin, Latvian, Lithuanian, Luxembourgish, Malay, Maori, Norwegian,
Nynorsk, Oromo, Polish, Portuguese, Romanian, Shona, Slovak, Slovenian, Somali, Sotho, Spanish,
Swahili, Swedish, Tagalog, Tsonga, Tswana, Turkish, Vietnamese, Welsh, Xhosa, Yoruba, Zulu

**CJK:** Chinese Simplified, Chinese Traditional, Japanese, Korean

**Arabic script:** Arabic, Pashto, Persian, Urdu

**Cyrillic script:** Belarusian, Bulgarian, Macedonian, Mongolian, Russian, Serbian, Ukrainian

**Unique script:** Amharic, Armenian, Bengali, Burmese, Georgian, Greek, Gujarati, Hebrew,
Hindi, Kannada, Khmer, Lao, Malayalam, Punjabi, Sinhala, Tamil, Telugu, Thai, Tigrinya

## Lite vs. full model

Both models are trained from the same Wikipedia data but cropped at different probability floors:

| | Lite | Full |
|---|---|---|
| Log-probability floor | -12 | -15 |
| Memory (10 langs) | ~16 MB | ~89 MB |
| Memory (28 langs) | ~66 MB | ~362 MB |
| Load time (10 langs) | ~0.1s | ~0.8s |
| Best for | Most use cases | Maximum accuracy |

The lite model is recommended for most applications and is available on
[crates.io](https://crates.io/crates/langidentify). The full model provides a small accuracy
improvement, especially on very short text (word pairs), at the cost of ~3x memory and slower
load times. Due to its size (~89 MB compressed), the full model is not published to crates.io
and is available only via git or path dependency (see [Adding the dependency](#adding-the-dependency)).

## Performance

Benchmarked on a MacBook Air M4, 10 European languages
(`efigsnp,no,da,sv`), 10,000 sentences from the Lingua test corpus.

### Detection speed

| Configuration | Throughput | Latency |
|---|---|---|
| Lite, 10 languages | ~3.2 Mwords/s | ~312 ns/word |
| Full, 10 languages | ~2.5 Mwords/s | ~407 ns/word |

Detection speed scales roughly inversely with the number of configured languages (more
languages = more work per word). The detector is allocation-free in the hot path; all scoring
uses pre-allocated fixed arrays.

### Model loading

| Configuration | Load time | Resident memory | Peak during load |
|---|---|---|---|
| Lite, 10 languages | ~0.1s | ~16 MB | ~42 MB |
| Full, 10 languages | ~0.8s | ~89 MB | ~256 MB |
| Lite, 28 languages | ~0.4s | ~66 MB | ~181 MB |
| Full, 28 languages | ~2.2s | ~362 MB | ~1060 MB |

Load once and share across threads via `Arc<Model>`. The `Detector` is lightweight to
construct but **not thread-safe** -- use one per thread.

## Thread safety

`Model` is immutable after construction and safe to share across threads via `Arc<Model>`.
`Detector` is not thread-safe -- create one per thread:

```rust
use std::sync::Arc;

let model: Arc<Model> = Arc::new(Model::load_lite(&languages).unwrap());

// In each thread:
let mut detector = Detector::new(model.clone());
let lang = detector.detect(text);
```

## C/C++ FFI

The `langidentify-ffi` crate provides a C-compatible shared/static library for use from
C, C++, or any language with a C FFI. Build with:

```bash
cargo build --release -p langidentify-ffi --features lite
```

See the [langidentify-ffi README](langidentify-ffi/README.md) for the full API reference,
compiling/linking instructions, and thread-safety notes. A working C example is at
[`rust/eval/src/useffi.c`](../eval/src/useffi.c).

## Crate structure

```
langidentify/              Workspace root
  langidentify/            Core detection library
  langidentify-models/     Model data crates
    lite/                  Lite model facade (re-exports lite-a + lite-b)
    lite-a/                Lite model part A: European Latin (~8.6 MB)
    lite-b/                Lite model part B: rest of world (~8.4 MB)
    full/                  Full model data (~89 MB compressed)
  langidentify-ffi/        C FFI bindings (cdylib + staticlib)
```

The model crates use `include_bytes!` at build time to embed compressed model files directly
into the binary. No filesystem access is needed at runtime.

A separate `eval/` crate (outside the workspace, at `rust/eval/`) provides benchmarking and
evaluation tools:

- `adhoc` -- single-phrase detection with score breakdown
- `phrase-eval` -- batch accuracy evaluation against test corpora
- `detection-speed` -- throughput benchmark (Mwords/s, ns/word)
- `model-load-speed` -- load time and memory measurement
- `src/useffi.c` -- C FFI usage example

## Language-specific notes

### Norwegian dialects

Both Bokmal (`no`) and Nynorsk (`nn`) are supported. If you only care about Norwegian
without distinguishing dialects, configure just Bokmal (`no`), which has a 4x larger
training corpus. The two dialects cross-detect when both are configured.

### Afrikaans and Dutch

Afrikaans evolved from Dutch and the two remain largely mutually intelligible. When both
are configured, Afrikaans frequently cross-detects as Dutch. If you don't need to distinguish
them, configure only Dutch (`nl`).

### Malay and Indonesian

Malay (`ms`) and Indonesian (`id`) are closely related standardizations of the same language.
When both are configured, Malay frequently cross-detects as Indonesian. If you don't need to
distinguish them, configure only Indonesian (`id`).

### Accent removal

The `AccentRemover` utility is available for preprocessing text before detection. It maps
accented characters to ASCII equivalents (e.g. cafe from cafe, ss from ß, ae from æ). It is
not used internally by the detector -- accented characters are preserved during detection since
they carry language signal.

```rust
use langidentify::accent_remover::AccentRemover;

let remover = AccentRemover::new();
assert_eq!("cafe", remover.remove("café").as_ref());
assert_eq!("Strasse", remover.remove("Straße").as_ref());
```

## Building and testing

```bash
cd rust/langidentify
cargo build --release
cargo test
```

To run benchmarks (requires test data):

```bash
cd rust/eval
cargo run --release --bin detection-speed -- \
    --languages efigsnp,no,da,sv \
    --testdir /path/to/test/sentences \
    --duration 30

cargo run --release --bin model-load-speed -- \
    --languages efigsnp,no,da,sv
```

## Publishing to crates.io

```bash
cd rust/langidentify

# 1. Copy model data from the Java project into crate data/ directories.
bash scripts/copy_model_data.sh

# 2. Dry run (verifies packaging without uploading).
bash scripts/publish.sh

# 3. Publish for real (requires `cargo login` first).
bash scripts/publish.sh --execute
```

The publish script handles the correct dependency order (lite-a → lite-b → lite → langidentify)
and temporarily removes the full-model dependency (which is git-only) from the main crate
before uploading.

Note: crates.io has a 10 MB per-crate size limit. The lite model data (~17 MB total) is split
across two sub-crates (lite-a at ~8.6 MB and lite-b at ~8.4 MB) to stay within this limit.
The full model (~89 MB) is too large for crates.io and is only available via git dependency.

## How it works

See the [project README](https://github.com/jlpka/langidentify#how-it-works) for a detailed
description of the detection algorithm, including n-gram scoring, topword blending, alphabet-based
detection, CJK handling, and the probability model.

## License

Apache License 2.0 -- see [LICENSE](../../LICENSE).

The bundled models contain statistical parameters derived from Wikipedia text.
The models do not contain or reproduce Wikipedia text.
