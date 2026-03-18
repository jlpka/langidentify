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

"""Segments text into words by alphabet, handling lowercasing, apostrophe
splitting, digit filtering, and CJ run detection."""

from langidentify.alphabet import Alphabet, get_alphabet

MAX_WORD_LEN = 64
MAX_CJ_WORD_LEN = 256

_HAS_DIGIT = 1
_HAS_APOSTROPHE = 2

# Apostrophe characters: ASCII ', right single quotation mark, Windows-1252 mis-decode
_APOSTROPHES = frozenset(["'", "\u2019", "\u0092"])


class WordSegmenter:
    """Segments text into words by alphabet boundaries.

    Calls word_consumer(word, alpha_idx) for each emitted word, and
    cj_consumer(word, alpha_idx) for CJ runs.
    """

    def __init__(self, model, word_consumer, cj_consumer=None):
        self._model = model
        self._word_consumer = word_consumer
        self._cj_consumer = cj_consumer

    def segment(self, text):
        """Segments a string into words."""
        word_buf = []
        word_start = -1
        word_alpha_idx = -1
        special_cases = 0
        model = self._model

        for i, ch in enumerate(text):
            alpha = get_alphabet(ch)
            alpha_idx = model.alphabet_index(alpha)

            if alpha_idx >= 0:
                if alpha_idx != word_alpha_idx and word_start >= 0:
                    self._emit_word(
                        word_buf, word_alpha_idx, special_cases)
                    word_buf = []
                    special_cases = 0

                if len(word_buf) < MAX_WORD_LEN:
                    word_buf.append(ch.lower())
                elif model.is_cj_alphabet(word_alpha_idx):
                    if len(word_buf) == MAX_CJ_WORD_LEN:
                        self._emit_word(
                            word_buf, word_alpha_idx, special_cases)
                        word_buf = []
                        special_cases = 0
                    word_buf.append(ch)

                if word_start < 0:
                    word_start = i
                word_alpha_idx = alpha_idx

            elif "0" <= ch <= "9":
                special_cases |= _HAS_DIGIT

            elif (word_start >= 0
                  and ch in _APOSTROPHES
                  and word_buf
                  and word_buf[-1] != "'"):
                special_cases |= _HAS_APOSTROPHE
                if len(word_buf) < MAX_WORD_LEN:
                    word_buf.append("'")

            else:
                if word_start >= 0:
                    self._emit_word(
                        word_buf, word_alpha_idx, special_cases)
                    word_buf = []
                special_cases = 0
                word_start = -1
                word_alpha_idx = -1

        if word_start >= 0:
            self._emit_word(word_buf, word_alpha_idx, special_cases)

    def _emit_word(self, word_buf, alpha_idx, special_cases):
        if not word_buf:
            return

        model = self._model

        # Special-case filtering
        if special_cases != 0:
            if ((special_cases & _HAS_DIGIT)
                    and model.alphabets[alpha_idx] == Alphabet.LATIN):
                return
            if (special_cases & _HAS_APOSTROPHE) and word_buf[-1] == "'":
                word_buf.pop()
                if not word_buf:
                    return

        word = "".join(word_buf)

        if self._cj_consumer is not None and model.is_cj_alphabet(alpha_idx):
            self._cj_consumer(word, alpha_idx)
        else:
            self._word_consumer(word, alpha_idx)
