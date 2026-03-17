# LangIdentify

A fast, accurate language detection library for Java.

LangIdentify detects the language of text using a combination of ngram frequency analysis and
whole-word ("topwords") frequency signals, both trained on the Wikipedia corpus.
It supports 80+ languages across Latin, Cyrillic, Arabic, CJK, and many other scripts.
It runs entirely offline with no network calls.

## Why LangIdentify?

Most language detection libraries rely solely on character ngram models. While ngrams are
an excellent primary signal, they struggle with short or ambiguous text. Consider:

- "was it Jimmy?" (English) vs. "was ist Jimmy?" (German) -- a single character difference
- "Where is Oberammergau?" -- clearly English, even though most ngrams look German

LangIdentify augments ngram scoring with a **topwords signal** that identifies common whole words
from each language. This was the original motivation for writing the library: we needed higher
accuracy on short sentences than existing libraries could provide.

### Design goals

- **Accuracy** -- blended ngram + topwords scoring, especially effective on short text
- **Speed** -- open-addressing hash tables, zero allocations in the detection path, fixed arrays
- **Low memory** -- the 28-language `europe_common` model is ~60 MB (lite) or ~305 MB (full);
  load only the languages you need
- **Extensible** -- adding a new language is straightforward if it has a reasonably sized
  Wikipedia edition

## Quick start

### Maven dependency

```xml
<!-- Core detection library -->
<dependency>
    <groupId>com.jlpka.langidentify</groupId>
    <artifactId>langidentify-lib</artifactId>
    <version>1.0.2</version>
</dependency>

<!-- Bundled model data (choose one) -->
<dependency>
    <groupId>com.jlpka.langidentify</groupId>
    <artifactId>langidentify-models-lite</artifactId>
    <version>1.0.2</version>
</dependency>
<!-- or: langidentify-models-full for higher accuracy at more memory cost -->
```

### Basic usage

```java
import com.jlpka.langidentify.*;
import java.util.List;

// Load the lite model for the languages you care about (throws IOException).
List<Language> languages = Language.fromCommaSeparated("en,fr,de,es,it");
Model model = Model.loadLite(languages);

// Create a detector (lightweight, not thread-safe -- use one per thread).
Detector detector = new Detector(model);

// Detect.
Language lang = detector.detect("Bonjour le monde");
System.out.println(lang);           // FRENCH
System.out.println(lang.isoCode()); // fr
```

### Inspecting results

After detection, `detector.results()` provides scoring details:

```java
detector.detect("The quick brown fox");
Detector.Results results = detector.results();
System.out.println(results.result);  // ENGLISH
System.out.println(results.gap);     // confidence gap (0.0 = close, 1.0 = decisive)
System.out.println(results);         // full per-language score breakdown
```

### Incremental detection

For streaming or multi-part text, use the addText API:

```java
detector.clearScores();
detector.addText("Bonjour");
detector.addText(" le monde");
Language result = detector.computeResult();  // FRENCH
```

This also supports `char[]` and `Reader` inputs.

### Language boosts

When you have prior context (e.g. an HTTP Accept-Language header or user locale), you can
bias detection toward expected languages:

```java
double[] frenchBoost = model.buildBoostArray(Language.FRENCH, 0.08);
Language lang = detector.detect("message", frenchBoost);  // FRENCH
// Without the boost, "message" is ambiguous between English and French.
```

## Choosing languages

**Try to only configure the languages you actually need.** Each additional language increases model
loading time, memory usage, and detection latency. More importantly, closely related languages
can cross-detect on very short phrases -- for example, adding Luxembourgish when you only
need German may cause short German phrases to be misidentified.

In addition to being able to specify a list of languages, LangIdentify some group aliases for convenience:

| Alias | Languages |
|-------|-----------|
| `latin_alphabet` | All Latin-script languages |
| `cjk` | Chinese (Simplified), Chinese (Traditional), Japanese, Korean |
| `cyrillic_alphabet` | All Cyrillic-script languages |
| `unique_alphabet` | Languages where the alphabet implies a language, e.g. Thai or Greek |
| `europe_west_common` | EFIGSNP + Danish, Swedish, Norwegian, Finnish |
| `europe_common` | Western + Eastern European + Cyrillic |
| `efigs` | English, French, Italian, German, Spanish |
| `efigsnp` | EFIGS + Dutch, Portuguese |
| `nordic` | Danish, Swedish, Norwegian, Finnish |

```java
List<Language> langs = Language.fromCommaSeparated("europe_west_common,cjk");
```

Note that languages trained on smaller Wikipedia corpora may be less accurate.

## Lite vs. full model

Both models are trained from the same Wikipedia data but cropped at different probability floors:

| | Lite | Full |
|---|---|---|
| Log-probability floor | -12 (&#x2248; 6.1 &times; 10&#x207b;&#x2076;) | -15 (&#x2248; 3.1 &times; 10&#x207b;&#x2077;) |
| Memory (28 langs) | ~60 MB | ~305 MB |
| Best for | Most use cases; good accuracy/memory balance | Maximum accuracy when memory is not a concern |

```java
Model lite = Model.loadLite(languages);  // recommended default
Model full = Model.loadFull(languages);  // when you need every last bit of accuracy
```

## Accuracy comparison

LangIdentify was benchmarked against two other well-known Java detection libraries:
[Lingua](https://github.com/pemistahl/lingua) and
[Shuyo LangDetect](https://github.com/shuyo/language-detection) (optimaize fork).
Test data is from [Lingua's accuracy report corpus](https://github.com/pemistahl/lingua/tree/main/src/accuracyReport/resources/language-testdata).

### Sentences (Lingua test corpus, all supported languages loaded)

Each library was loaded with all of its supported languages (LangIdentify: 84, Shuyo: 70,
Lingua: 75) and evaluated on 10 European language test sets (1,000 sentences each).

| Language | LangIdentify (full) | LangIdentify (lite) | Lingua | Shuyo LangDetect |
|----------|:-------------------:|:-------------------:|:------:|:-----------------:|
| English  | **100.0%** | 99.9% | 99.1% | 99.3% |
| French   | **99.8%** | 99.7% | 98.8% | 99.0% |
| German   | **99.9%** | 99.8% | 99.7% | 99.8% |
| Danish   | **99.6%** | 98.9% | 97.8% | 94.3% |
| Finnish  | **100.0%** | 100.0% | 100.0% | 99.9% |
| Italian  | **100.0%** | 99.9% | 99.7% | 99.2% |
| Spanish  | **99.7%** | 99.4% | 96.7% | 97.3% |
| Portuguese | **99.8%** | 99.8% | 97.9% | 98.8% |
| Dutch    | **100.0%** | 100.0% | 96.2% | 97.0% |
| Swedish  | **99.4%** | 98.8% | 98.7% | 96.3% |

### Word pairs (Lingua test corpus, all supported languages loaded) -- where short-text accuracy matters most

| Language | LangIdentify (full) | LangIdentify (lite) | Lingua | Shuyo LangDetect |
|----------|:-------------------:|:-------------------:|:------:|:-----------------:|
| English  | **94.3%** | 91.4% | 88.6% | 57.7% |
| French   | **96.1%** | 93.6% | 94.5% | 78.6% |
| German   | **94.6%** | 90.8% | 94.1% | 72.7% |
| Danish   | **84.9%** | 80.6% | 83.9% | 69.0% |
| Finnish  | **98.8%** | 97.9% | 98.0% | 95.5% |
| Italian  | **95.9%** | 93.5% | 91.9% | 81.2% |
| Spanish  | **79.2%** | 76.1% | 68.7% | 43.5% |
| Portuguese | **88.5%** | 83.4% | 85.3% | 58.3% |
| Dutch    | **83.2%** | 75.5% | 80.7% | 49.6% |
| Swedish  | **91.2%** | 82.8% | 88.6% | 66.5% |

LangIdentify wins all 10 languages. The advantage is most
pronounced on short text, where the topwords signal makes the biggest difference. Note that
word-pair accuracy drops for all libraries when the full language set is loaded, since
two-word phrases are inherently ambiguous and more candidate languages increase the chance of
a false match. Shuyo's percentages are somewhat inflated because it skips phrases it cannot
classify (e.g. 191 of 1,000 Spanish word pairs), while LangIdentify and Lingua always
produce a result.

### Word pairs with narrowed language set (10 languages loaded)

If you know the likely languages in advance, configuring only those languages substantially
improves short-text accuracy. The table below shows LangIdentify word-pair results with only
the 10 test languages loaded, compared to all 84:

| Language | Full (10 langs) | Full (84 langs) | Lite (10 langs) | Lite (84 langs) |
|----------|:---------------:|:---------------:|:---------------:|:---------------:|
| English  | **97.8%** | 83.4% | 95.4% | 78.9% |
| French   | **97.7%** | 95.9% | 95.8% | 92.9% |
| German   | **96.8%** | 94.9% | 94.0% | 91.2% |
| Danish   | **95.9%** | 84.9% | 93.9% | 80.2% |
| Finnish  | **99.4%** | 98.8% | 98.7% | 97.9% |
| Italian  | **97.8%** | 96.4% | 95.5% | 93.7% |
| Spanish  | **84.6%** | 78.9% | 82.8% | 75.7% |
| Portuguese | **90.5%** | 88.6% | 86.5% | 83.3% |
| Dutch    | **91.8%** | 84.7% | 88.6% | 77.3% |
| Swedish  | **94.5%** | 91.1% | 90.1% | 82.7% |

Narrowing from 84 to 10 languages improves overall word-pair accuracy from 89.8% to 94.7%
(full model). The gain is largest for languages that share vocabulary with many others --
English jumps from 83.4% to 97.8%, and Danish from 84.9% to 95.9%. For applications
processing very short text, configuring only the expected languages is one of the most
effective ways to improve accuracy.

## Speed comparison

Detection throughput was benchmarked on the same 10 European language sentence corpus
(10,000 phrases). Each library was tested in two configurations: with only the 10 test
languages loaded, and with all supported languages loaded (which increases per-phrase work
since every language must be scored).

### 10 languages loaded

| Library | Mwords/s | ns/word |
|---|:---:|:---:|
| **LangIdentify (lite)** | **2.92** | **342** |
| LangIdentify (full) | 1.91 | 523 |
| Shuyo LangDetect | 1.03 | 969 |
| Lingua | 0.17 | 6,016 |

### All supported languages loaded

| Library | Mwords/s | ns/word | Languages |
|---|:---:|:---:|:---:|
| **LangIdentify (lite)** | **1.29** | **774** | 84 |
| LangIdentify (full) | 0.87 | 1,153 | 84 |
| Shuyo LangDetect | 0.34 | 2,933 | 70 |
| Lingua | 0.04 | 25,082 | 75 |

LangIdentify lite with all 84 languages loaded is still faster than Shuyo with only 10
languages. The relative performance gap widens as more languages are added, since
LangIdentify's open-addressing hash tables and fixed-array scoring scale more efficiently
than the alternatives. LangIdentify's hot loop operates on `char[]` primitives and avoids heap allocations.

Benchmarks were run single-threaded on a MacBook Air M4. Absolute throughput will vary by
machine; relative comparisons between libraries are the more useful metric.

## How it works

### Signals

LangIdentify combines two statistical signals, both derived from Wikipedia:

1. **ngrams** -- character subsequences extracted from each word. For example, "hello" yields
   the 3-grams "hel", "ell", "llo". The relative frequencies of these ngrams differ across
   languages and form the primary detection signal. We typically evaluate 5-grams down to
   1-grams, stopping at 3-grams if the word is fully covered.

2. **Topwords** -- whole-word frequencies for common words like "the", "what", "vous", "ist".
   This signal is critical for short phrases where ngrams alone are ambiguous. For example,
   "was ist..." vs. "was it..." differ by a single character -- word frequencies make the
   distinction clear.

### Probability model

For each ngram and topword, we compute per-language log-probabilities from Wikipedia frequency
data. We use log-space because raw probabilities are extremely small numbers (the product of
many small per-token probabilities). For instance, a probability of 0.00003% becomes
log(3 &times; 10&#x207b;&#x2077;) &approx; -15. In log-space, multiplication becomes addition, which is
both faster and avoids floating-point underflow.

There is a probability floor below which statistical noise dominates. Training data is
domain-specific (Wikipedia), so overly precise probabilities would overfit. The lite model
crops at log-probability -12 (&approx; 6.1 &times; 10&#x207b;&#x2076;) and the full model at -15
(&approx; 3.1 &times; 10&#x207b;&#x2077;). Ngrams and words not present in the model are assigned
the floor probability.

### Scoring

For each word in the input:

- **ngram scoring**: we look up ngrams from 5-grams down to 1-grams in open-addressing hash
  tables, summing log-probabilities per language. If all tiles of a given ngram size are found
  (fully covered), we skip smaller sizes as an optimization.
- **Topword scoring**: the whole word is looked up in a separate topwords table. Single
  Latin-alphabet characters without accents are excluded, since isolated letters are not
  language-indicative.
- **Apostrophe handling**: words like "l'homme" are split at the apostrophe and each part
  ("l'" and "homme") is looked up separately as a topword. Apostrophes are included in ngrams
  (e.g. "d'u" is a valid 3-gram), which benefits languages like French, Italian, and English.

The ngram and topword signals are normalized and blended, with topwords weighted more heavily
when topword coverage is high (i.e. when many of the input words have topword hits).

### Alphabet-based detection

For scripts that uniquely identify a language -- such as Thai, Georgian, Armenian, or Burmese --
detection is immediate based on the script alone, with no ngram lookup required. Ngram data is
only loaded for alphabets shared by multiple configured languages (e.g. Latin, Cyrillic, Arabic).
These can be added with the "unique_alphabet" alias.

When text contains multiple scripts (e.g. "He likes to say привет"), words are segmented at
script boundaries and the **predominant alphabet** is determined by weighted character count.
CJK ideographs are weighted 3&times; and Korean/Kana 2&times; to reflect their higher linguistic
density per character. Only languages using the predominant alphabet are considered for the final
result. For example, "我的名字是Jonathan" detects as Chinese because 4 HAN characters at
3&times; weight outweigh 8 Latin characters at 1&times;.

### Chinese, Japanese, and Korean

CJK detection is handled by the related
[CJClassifier](https://github.com/jlpka/cjclassifier) library. Chinese and Japanese share
the same Unicode ideograph range and don't use spaces between words (with an average "word"
length of roughly 1.5 characters), so standard ngram approaches don't work well. CJClassifier
uses character unigram and adjacent-character bigram frequencies instead, also trained on
Wikipedia data, to distinguish Chinese Simplified, Chinese Traditional, and Japanese.

Korean uses the distinct Hangul script and is identified by alphabet.

### Skipwords

A small set of language-independent tokens (e.g. "http", "www") are marked as skipwords and
excluded from scoring entirely.

### Case and accents

All text is lowercased before scoring. Accented characters are preserved for detection (e.g.
"caf&eacute;" retains the accent in both ngram and topword lookups).

### What we tried and didn't keep

We experimented with topword bigrams (e.g. the French sequence "y a" from "il y a") but found
the memory cost was not justified by the marginal improvement in aggregate accuracy, even when
restricted to bigrams of short words.

## Practical notes

### Norwegian dialects

Both Bokm&aring;l (`no`) and Nynorsk (`nn`) are supported. If you only care about the Norwegian
language cluster without distinguishing dialects, configure just Bokm&aring;l (`no`), which has a
4x larger training corpus. The two dialects are similar enough that they cross-detect at some
rate when both are configured.

### Serbo-Croatian

We use Croatian (`hr`) for Latin-script and Serbian (`sr`) for Cyrillic-script detection.
Bosnian has its own Wikipedia edition, but is statistically so close to Croatian that it
cross-detects heavily (~55% accuracy), so it is not included as a separate language. Montenegrin
does not have its own Wikipedia edition.

### Wikipedia evaluation caveats

When evaluating on Wikipedia text (as opposed to curated test sets), one recurring issue is that
articles contain foreign-language text (e.g. a French article quoting English). This means a
measured accuracy of, say, 98.8% is typically closer to 100% in practice -- most of the
"misses" are genuinely not in the expected language.

## Adding a new language

A new language can be added if it has a reasonably sized Wikipedia edition.

1. **Download the Wikipedia dump** (e.g. for Nynorsk):
   ```
   https://dumps.wikimedia.org/nnwiki/20260201/nnwiki-20260201-pages-articles.xml.bz2
   ```

2. **Extract ngrams and topwords** using the provided script:
   ```bash
   python3 scripts/calcngrams.py --alphabet latin --languages nn
   ```

3. **Reduce to model thresholds** using ModelBuilder:
   ```bash
   export INVOKEBUILDER="java -cp tools/target/langidentify-tools-1.0.2.jar \
       com.jlpka.langidentify.tools.ModelBuilder"

   # Lite model (-12/-12)
   $INVOKEBUILDER reducengrams --infile ../wikidata/derived/ngrams-nn.txt \
       --outfile models-lite/src/main/resources/com/jlpka/langidentify/models/lite/ngrams-nn.txt.gz \
       --minlogprob -12.0
   $INVOKEBUILDER reducetopwords --infile ../wikidata/derived/topwords-nn.txt \
       --outfile models-lite/src/main/resources/com/jlpka/langidentify/models/lite/topwords-nn.txt.gz \
       --twminlogprob -12.0

   # Full model (-15/-15)
   $INVOKEBUILDER reducengrams --infile ../wikidata/derived/ngrams-nn.txt \
       --outfile models-full/src/main/resources/com/jlpka/langidentify/models/full/ngrams-nn.txt.gz \
       --minlogprob -15.0
   $INVOKEBUILDER reducetopwords --infile ../wikidata/derived/topwords-nn.txt \
       --outfile models-full/src/main/resources/com/jlpka/langidentify/models/full/topwords-nn.txt.gz \
       --twminlogprob -15.0
   ```

4. **Add the language enum** in `Language.java` if it doesn't already exist, and rebuild.

## Project structure

```
langidentify-parent
  core/        langidentify-lib       Core detection library
  models-lite/ langidentify-models-lite  Bundled lite model data
  models-full/ langidentify-models-full  Bundled full model data
  tools/       langidentify-tools     Evaluation and model building tools
```

## Thread safety

`Model` is heavyweight to load the first time (disk I/O), but the loaded data is cached as a
static singleton. `Detector` is lightweight to construct and intentionally **not thread-safe**. For
concurrent detection, use a separate instance per thread (e.g. via `ThreadLocal`):

```java
Model model = Model.loadLite(languages);  // shared, thread-safe
ThreadLocal<Detector> detector = ThreadLocal.withInitial(() -> new Detector(model));

// In each thread:
Language lang = detector.get().detect(text);
```

## Building from source

```bash
mvn clean package
```

This produces:

- `core/target/langidentify-lib-1.0.2.jar` -- the core library
- `models-lite/target/langidentify-models-lite-1.0.2.jar` -- bundled lite model data
- `models-full/target/langidentify-models-full-1.0.2.jar` -- bundled full model data
- `tools/target/langidentify-tools-1.0.2.jar` -- uber-JAR for evaluation and model building

To run tests:

```bash
mvn test
```

## Requirements

- Java 11+

## Contributing

Contributions are welcome! Please open an issue or pull request at
[github.com/jlpka/langidentify](https://github.com/jlpka/langidentify).

Before submitting a PR, make sure all tests pass:

```bash
mvn test
```

## Contact

- **Author**: Jeremy Lilley
- **GitHub**: [github.com/jlpka/langidentify](https://github.com/jlpka/langidentify)
- **Email**: jeremy@jlilley.net

## License

Apache License 2.0 -- see [LICENSE](LICENSE).

The bundled models contain statistical parameters derived from Wikipedia text.
The models do not contain or reproduce Wikipedia text.
