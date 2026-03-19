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

"""Tests for langidentify.alphabet."""

import pytest

from langidentify.alphabet import Alphabet, get_alphabet


class TestWeight:
    """Tests for Alphabet.weight()."""

    def test_han_weight(self):
        assert Alphabet.HAN.weight() == 3.0

    def test_hangul_weight(self):
        assert Alphabet.HANGUL.weight() == 2.0

    def test_ja_kana_weight(self):
        assert Alphabet.JA_KANA.weight() == 2.0

    def test_arabic_weight(self):
        assert Alphabet.ARABIC.weight() == 1.25

    @pytest.mark.parametrize("alpha", [
        Alphabet.LATIN, Alphabet.CYRILLIC, Alphabet.GREEK, Alphabet.ARMENIAN,
        Alphabet.HEBREW, Alphabet.BENGALI, Alphabet.DEVANAGARI,
        Alphabet.ETHIOPIC, Alphabet.GEORGIAN, Alphabet.GUJARATI,
        Alphabet.GURMUKHI, Alphabet.KANNADA, Alphabet.KHMER, Alphabet.LAO,
        Alphabet.MALAYALAM, Alphabet.MYANMAR, Alphabet.SINHALA,
        Alphabet.TAMIL, Alphabet.TELUGU, Alphabet.THAI, Alphabet.UNKNOWN,
    ])
    def test_default_weight(self, alpha):
        assert alpha.weight() == 1.0


class TestGetAlphabet:
    """Tests for get_alphabet()."""

    def test_ascii_latin(self):
        assert get_alphabet('a') == Alphabet.LATIN
        assert get_alphabet('Z') == Alphabet.LATIN

    def test_ascii_non_alpha(self):
        assert get_alphabet('5') == Alphabet.UNKNOWN
        assert get_alphabet(' ') == Alphabet.UNKNOWN

    @pytest.mark.parametrize("ch,expected", [
        ('\u00e9', Alphabet.LATIN),       # é
        ('\u0100', Alphabet.LATIN),       # Ā - Latin Extended-A
        ('\u1E00', Alphabet.LATIN),       # Latin Extended Additional
        ('\u2C60', Alphabet.LATIN),       # Latin Extended-C
        ('\uFF21', Alphabet.LATIN),       # Fullwidth A
        ('\u0410', Alphabet.CYRILLIC),    # А
        ('\u0370', Alphabet.GREEK),       # Greek
        ('\u0530', Alphabet.ARMENIAN),    # Armenian
        ('\u05D0', Alphabet.HEBREW),      # א
        ('\u0627', Alphabet.ARABIC),      # ا
        ('\u0900', Alphabet.DEVANAGARI),  # Devanagari
        ('\u0980', Alphabet.BENGALI),     # Bengali
        ('\u0A00', Alphabet.GURMUKHI),    # Gurmukhi
        ('\u0A80', Alphabet.GUJARATI),    # Gujarati
        ('\u0B80', Alphabet.TAMIL),       # Tamil
        ('\u0C00', Alphabet.TELUGU),      # Telugu
        ('\u0C80', Alphabet.KANNADA),     # Kannada
        ('\u0D00', Alphabet.MALAYALAM),   # Malayalam
        ('\u0D80', Alphabet.SINHALA),     # Sinhala
        ('\u0E01', Alphabet.THAI),        # Thai
        ('\u0E81', Alphabet.LAO),         # Lao
        ('\u1000', Alphabet.MYANMAR),     # Myanmar
        ('\u10A0', Alphabet.GEORGIAN),    # Georgian
        ('\u1100', Alphabet.HANGUL),      # Hangul Jamo
        ('\u1200', Alphabet.ETHIOPIC),    # Ethiopic
        ('\u1780', Alphabet.KHMER),       # Khmer
        ('\u4E00', Alphabet.HAN),         # CJK Unified
        ('\u3041', Alphabet.JA_KANA),     # Hiragana
        ('\u30A1', Alphabet.JA_KANA),     # Katakana
        ('\uAC00', Alphabet.HANGUL),      # Hangul Syllable
        ('\uFF66', Alphabet.JA_KANA),     # Halfwidth Katakana
    ])
    def test_non_ascii(self, ch, expected):
        assert get_alphabet(ch) == expected
