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

"""Detects the language of text using n-gram and topword scoring.

Detector is inexpensive to construct and intentionally not thread-safe. For
concurrent detection, use a separate instance per thread.
"""

from langidentify.alphabet import Alphabet
from langidentify.language import Language
from langidentify.model_loader import SKIP
from langidentify.word_segmenter import WordSegmenter


class Scores:
    """Accumulates per-language scoring data during word-level lookups."""

    def __init__(self, model):
        n = model.num_languages
        self.ngram_scores = [0.0] * n
        self.ngram_hits_per_lang = [0] * n
        self.tw_scores = [0.0] * n
        self.tw_hits_per_lang = [0] * n
        self.tw_num_lookups = 0
        self.alphabet_counts = [0] * model.num_alphabets
        self.num_words = 0
        self._num_langs = n
        self._num_alphas = model.num_alphabets

        self.cj_scores = None
        if model.cj_classifier is not None:
            from cjclassifier.classifier import Scores as CJScores
            self.cj_scores = CJScores()

    def clear(self):
        n = self._num_langs
        self.ngram_scores = [0.0] * n
        self.ngram_hits_per_lang = [0] * n
        self.tw_scores = [0.0] * n
        self.tw_hits_per_lang = [0] * n
        self.tw_num_lookups = 0
        self.alphabet_counts = [0] * self._num_alphas
        self.num_words = 0
        if self.cj_scores is not None:
            self.cj_scores.clear()

    def num_chars(self):
        return sum(self.alphabet_counts)


class Results:
    """Detection results, including accumulated Scores and the computed result."""

    def __init__(self, model):
        self._model = model
        self.scores = Scores(model)
        self.total_scores = [0.0] * model.num_languages
        self.result = None
        self.gap = 0.0
        self.predominant_alpha_idx = -1

        self.cj_results = None
        if self.scores.cj_scores is not None:
            from cjclassifier.classifier import Results as CJResults
            self.cj_results = CJResults(self.scores.cj_scores)

    def compute_result(self, boosts=None):
        """Computes final normalized scores and determines the winning language."""
        model = self._model
        num_langs = len(self.scores.ngram_scores)
        scores = self.scores

        # Determine predominant alphabet by weighted character count.
        self.predominant_alpha_idx = -1
        max_weighted_count = 0.0
        alphas = model.alphabets
        cj_unified_idx = model.alphabet_index(Alphabet.HAN)
        ja_kana_idx = model.alphabet_index(Alphabet.JA_KANA)
        cj_group_weight = 0.0

        for ai in range(len(scores.alphabet_counts)):
            if scores.alphabet_counts[ai] > 0:
                wc = scores.alphabet_counts[ai] * alphas[ai].weight()
                if (self.cj_results is not None
                        and (ai == cj_unified_idx or ai == ja_kana_idx)):
                    cj_group_weight += wc
                else:
                    if wc > max_weighted_count:
                        max_weighted_count = wc
                        self.predominant_alpha_idx = ai

        # Check if CJ group beats all other alphabets.
        if cj_group_weight > max_weighted_count and self._compute_result_for_cj(boosts):
            return

        # No alphabet chars at all.
        if self.predominant_alpha_idx < 0:
            self.total_scores = [0.0] * num_langs
            self.result = Language.UNKNOWN
            return

        # Unique alphabet → return directly.
        if model.alphabet_implies_one_language(self.predominant_alpha_idx):
            li = model.lang_indices_for_alphabet(self.predominant_alpha_idx)[0]
            self.total_scores = [0.0] * num_langs
            self.total_scores[li] = 1.0
            self.result = model.unique_language_for_alphabet(self.predominant_alpha_idx)
            self.gap = 1.0
            return

        # Normalize ngram scores.
        total_scores = self._normalized_ngram_scores()

        # Maybe blend in topwords scores.
        tw_scratch, tw_factor = self._normalized_tw_scores()
        if tw_factor > 0.0:
            ngram_weight = 1.0 - tw_factor
            for li in range(num_langs):
                total_scores[li] = (
                    total_scores[li] * ngram_weight + tw_scratch[li] * tw_factor)

        # Apply boosts.
        if boosts is not None:
            for li in range(num_langs):
                if boosts[li] != 0.0:
                    total_scores[li] += boosts[li] * abs(total_scores[li])

        self.total_scores = total_scores

        # Restrict to languages using the predominant alphabet.
        candidates = model.lang_indices_for_alphabet(self.predominant_alpha_idx)
        best_idx = candidates[0]
        second_idx = -1
        for k in range(1, len(candidates)):
            li = candidates[k]
            if total_scores[li] > total_scores[best_idx]:
                second_idx = best_idx
                best_idx = li
            elif second_idx < 0 or total_scores[li] > total_scores[second_idx]:
                second_idx = li

        if total_scores[best_idx] != 0.0:
            self.result = model.languages[best_idx]
            second = (total_scores[second_idx]
                      if second_idx >= 0 else total_scores[best_idx])
            self.gap = (1.0 - total_scores[best_idx] / second) if second != 0.0 else 0.0
        else:
            self.result = Language.UNKNOWN

    def _compute_result_for_cj(self, boosts):
        model = self._model
        ja_kana_idx = model.alphabet_index(Alphabet.JA_KANA)
        cj_unified_idx = model.alphabet_index(Alphabet.HAN)
        kana_chars = (self.scores.alphabet_counts[ja_kana_idx]
                      if ja_kana_idx >= 0 else 0)
        han_chars = (self.scores.alphabet_counts[cj_unified_idx]
                     if cj_unified_idx >= 0 else 0)
        total_cj = kana_chars + han_chars
        cjc = model.cj_classifier

        if total_cj > 0 and kana_chars > 0 and cjc is not None:
            kana_ratio = kana_chars / total_cj
            if kana_ratio > cjc.tolerated_kana_threshold:
                self.predominant_alpha_idx = ja_kana_idx
                self.result = Language.JAPANESE
                self.gap = 1.0
                self.total_scores = [0.0] * model.num_languages
                ja_li = model.lang_index(Language.JAPANESE)
                if ja_li >= 0:
                    self.total_scores[ja_li] = 1.0
                return True

        if self.cj_results is not None:
            self._map_cj_boosts(boosts)
            from cjclassifier import CJLanguage
            cj_result = cjc.compute_result(self.cj_results)
            if cj_result != CJLanguage.UNKNOWN:
                self.predominant_alpha_idx = cj_unified_idx
                self.result = _convert_cj_language(cj_result)
                self.gap = self.cj_results.gap
                self.total_scores = [0.0] * model.num_languages
                for ci, cj_lang in enumerate(_CJ_LANGUAGES):
                    li = model.lang_index(cj_lang)
                    if li >= 0:
                        self.total_scores[li] = self.cj_results.total_scores[ci]
                return True

        self.predominant_alpha_idx = cj_unified_idx
        return False

    def _map_cj_boosts(self, detector_boosts):
        model = self._model
        for ci in range(len(self.cj_results.boosts)):
            self.cj_results.boosts[ci] = 0.0
        if detector_boosts is not None:
            for ci, cj_lang in enumerate(_CJ_LANGUAGES):
                li = model.lang_index(cj_lang)
                if li >= 0:
                    self.cj_results.boosts[ci] = detector_boosts[li]

    def _normalized_ngram_scores(self):
        scores = self.scores
        n = len(scores.ngram_scores)
        out = list(scores.ngram_scores)
        max_hits = max(scores.ngram_hits_per_lang) if n > 0 else 0
        factor = 1.0 / max_hits if max_hits > 0 else 1.0
        min_log_prob = self._model.min_log_prob
        for li in range(n):
            out[li] += (max_hits - scores.ngram_hits_per_lang[li]) * min_log_prob
            out[li] *= factor
        return out

    def _normalized_tw_scores(self):
        scores = self.scores
        n = len(scores.tw_scores)
        if scores.tw_num_lookups == 0:
            return [0.0] * n, 0.0

        out = list(scores.tw_scores)
        tw_max_hits = max(scores.tw_hits_per_lang) if n > 0 else 0
        if tw_max_hits == 0:
            return out, 0.0

        tw_min_log_prob = self._model.tw_min_log_prob
        factor = 1.0 / tw_max_hits
        for li in range(n):
            out[li] += (tw_max_hits - scores.tw_hits_per_lang[li]) * tw_min_log_prob
            out[li] *= factor

        ratio = tw_max_hits / scores.tw_num_lookups
        if ratio < 0.5:
            tw_factor = 0.5 * tw_max_hits / scores.tw_num_lookups
        else:
            tw_factor = 0.8 * tw_max_hits / scores.tw_num_lookups
        return out, tw_factor

    def __repr__(self):
        iso = self.result.iso_code if self.result else "None"
        return f"Results(result={iso}, gap={self.gap:.4f})"


def _convert_cj_language(cj_lang):
    from cjclassifier import CJLanguage
    if cj_lang == CJLanguage.CHINESE_SIMPLIFIED:
        return Language.CHINESE_SIMPLIFIED
    if cj_lang == CJLanguage.CHINESE_TRADITIONAL:
        return Language.CHINESE_TRADITIONAL
    if cj_lang == CJLanguage.JAPANESE:
        return Language.JAPANESE
    return Language.UNKNOWN


# CJ language list, matching CJClassifier.CJ_LANGUAGES_LIST order.
_CJ_LANGUAGES = [
    Language.CHINESE_SIMPLIFIED,
    Language.CHINESE_TRADITIONAL,
    Language.JAPANESE,
]


class Detector:
    """Detects the language of text using n-gram and topword scoring.

    Detector is inexpensive to construct and intentionally not thread-safe.
    For concurrent detection, use a separate instance per thread.

    Example::

        languages = Language.from_comma_separated("en,fr,de")
        model = Model.load(languages)
        detector = Detector(model)
        lang = detector.detect("Bonjour le monde")
    """

    def __init__(self, model):
        self._model = model
        self._min_ngram = 1
        self._stop_if_ngram_covered = min(3, model.max_ngram)
        self._max_ngram = model.max_ngram
        self._use_topwords = True
        self._results = Results(model)
        self._segmenter = self._make_word_segmenter()

    def set_accuracy_params(self, min_ngram=1, stop_if_ngram_covered=3,
                            max_ngram=None, use_topwords=True):
        """Adjusts the n-gram evaluation range and topword usage.

        Returns True if the parameters are valid and were applied.
        """
        if max_ngram is None:
            max_ngram = self._model.max_ngram
        if (min_ngram < 1
                or max_ngram > self._model.max_ngram
                or min_ngram > max_ngram
                or stop_if_ngram_covered < min_ngram
                or stop_if_ngram_covered > max_ngram):
            return False
        self._min_ngram = min_ngram
        self._stop_if_ngram_covered = stop_if_ngram_covered
        self._max_ngram = max_ngram
        self._use_topwords = use_topwords
        return True

    def detect(self, text, boosts=None):
        """Detects the language of the given text.

        Args:
            text: The text to detect.
            boosts: Optional per-language boost array (from Model.build_boost_array).

        Returns:
            The detected Language, or Language.UNKNOWN.
        """
        self._results.scores.clear()
        self._segmenter.segment(text)
        self._results.compute_result(boosts)
        return self._results.result

    def clear_scores(self):
        """Clears scores for use with the add_text/compute_result API."""
        self._results.scores.clear()

    def add_text(self, text):
        """Adds text to the current detection accumulator."""
        self._segmenter.segment(text)

    def compute_result(self, boosts=None):
        """Computes the result from all text added since the last clear_scores."""
        self._results.compute_result(boosts)
        return self._results.result

    @property
    def model(self):
        return self._model

    @property
    def results(self):
        """Returns the detection Results, populated after detect() or compute_result()."""
        return self._results

    def _make_word_segmenter(self):
        model = self._model
        cjc = model.cj_classifier

        def word_consumer(word, alpha_idx):
            if (model.alphabet_implies_one_language(alpha_idx)
                    or self._score_word(word)):
                self._results.scores.alphabet_counts[alpha_idx] += len(word)
                self._results.scores.num_words += 1

        cj_consumer = None
        if cjc is not None:
            def cj_consumer(word, alpha_idx):
                cjc.add_text(word, self._results.scores.cj_scores)
                self._results.scores.alphabet_counts[alpha_idx] += len(word)
                self._results.scores.num_words += 1

        return WordSegmenter(model, word_consumer, cj_consumer)

    def _score_word(self, word):
        """Scores ngram and topwords within a word."""
        if self._use_topwords and not self._score_topwords(word):
            return False  # skipword

        scores = self._results.scores
        word_len = len(word)

        for n in range(min(self._max_ngram, word_len), self._min_ngram - 1, -1):
            table = self._model.get_table(n)
            is_fully_covered = True
            for start in range(word_len - n + 1):
                entry = table.get(word[start:start + n])
                if entry is not None:
                    lang_indices, probs = entry
                    for j in range(len(lang_indices)):
                        li = lang_indices[j]
                        scores.ngram_scores[li] += probs[j]
                        scores.ngram_hits_per_lang[li] += 1
                else:
                    is_fully_covered = False
            if is_fully_covered and n <= self._stop_if_ngram_covered:
                break

        return True

    def _score_topwords(self, word):
        """Scores topwords/skipwords for a word. Returns False if skipword."""
        word_len = len(word)
        if word_len <= 1 and (not word or ord(word[0]) < 0x80):
            return True

        scores = self._results.scores
        tw_table = self._model.topwords_table
        scores.tw_num_lookups += 1
        entry = tw_table.get(word)

        if entry is SKIP:
            # Skipword — back out the lookup count.
            scores.tw_num_lookups -= 1
            return False
        elif entry is not None:
            lang_indices, probs = entry
            for j in range(len(lang_indices)):
                li = lang_indices[j]
                scores.tw_scores[li] += probs[j]
                scores.tw_hits_per_lang[li] += 1
        elif word_len > 2:
            apos = _mid_word_apostrophe(word)
            if apos > 0:
                self._score_apostrophe_topwords(word, apos)

        return True

    def _score_apostrophe_topwords(self, word, apos):
        """Handles apostrophe splitting for topwords (e.g. l'homme -> l' + homme)."""
        scores = self._results.scores
        tw_table = self._model.topwords_table

        # Look up prefix including apostrophe.
        prefix = word[:apos + 1]
        entry = tw_table.get(prefix)
        if entry is not None and entry is not SKIP:
            lang_indices, probs = entry
            for j in range(len(lang_indices)):
                li = lang_indices[j]
                scores.tw_scores[li] += probs[j]
                scores.tw_hits_per_lang[li] += 1

        # Look up suffix after apostrophe.
        suffix = word[apos + 1:]
        if len(suffix) > 1:
            scores.tw_num_lookups += 1
            entry = tw_table.get(suffix)
            if entry is not None and entry is not SKIP:
                lang_indices, probs = entry
                for j in range(len(lang_indices)):
                    li = lang_indices[j]
                    scores.tw_scores[li] += probs[j]
                    scores.tw_hits_per_lang[li] += 1


def _mid_word_apostrophe(word):
    """Returns the position of the first mid-word apostrophe, or -1."""
    for i in range(1, len(word) - 1):
        if word[i] == "'":
            return i
    return -1
