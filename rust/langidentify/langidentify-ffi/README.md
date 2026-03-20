# langidentify-ffi

C/C++ bindings for the [langidentify](https://github.com/jlpka/langidentify) language
detection library. Produces a shared library (`liblangidentify_ffi.dylib` / `.so`) and a
static archive (`liblangidentify_ffi.a`) with a flat C function interface.

All strings are passed as `(const char*, size_t)` pairs — not null-terminated — and must be
valid UTF-8. Returned ISO code pointers point to static memory and must **not** be freed by
the caller.

## Building

From the workspace root (`rust/langidentify/`):

```bash
# With the embedded lite model (~7 MB, recommended):
cargo build --release -p langidentify-ffi --features lite

# Or with the full model (~89 MB, higher accuracy):
cargo build --release -p langidentify-ffi --features full

# Or without embedded models (use langid_model_load_path at runtime):
cargo build --release -p langidentify-ffi
```

Build artifacts:

```
target/release/liblangidentify_ffi.dylib   # macOS shared library
target/release/liblangidentify_ffi.so       # Linux shared library
target/release/liblangidentify_ffi.a        # static archive (all platforms)
```

The C header is at `include/langidentify.h`.

## Quick start

```c
#include "langidentify.h"

/* Load the embedded lite model for 5 languages */
LangidModel* model = NULL;
int32_t rc = langid_model_load_lite("en,fr,de,es,it", 14, &model);
if (rc != LANGID_OK) { /* handle error */ }

/* Create a detector */
LangidDetector* detector = NULL;
langid_detector_new(model, &detector);

/* Detect */
const char* iso;
size_t iso_len;
langid_detect(detector, "Bonjour le monde", 16, &iso, &iso_len);
/* iso = "fr", iso_len = 2 */

double gap;
langid_detector_gap(detector, &gap);
/* gap > 0 means confident; closer to 0 means ambiguous */

/* Cleanup */
langid_detector_free(detector);
langid_model_free(model);
```

## API reference

### Error codes

| Code | Constant | Meaning |
|------|----------|---------|
| 0 | `LANGID_OK` | Success |
| -1 | `LANGID_ERR_NULL` | A required pointer argument was NULL |
| -2 | `LANGID_ERR_UTF8` | A string argument was not valid UTF-8 |
| -3 | `LANGID_ERR_LOAD` | Model loading failed (bad path, missing files, etc.) |
| -4 | `LANGID_ERR_PARAM` | Invalid parameter value |
| -5 | `LANGID_ERR_PANIC` | Internal panic (should not occur in normal use) |

### Model lifecycle

```c
/* Load from a directory on disk */
int32_t langid_model_load_path(
    const char* dir_path, size_t dir_path_len,
    const char* languages_csv, size_t languages_csv_len,
    double min_log_prob, double tw_min_log_prob, double cj_min_log_prob,
    LangidModel** out_model);

/* Load embedded lite model (requires --features lite) */
int32_t langid_model_load_lite(
    const char* languages_csv, size_t languages_csv_len,
    LangidModel** out_model);

/* Load embedded full model (requires --features full) */
int32_t langid_model_load_full(
    const char* languages_csv, size_t languages_csv_len,
    LangidModel** out_model);

/* Free a model (safe to call with NULL) */
void langid_model_free(LangidModel* model);

/* Number of languages in the model */
size_t langid_model_num_languages(const LangidModel* model);

/* ISO code of the language at index idx (do NOT free the returned pointer) */
int32_t langid_model_language_iso(
    const LangidModel* model, size_t idx,
    const char** out_iso, size_t* out_iso_len);
```

For `langid_model_load_path`, pass `0.0` for any log-probability floor to use the default.
The `languages_csv` parameter accepts the same comma-separated format as the Rust API,
including group aliases like `"efigs"` or `"europe_west_common,cjk"`.

### Detector lifecycle

```c
int32_t langid_detector_new(
    const LangidModel* model,
    LangidDetector** out_detector);

void langid_detector_free(LangidDetector* detector);
```

The detector holds an internal reference to the model, so the model remains alive even if
`langid_model_free` is called on the original handle.

### One-shot detection

```c
int32_t langid_detect(
    LangidDetector* detector,
    const char* text, size_t text_len,
    const char** out_iso, size_t* out_iso_len);

int32_t langid_detect_with_boosts(
    LangidDetector* detector,
    const char* text, size_t text_len,
    const double* boosts, size_t boosts_len,
    const char** out_iso, size_t* out_iso_len);
```

### Incremental detection

For streaming or multi-part text:

```c
langid_detector_clear(detector);
langid_detector_add_text(detector, part1, part1_len);
langid_detector_add_text(detector, part2, part2_len);

const char* iso;
size_t iso_len;
langid_detector_compute_result(detector, &iso, &iso_len);
```

### Results access

```c
/* Confidence gap (0.0 = ambiguous, higher = more confident) */
int32_t langid_detector_gap(const LangidDetector* detector, double* out_gap);

/* Number of words scored in the last detection */
size_t langid_detector_num_words(const LangidDetector* detector);

/* Per-language total scores (caller-allocated array) */
int32_t langid_detector_total_scores(
    const LangidDetector* detector,
    double* out_scores, size_t out_scores_len);

/* ISO code of the cached result (does not re-run detection) */
int32_t langid_detector_result_iso(
    const LangidDetector* detector,
    const char** out_iso, size_t* out_iso_len);
```

### Boost helpers

```c
/* Build a boost array with a single boosted language */
int32_t langid_model_build_boost_single(
    const LangidModel* model,
    const char* lang_iso, size_t lang_iso_len,
    double boost,
    double* out_boosts, size_t out_boosts_len);
```

## Compiling and linking

### Dynamic linking

```bash
cc -O2 myapp.c \
    -I path/to/langidentify-ffi/include \
    -L path/to/target/release \
    -llangidentify_ffi \
    -o myapp
```

At runtime, set the library search path:

```bash
# macOS
DYLD_LIBRARY_PATH=path/to/target/release ./myapp

# Linux
LD_LIBRARY_PATH=path/to/target/release ./myapp
```

### Static linking

```bash
cc -O2 myapp.c \
    -I path/to/langidentify-ffi/include \
    path/to/target/release/liblangidentify_ffi.a \
    -lpthread -ldl -lm \
    -o myapp
```

On macOS you may also need `-framework Security -framework CoreFoundation`.

Static linking produces a self-contained binary with no runtime library dependencies.

## Thread safety

- **`LangidModel`** is immutable after creation. It is safe to share one model across
  multiple threads — just pass the same `LangidModel*` to `langid_detector_new` in each
  thread.
- **`LangidDetector`** is **not** thread-safe. Create one per thread.

A typical pattern for a multi-threaded C application:

```c
/* Main thread: load once */
LangidModel* model = NULL;
langid_model_load_lite("efigs", 5, &model);

/* Each worker thread: */
LangidDetector* det = NULL;
langid_detector_new(model, &det);
/* ... use det for detection ... */
langid_detector_free(det);

/* Main thread: free after all workers are done */
langid_model_free(model);
```

## Working example

See [`rust/eval/src/useffi.c`](../../eval/src/useffi.c) for a complete working example that loads a
model, detects a phrase, and prints per-language score breakdowns. Build and run instructions
are in the file header.

## License

Apache License 2.0 — see [LICENSE](../../../LICENSE).
