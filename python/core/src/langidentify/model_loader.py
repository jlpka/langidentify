# Copyright 2026 Jeremy Lilley (jeremy@jlilley.net)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Loader for ngram-based language detection models.

Reads per-language ngram and topword files and builds a Model. Use
Model.load() or Model.load_from_path() as the public entry points.
"""

import array
import gzip
import io
import logging
import math
import os
import threading
import time

from importlib import resources

from langidentify.alphabet import Alphabet
from langidentify.language import Language

logger = logging.getLogger(__name__)

_cache = {}
_cache_lock = threading.Lock()

# Sentinel value for poisoned (skipword) entries in a compacted table.
# Distinct from None so dict.get() can distinguish "not found" from "skip".
SKIP = object()

# Sentinel for an empty table.
EMPTY = {}


# ========================================================================
# Table building utilities (used only during model loading)
# ========================================================================

class _LangProbListBuilder:
    """Mutable builder for accumulating (lang_index, probability) entries.

    Uses array.array instead of Python lists to store values as raw C types,
    reducing per-element memory from ~28 bytes (Python int/float) to 2/4 bytes.
    """

    __slots__ = ("_lang_indices", "_probs", "_poisoned")

    def __init__(self):
        self._lang_indices = array.array("H")  # unsigned short (2 bytes each)
        self._probs = array.array("f")          # float32 (4 bytes each)
        self._poisoned = False

    def poison(self):
        self._poisoned = True

    def is_poisoned(self):
        return self._poisoned

    def add(self, lang_idx, prob):
        if not self._poisoned:
            self._lang_indices.append(lang_idx)
            self._probs.append(prob)

    def lang_indices(self):
        return self._lang_indices

    def probs(self):
        return self._probs


class _TableBuilder:
    """Mutable builder for constructing an ngram table dict.

    Accumulate entries with put/get, then call compact() to produce a
    plain dict mapping ngram strings to ``(lang_indices, probs)`` tuples,
    or the ``SKIP`` sentinel for poisoned (skipword) entries.
    """

    def __init__(self):
        self._entries = {}

    def put(self, key, value):
        self._entries[key] = value

    def get(self, key):
        return self._entries.get(key)

    def compact(self, prob_floor=0.0):
        table = {}
        # Intern cache: deduplicate identical tuples to save memory.
        # lang_indices tuples repeat heavily (many ngrams map to the same
        # single language); probs tuples are less likely to repeat but
        # interning is essentially free.
        intern_cache = {}
        for key, builder in self._entries.items():
            if builder.is_poisoned():
                table[key] = SKIP
            else:
                probs = builder.probs()
                if prob_floor != 0.0:
                    probs = [max(p, prob_floor) for p in probs]
                idx_tuple = tuple(builder.lang_indices())
                prob_array = array.array("f", probs)
                idx_tuple = intern_cache.setdefault(idx_tuple, idx_tuple)
                # Intern prob arrays by converting to bytes for hashable lookup.
                prob_key = prob_array.tobytes()
                existing = intern_cache.get(prob_key)
                if existing is not None:
                    prob_array = existing
                else:
                    intern_cache[prob_key] = prob_array
                table[key] = (idx_tuple, prob_array)
        return table


def clear_cache():
    """Clears the model cache, forcing the next load to re-read from disk."""
    with _cache_lock:
        _cache.clear()


# ========================================================================
# Resource resolvers
# ========================================================================

class FileResolver:
    """Resolves model files from a filesystem directory."""

    def __init__(self, raw):
        stripped = raw.rstrip(os.sep).rstrip("/")
        if os.path.isdir(stripped):
            self._prefix = stripped + os.sep
        else:
            self._prefix = stripped

    def exists(self, name):
        return os.path.exists(self._prefix + name)

    def open(self, name):
        path = self._prefix + name
        if name.endswith(".gz"):
            return io.TextIOWrapper(
                gzip.open(path, "rb"), encoding="utf-8")
        return open(path, "r", encoding="utf-8")


class PackageResolver:
    """Resolves model files from the installed Python package data.

    For the "full" variant, also checks the companion
    ``langidentify-full-model`` package (installed via
    ``pip install langidentify[full]``).
    """

    def __init__(self, variant):
        self._variant = variant
        self._packages = [f"langidentify.models.{variant}"]
        if variant == "full":
            self._packages.append("langidentify_full_model.data")

    def _find_ref(self, name):
        for pkg in self._packages:
            try:
                ref = resources.files(pkg).joinpath(name)
                with resources.as_file(ref) as path:
                    if path.exists():
                        return ref
            except (FileNotFoundError, ModuleNotFoundError, TypeError):
                continue
        return None

    def exists(self, name):
        return self._find_ref(name) is not None

    def open(self, name):
        ref = self._find_ref(name)
        if ref is None:
            raise FileNotFoundError(
                f"Model file {name} not found in packages: {self._packages}")
        if name.endswith(".gz"):
            return io.TextIOWrapper(
                gzip.open(ref.open("rb"), "rb"), encoding="utf-8")
        return ref.open("r", encoding="utf-8")


# ========================================================================
# Entry points
# ========================================================================

def load_from_path(prefix, languages, min_log_prob=0, tw_min_log_prob=0,
                   cj_min_log_prob=0):
    """Loads (or returns cached) a Model from a filesystem path."""
    resolver = FileResolver(prefix)
    return _load_with_resolver(
        resolver, languages, min_log_prob, tw_min_log_prob, cj_min_log_prob,
        cache_prefix=prefix)


def load(languages, min_log_prob=0, tw_min_log_prob=0, cj_min_log_prob=0):
    """Loads a Model from the package data, auto-discovering the variant."""
    if not languages:
        raise ValueError("At least one language required")
    test_file = f"ngrams-{languages[0].iso_code}.txt.gz"
    for variant in ("full", "lite"):
        resolver = PackageResolver(variant)
        if resolver.exists(test_file):
            return load_variant(
                variant, languages, min_log_prob, tw_min_log_prob,
                cj_min_log_prob)
    raise IOError(
        f"No model found for {languages[0].iso_code}. "
        "Ensure model data is installed (run 'make models' or install "
        "the langidentify package with model data).")


def load_variant(variant, languages, min_log_prob=0, tw_min_log_prob=0,
                 cj_min_log_prob=0):
    """Loads (or returns cached) a Model from the package for a named variant."""
    resolver = PackageResolver(variant)
    return _load_with_resolver(
        resolver, languages, min_log_prob, tw_min_log_prob, cj_min_log_prob,
        cache_prefix=f"package:{variant}")


def load_lite(languages):
    """Loads a Model forcing the lite variant (or full files with lite thresholds)."""
    if not languages:
        raise ValueError("At least one language required")
    test_file = f"ngrams-{languages[0].iso_code}.txt.gz"
    lite_resolver = PackageResolver("lite")
    if lite_resolver.exists(test_file):
        return load_variant("lite", languages, -12, -12)
    return load_variant("full", languages, -12, -12)


def load_full(languages):
    """Loads a Model forcing the full variant."""
    return load_variant("full", languages, 0, 0)


# ========================================================================
# Core loading logic
# ========================================================================

def _load_with_resolver(resolver, languages, min_log_prob, tw_min_log_prob,
                        cj_min_log_prob, cache_prefix):
    # Canonicalize language order (sorted by enum declaration order).
    lang_set = sorted(set(languages), key=lambda l: list(Language).index(l))
    ordered_langs = lang_set

    skip_topwords = math.isnan(tw_min_log_prob) if isinstance(tw_min_log_prob, float) else False
    cache_key = (
        cache_prefix + ":"
        + ",".join(l.iso_code for l in ordered_langs)
        + f":{min_log_prob}"
        + (":notw" if skip_topwords else f":{tw_min_log_prob}")
        + f":cj{cj_min_log_prob}")

    cached = _cache.get(cache_key)
    if cached is not None:
        return cached

    with _cache_lock:
        cached = _cache.get(cache_key)
        if cached is not None:
            return cached
        model = _actually_load(
            ordered_langs, resolver, min_log_prob, tw_min_log_prob,
            cj_min_log_prob, skip_topwords)
        _cache[cache_key] = model
        return model


def _actually_load(ordered_langs, resolver, min_log_prob, tw_min_log_prob,
                   cj_min_log_prob, skip_topwords):
    from langidentify.model import Model

    start_time = time.time()
    skip_ngrams = _compute_skip_ngrams(ordered_langs)

    load_min_log_prob = -float("inf") if min_log_prob == 0 else min_log_prob
    load_tw_min_log_prob = (
        0.0 if skip_topwords
        else (-float("inf") if tw_min_log_prob == 0 else tw_min_log_prob))

    # Resolve ngram file names.
    ngram_names = [None] * len(ordered_langs)
    for li, lang in enumerate(ordered_langs):
        if skip_ngrams[li]:
            continue
        iso = lang.iso_code
        gz_name = f"ngrams-{iso}.txt.gz"
        txt_name = f"ngrams-{iso}.txt"
        if resolver.exists(gz_name):
            ngram_names[li] = gz_name
        elif resolver.exists(txt_name):
            ngram_names[li] = txt_name
        else:
            raise FileNotFoundError(
                f"Ngram file not found for {iso}: tried {gz_name} and {txt_name}")

    # Resolve topword file names.
    topword_names = [None] * len(ordered_langs)
    if not skip_topwords:
        for li, lang in enumerate(ordered_langs):
            if skip_ngrams[li]:
                continue
            iso = lang.iso_code
            gz_name = f"topwords-{iso}.txt.gz"
            txt_name = f"topwords-{iso}.txt"
            if resolver.exists(gz_name):
                topword_names[li] = gz_name
            elif resolver.exists(txt_name):
                topword_names[li] = txt_name

    # --- Load ngrams ---
    load_max_ngram = 5
    builders = [_TableBuilder() for _ in range(load_max_ngram)]
    infos = [_LoadNgramInfo(load_min_log_prob) for _ in range(len(ordered_langs))]
    for li in range(len(ordered_langs)):
        if ngram_names[li] is not None:
            _load_language_ngrams(
                resolver, ngram_names[li], li, builders, infos[li])

    eff_max_ngram = max((info.seen_max_ngram for info in infos), default=load_max_ngram)
    if eff_max_ngram == 0:
        eff_max_ngram = load_max_ngram

    # Compute global compact floor.
    compact_floor = load_min_log_prob
    for info in infos:
        if info.seen_min_log_prob is not None:
            compact_floor = max(compact_floor, info.seen_min_log_prob)
    if compact_floor == -float("inf"):
        compact_floor = 0.0

    tables = []
    for n in range(1, eff_max_ngram + 1):
        tables.append(builders[n - 1].compact(compact_floor))
        builders[n - 1] = None  # free builder memory immediately

    # --- Load topwords ---
    tw_table = EMPTY
    tw_counts = [0] * len(ordered_langs)
    tw_file_min_log_probs = [None] * len(ordered_langs)
    tw_langs_loaded = 0
    if not skip_topwords:
        tw_builder = _TableBuilder()
        sw_name = "skipwords.txt"
        if resolver.exists(sw_name):
            _load_skip_words(resolver, sw_name, tw_builder)

        for li in range(len(ordered_langs)):
            if topword_names[li] is not None:
                tw_counts[li] = _load_top_words(
                    resolver, topword_names[li], li, tw_builder,
                    load_tw_min_log_prob, tw_file_min_log_probs)
                tw_langs_loaded += 1

        tw_compact_floor = load_tw_min_log_prob
        for fml in tw_file_min_log_probs:
            if fml is not None:
                tw_compact_floor = max(tw_compact_floor, fml)
        if tw_compact_floor == -float("inf"):
            tw_compact_floor = 0.0

        if tw_langs_loaded > 0:
            tw_table = tw_builder.compact(tw_compact_floor)
        tw_builder = None  # free builder memory immediately

    # --- Load CJ classifier ---
    temp_lang_set = set(ordered_langs)
    has_ja = Language.JAPANESE in temp_lang_set
    has_zh_hans = Language.CHINESE_SIMPLIFIED in temp_lang_set
    has_zh_hant = Language.CHINESE_TRADITIONAL in temp_lang_set
    needs_cj = (has_ja and (has_zh_hans or has_zh_hant)) or (has_zh_hans and has_zh_hant)
    cj_classifier = None
    if needs_cj:
        try:
            from cjclassifier import CJClassifier
            cj_classifier = CJClassifier.load(log_prob_floor=cj_min_log_prob)
        except ImportError:
            logger.warning(
                "cjclassifier package not installed; CJ disambiguation disabled")

    # Derive effective values.
    effective_min_log_prob = load_min_log_prob
    for info in infos:
        if info.seen_min_log_prob is not None:
            effective_min_log_prob = max(effective_min_log_prob, info.seen_min_log_prob)
    if effective_min_log_prob == -float("inf"):
        effective_min_log_prob = 0.0

    effective_tw_min_log_prob = load_tw_min_log_prob
    for fml in tw_file_min_log_probs:
        if fml is not None:
            effective_tw_min_log_prob = max(effective_tw_min_log_prob, fml)
    if effective_tw_min_log_prob == -float("inf"):
        effective_tw_min_log_prob = 0.0

    elapsed = time.time() - start_time
    logger.info(
        "ModelLoader: loaded %d languages, %d ngram tables, "
        "topwords=%d langs, cj=%s in %.1fs",
        len(ordered_langs), len(tables), tw_langs_loaded,
        "yes" if cj_classifier else "no", elapsed)

    return Model(
        tables=tables,
        languages=ordered_langs,
        min_log_prob=effective_min_log_prob,
        tw_min_log_prob=effective_tw_min_log_prob,
        max_ngram=len(tables),
        cj_classifier=cj_classifier,
        topwords_table=tw_table)


# ========================================================================
# Helpers
# ========================================================================

class _LoadNgramInfo:
    def __init__(self, wanted_min_log_prob):
        self.wanted_min_log_prob = wanted_min_log_prob
        self.count = 0
        self.seen_min_log_prob = None
        self.seen_max_ngram = 0


def _compute_skip_ngrams(ordered_langs):
    """Languages with unique alphabets can skip ngram loading."""
    alpha_lang_count = {}
    for lang in ordered_langs:
        for alpha in lang.alphabets:
            alpha_lang_count[alpha] = alpha_lang_count.get(alpha, 0) + 1
    skip = []
    cj_set = {Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL,
              Language.JAPANESE}
    for lang in ordered_langs:
        all_unique = all(
            alpha_lang_count.get(a, 0) <= 1 for a in lang.alphabets)
        skip.append(all_unique or lang in cj_set)
    return skip


def _load_language_ngrams(resolver, name, lang_idx, builders, info):
    min_log_prob = info.wanted_min_log_prob
    totals = [0] * 6  # index 1..5

    with resolver.open(name) as f:
        header = f.readline().strip()
        if header.startswith("# "):
            header = header[2:]

        parts = header.split()
        i = 0
        while i < len(parts):
            part = parts[i]
            if part == "MinLogProb:" and i + 1 < len(parts):
                parsed = float(parts[i + 1])
                info.seen_min_log_prob = parsed
                if parsed > min_log_prob:
                    min_log_prob = parsed
                i += 2
                continue
            colon_idx = part.find(":")
            if colon_idx > 0:
                try:
                    n = int(part[:colon_idx])
                    value = part[colon_idx + 1:]
                    slash_idx = value.find("/")
                    if slash_idx >= 0 and 1 <= n <= 5:
                        totals[n] = int(value[slash_idx + 1:])
                        if n > info.seen_max_ngram:
                            info.seen_max_ngram = n
                except ValueError:
                    pass
            i += 1

        if info.seen_min_log_prob is None:
            raise ValueError(f"Missing MinLogProb in ngram file header: {name}")

        entry_count = 0
        for line in f:
            line = line.strip()
            if not line or line[0] == "#":
                continue
            space_idx = line.rfind(" ")
            if space_idx <= 0:
                continue

            ngram = line[:space_idx]
            value_str = line[space_idx + 1:]
            n = len(ngram)
            if n < 1 or n > 5 or totals[n] == 0:
                continue

            if value_str[0] == "-":
                log_prob = float(value_str)
            else:
                count = int(value_str)
                log_prob = math.log(count / totals[n])

            if log_prob < min_log_prob:
                continue

            builder = builders[n - 1]
            existing = builder.get(ngram)
            if existing is not None:
                existing.add(lang_idx, log_prob)
            else:
                lp_builder = _LangProbListBuilder()
                lp_builder.add(lang_idx, log_prob)
                builder.put(ngram, lp_builder)

            entry_count += 1

    info.count = entry_count


def _load_skip_words(resolver, name, builder):
    with resolver.open(name) as f:
        for line in f:
            line = line.strip()
            if not line or line[0] == "#":
                continue
            existing = builder.get(line)
            if existing is None:
                lp_builder = _LangProbListBuilder()
                lp_builder.poison()
                builder.put(line, lp_builder)
            else:
                existing.poison()


def _load_top_words(resolver, name, lang_idx, builder, topwords_min_log_prob,
                    file_min_tw_log_probs):
    entry_count = 0

    with resolver.open(name) as f:
        header = f.readline().strip()
        if not header.startswith("# Count: "):
            raise ValueError(
                f"Expected '# Count: NNN' header in topwords file: {name}")

        after_count = header[len("# Count: "):].strip()
        sp_idx = after_count.find(" ")
        total = int(after_count[:sp_idx] if sp_idx >= 0 else after_count)
        if total <= 0:
            raise ValueError(f"Invalid total count in topwords file: {name}")

        if sp_idx < 0 or "MinLogProb:" not in after_count[sp_idx + 1:]:
            raise ValueError(
                f"Missing MinLogProb in topwords file header: {name}")

        mlp_str = after_count[sp_idx + 1:].strip()
        file_min_tw = float(mlp_str[len("MinLogProb:"):].strip())
        file_min_tw_log_probs[lang_idx] = file_min_tw
        if file_min_tw > topwords_min_log_prob:
            topwords_min_log_prob = file_min_tw

        for line in f:
            line = line.strip()
            if not line or line[0] == "#":
                continue
            space_idx = line.rfind(" ")
            if space_idx <= 0:
                continue

            word = line[:space_idx]
            # Skip single-character ASCII words
            if len(word) == 1 and ord(word) <= 0x7F:
                continue

            value_str = line[space_idx + 1:]
            if value_str[0] == "-":
                log_prob = float(value_str)
            else:
                count = int(value_str)
                log_prob = math.log(count / total)

            if log_prob < topwords_min_log_prob:
                break  # sorted by descending count

            existing = builder.get(word)
            if existing is not None:
                existing.add(lang_idx, log_prob)
            else:
                lp_builder = _LangProbListBuilder()
                lp_builder.add(lang_idx, log_prob)
                builder.put(word, lp_builder)

            entry_count += 1

    return entry_count
