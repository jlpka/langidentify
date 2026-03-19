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

"""Writing systems used to classify characters during language detection."""

import bisect
from enum import Enum, auto


class Alphabet(Enum):
    UNKNOWN = auto()
    LATIN = auto()
    HAN = auto()
    JA_KANA = auto()
    HANGUL = auto()
    ARABIC = auto()
    ARMENIAN = auto()
    BENGALI = auto()
    CYRILLIC = auto()
    DEVANAGARI = auto()
    ETHIOPIC = auto()
    GEORGIAN = auto()
    GREEK = auto()
    GUJARATI = auto()
    GURMUKHI = auto()
    HEBREW = auto()
    KANNADA = auto()
    KHMER = auto()
    LAO = auto()
    MALAYALAM = auto()
    MYANMAR = auto()
    SINHALA = auto()
    TAMIL = auto()
    TELUGU = auto()
    THAI = auto()

    def weight(self):
        """Returns the detection weight for this alphabet.

        CJK characters carry more linguistic signal per character than Latin
        letters, so they are weighted higher when determining the predominant
        alphabet.
        """
        return _WEIGHTS.get(self, 1.0)

    @staticmethod
    def from_string(s):
        """Parses an alphabet name (case-insensitive). Returns UNKNOWN if unrecognized."""
        if s is None:
            return Alphabet.UNKNOWN
        s = s.upper()
        try:
            return Alphabet[s]
        except KeyError:
            return Alphabet.UNKNOWN


LATIN_SET = frozenset([Alphabet.LATIN])
CYRILLIC_SET = frozenset([Alphabet.CYRILLIC])
ARABIC_SET = frozenset([Alphabet.ARABIC])

_WEIGHTS = {
    Alphabet.HAN: 3.0,
    Alphabet.HANGUL: 2.0,
    Alphabet.JA_KANA: 2.0,
    Alphabet.ARABIC: 1.25,
}

# Precomputed set of ASCII letter code points for fast Latin detection.
_ASCII_LETTERS = set()
for _c in range(128):
    if chr(_c).isalpha():
        _ASCII_LETTERS.add(_c)
_ASCII_LETTERS = frozenset(_ASCII_LETTERS)


# Sorted table of (range_start, range_end, Alphabet) for binary search.
# Adjacent ranges for the same script are merged where possible.
_ALPHABET_RANGES = [
    # Latin Extended-A/B + supplement
    (0x00C0, 0x024F, Alphabet.LATIN),
    # Greek
    (0x0370, 0x03FF, Alphabet.GREEK),
    # Cyrillic + Supplement (merged 0400-04FF, 0500-052F)
    (0x0400, 0x052F, Alphabet.CYRILLIC),
    # Armenian
    (0x0530, 0x058F, Alphabet.ARMENIAN),
    # Hebrew
    (0x0590, 0x05FF, Alphabet.HEBREW),
    # Arabic
    (0x0600, 0x06FF, Alphabet.ARABIC),
    # Arabic Supplement
    (0x0750, 0x077F, Alphabet.ARABIC),
    # Arabic Extended-A
    (0x08A0, 0x08FF, Alphabet.ARABIC),
    # Devanagari
    (0x0900, 0x097F, Alphabet.DEVANAGARI),
    # Bengali
    (0x0980, 0x09FF, Alphabet.BENGALI),
    # Gurmukhi
    (0x0A00, 0x0A7F, Alphabet.GURMUKHI),
    # Gujarati
    (0x0A80, 0x0AFF, Alphabet.GUJARATI),
    # Tamil
    (0x0B80, 0x0BFF, Alphabet.TAMIL),
    # Telugu
    (0x0C00, 0x0C7F, Alphabet.TELUGU),
    # Kannada
    (0x0C80, 0x0CFF, Alphabet.KANNADA),
    # Malayalam
    (0x0D00, 0x0D7F, Alphabet.MALAYALAM),
    # Sinhala
    (0x0D80, 0x0DFF, Alphabet.SINHALA),
    # Thai
    (0x0E00, 0x0E7F, Alphabet.THAI),
    # Lao
    (0x0E80, 0x0EFF, Alphabet.LAO),
    # Myanmar
    (0x1000, 0x109F, Alphabet.MYANMAR),
    # Georgian
    (0x10A0, 0x10FF, Alphabet.GEORGIAN),
    # Hangul Jamo
    (0x1100, 0x11FF, Alphabet.HANGUL),
    # Ethiopic (merged 1200-137F, 1380-139F)
    (0x1200, 0x139F, Alphabet.ETHIOPIC),
    # Khmer
    (0x1780, 0x17FF, Alphabet.KHMER),
    # Georgian Supplement
    (0x1C90, 0x1CBF, Alphabet.GEORGIAN),
    # Latin Extended Additional
    (0x1E00, 0x1EFF, Alphabet.LATIN),
    # Greek Extended
    (0x1F00, 0x1FFF, Alphabet.GREEK),
    # Khmer Symbols
    (0x19E0, 0x19FF, Alphabet.KHMER),
    # Latin Extended-C
    (0x2C60, 0x2C7F, Alphabet.LATIN),
    # Georgian Supplement
    (0x2D00, 0x2D2F, Alphabet.GEORGIAN),
    # Ethiopic Supplement
    (0x2D80, 0x2DDF, Alphabet.ETHIOPIC),
    # Cyrillic Extended-B
    (0x2DE0, 0x2DFF, Alphabet.CYRILLIC),
    # CJK Radicals Supplement + Kangxi Radicals (merged 2E80-2EFF, 2F00-2FD5)
    (0x2E80, 0x2FD5, Alphabet.HAN),
    # Hiragana
    (0x3040, 0x309F, Alphabet.JA_KANA),
    # Katakana
    (0x30A0, 0x30FF, Alphabet.JA_KANA),
    # Hangul Compatibility Jamo
    (0x3130, 0x318F, Alphabet.HANGUL),
    # Katakana Phonetic Extensions
    (0x31F0, 0x31FF, Alphabet.JA_KANA),
    # CJK Unified Ideographs Extension A
    (0x3400, 0x4DBF, Alphabet.HAN),
    # CJK Unified Ideographs
    (0x4E00, 0x9FFF, Alphabet.HAN),
    # Hangul Jamo Extended-A
    (0xA960, 0xA97F, Alphabet.HANGUL),
    # Cyrillic Extended-A
    (0xA640, 0xA69F, Alphabet.CYRILLIC),
    # Latin Extended-D
    (0xA720, 0xA7FF, Alphabet.LATIN),
    # Devanagari Extended
    (0xA8E0, 0xA8FF, Alphabet.DEVANAGARI),
    # Myanmar Extended-B
    (0xAA60, 0xAA7F, Alphabet.MYANMAR),
    # Ethiopic Extended-A
    (0xAB00, 0xAB2F, Alphabet.ETHIOPIC),
    # Latin Extended-E
    (0xAB30, 0xAB6F, Alphabet.LATIN),
    # Hangul Syllables + Jamo Extended-B (merged AC00-D7AF, D7B0-D7FF)
    (0xAC00, 0xD7FF, Alphabet.HANGUL),
    # CJK Compatibility Ideographs
    (0xF900, 0xFAFF, Alphabet.HAN),
    # Armenian ligatures
    (0xFB00, 0xFB17, Alphabet.ARMENIAN),
    # Hebrew presentation forms
    (0xFB1D, 0xFB4F, Alphabet.HEBREW),
    # Arabic presentation forms A
    (0xFB50, 0xFDFF, Alphabet.ARABIC),
    # Arabic presentation forms B
    (0xFE70, 0xFEFF, Alphabet.ARABIC),
    # Fullwidth Latin uppercase
    (0xFF21, 0xFF3A, Alphabet.LATIN),
    # Fullwidth Latin lowercase
    (0xFF41, 0xFF5A, Alphabet.LATIN),
    # Halfwidth Katakana
    (0xFF66, 0xFF9F, Alphabet.JA_KANA),
    # CJK Unified Ideographs Extension B
    (0x20000, 0x2A6DF, Alphabet.HAN),
    # CJK Unified Ideographs Extension C
    (0x2A700, 0x2B73F, Alphabet.HAN),
    # CJK Unified Ideographs Extension D
    (0x2B740, 0x2B81F, Alphabet.HAN),
]

# Sort by range start and extract starts for bisect.
_ALPHABET_RANGES.sort(key=lambda r: r[0])
_RANGE_STARTS = [r[0] for r in _ALPHABET_RANGES]


def get_alphabet(ch):
    """Returns the Alphabet for the given character, or UNKNOWN if not recognized."""
    cp = ord(ch)

    # Fast path: ASCII
    if cp < 0x80:
        return Alphabet.LATIN if cp in _ASCII_LETTERS else Alphabet.UNKNOWN

    # Binary search over sorted range table.
    i = bisect.bisect_right(_RANGE_STARTS, cp) - 1
    if i >= 0:
        _, end, alpha = _ALPHABET_RANGES[i]
        if cp <= end:
            return alpha

    return Alphabet.UNKNOWN
