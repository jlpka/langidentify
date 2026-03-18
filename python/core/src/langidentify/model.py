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

"""Immutable model containing ngram tables for a set of languages."""

from langidentify.alphabet import Alphabet
from langidentify.language import Language
from langidentify import model_loader


class Model:
    """Immutable model containing ngram tables for a set of languages.

    Usage::

        model = Model.load(Language.from_comma_separated("en,fr,de"))
        detector = Detector(model)
        lang = detector.detect("Bonjour le monde")
    """

    def __init__(self, tables, languages, min_log_prob, tw_min_log_prob,
                 max_ngram, cj_classifier, topwords_table):
        self._tables = tables
        self._languages = list(languages)
        self._min_log_prob = min_log_prob
        self._tw_min_log_prob = tw_min_log_prob
        self._max_ngram = max_ngram
        self._cj_classifier = cj_classifier
        self._topwords_table = topwords_table

        # Build language index map.
        self._lang_index_map = {}
        for i, lang in enumerate(self._languages):
            self._lang_index_map[lang] = i

        # Collect union of alphabets from all languages, in enum order.
        alpha_set = set()
        for lang in self._languages:
            alpha_set.update(lang.alphabets)
        self._alphabets = sorted(alpha_set, key=lambda a: list(Alphabet).index(a))
        self._alphabet_index_map = {a: i for i, a in enumerate(self._alphabets)}

        # Build alphabet -> language index mapping.
        self._alphabet_to_lang_indices = []
        self._alphabet_implies_one_language = []
        self._cj_alphabet = []
        for ai, alpha in enumerate(self._alphabets):
            lang_indices = [
                li for li, lang in enumerate(self._languages)
                if alpha in lang.alphabets]
            self._alphabet_to_lang_indices.append(lang_indices)
            self._alphabet_implies_one_language.append(len(lang_indices) == 1)
            self._cj_alphabet.append(
                alpha == Alphabet.HAN or alpha == Alphabet.JA_KANA)

    # ========================================================================
    # Factory methods
    # ========================================================================

    @staticmethod
    def load_from_path(prefix, languages, min_log_prob=0, tw_min_log_prob=0,
                       cj_min_log_prob=0):
        """Loads (or returns cached) a Model from a filesystem path."""
        return model_loader.load_from_path(
            prefix, languages, min_log_prob, tw_min_log_prob, cj_min_log_prob)

    @staticmethod
    def load(languages, min_log_prob=0, tw_min_log_prob=0, cj_min_log_prob=0):
        """Loads a Model from the package data, auto-discovering the variant."""
        return model_loader.load(
            languages, min_log_prob, tw_min_log_prob, cj_min_log_prob)

    @staticmethod
    def load_lite(languages):
        """Loads a Model forcing the lite variant."""
        return model_loader.load_lite(languages)

    @staticmethod
    def load_full(languages):
        """Loads a Model forcing the full variant."""
        return model_loader.load_full(languages)

    @staticmethod
    def load_variant(variant, languages, min_log_prob=0, tw_min_log_prob=0,
                     cj_min_log_prob=0):
        """Loads a Model from the package for a named variant."""
        return model_loader.load_variant(
            variant, languages, min_log_prob, tw_min_log_prob, cj_min_log_prob)

    @staticmethod
    def clear_cache():
        """Clears the model cache."""
        model_loader.clear_cache()

    # ========================================================================
    # Accessors
    # ========================================================================

    def get_table(self, ngram_size):
        """Returns the ngram dict for the given ngram size (1..max_ngram)."""
        return self._tables[ngram_size - 1]

    @property
    def languages(self):
        """Returns the ordered list of languages in this model."""
        return self._languages

    @property
    def num_languages(self):
        return len(self._languages)

    def lang_index(self, lang):
        """Maps a language to its column index, or -1 if not in the model."""
        return self._lang_index_map.get(lang, -1)

    @property
    def min_log_prob(self):
        return self._min_log_prob

    @property
    def tw_min_log_prob(self):
        return self._tw_min_log_prob

    @property
    def max_ngram(self):
        return self._max_ngram

    @property
    def alphabets(self):
        """Returns the ordered list of active alphabets in this model."""
        return self._alphabets

    @property
    def num_alphabets(self):
        return len(self._alphabets)

    def alphabet_index(self, alpha):
        """Maps an alphabet to its local index, or -1 if not in the model."""
        return self._alphabet_index_map.get(alpha, -1)

    def lang_indices_for_alphabet(self, alpha_idx):
        """Returns the language indices associated with a given alphabet index."""
        return self._alphabet_to_lang_indices[alpha_idx]

    def alphabet_implies_one_language(self, alpha_idx):
        """Returns True if this alphabet index maps to exactly one language."""
        return self._alphabet_implies_one_language[alpha_idx]

    def unique_language_for_alphabet(self, alpha_idx):
        """For a unique alphabet, returns the single language it maps to."""
        return self._languages[self._alphabet_to_lang_indices[alpha_idx][0]]

    def is_cj_alphabet(self, alpha_idx):
        """Returns True if the alphabet at this index is HAN or JA_KANA."""
        return self._cj_alphabet[alpha_idx]

    @property
    def cj_classifier(self):
        """Returns the CJClassifier for HAN disambiguation, or None."""
        return self._cj_classifier

    @property
    def topwords_table(self):
        """Returns the topwords table."""
        return self._topwords_table

    def build_boost_array(self, boost_map=None, **kwargs):
        """Builds a boost array for the Detector.

        Args:
            boost_map: dict mapping Language -> float boost value.
            **kwargs: Alternative: pass lang=boost pairs as keyword args.

        Returns:
            list of float, one per language in the model.
        """
        boosts = [0.0] * len(self._languages)
        if boost_map:
            for lang, boost in boost_map.items():
                li = self.lang_index(lang)
                if li >= 0:
                    boosts[li] = boost
        return boosts
