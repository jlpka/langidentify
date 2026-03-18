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
        if self == Alphabet.HAN:
            return 3.0
        if self in (Alphabet.HANGUL, Alphabet.JA_KANA):
            return 2.0
        if self == Alphabet.ARABIC:
            return 1.25
        return 1.0

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

# Precomputed set of ASCII letter code points for fast Latin detection.
_ASCII_LETTERS = set()
for _c in range(128):
    if chr(_c).isalpha():
        _ASCII_LETTERS.add(_c)
_ASCII_LETTERS = frozenset(_ASCII_LETTERS)


def get_alphabet(ch):
    """Returns the Alphabet for the given character, or UNKNOWN if not recognized."""
    cp = ord(ch)

    # Fast path: ASCII
    if cp < 0x80:
        return Alphabet.LATIN if cp in _ASCII_LETTERS else Alphabet.UNKNOWN

    # Extended Latin ranges
    if (0x00C0 <= cp <= 0x024F  # Latin Extended-A, B
            or 0x1E00 <= cp <= 0x1EFF  # Latin Extended Additional
            or 0x2C60 <= cp <= 0x2C7F  # Latin Extended-C
            or 0xA720 <= cp <= 0xA7FF  # Latin Extended-D
            or 0xAB30 <= cp <= 0xAB6F  # Latin Extended-E
            or 0xFF21 <= cp <= 0xFF3A  # Fullwidth Latin uppercase
            or 0xFF41 <= cp <= 0xFF5A  # Fullwidth Latin lowercase
            or 0x0100 <= cp <= 0x017F  # Latin Extended-A
            or 0x0180 <= cp <= 0x024F):  # Latin Extended-B
        return Alphabet.LATIN

    # CJK Unified Ideographs (HAN)
    if (0x4E00 <= cp <= 0x9FFF
            or 0x3400 <= cp <= 0x4DBF
            or 0x2E80 <= cp <= 0x2EFF
            or 0x2F00 <= cp <= 0x2FD5
            or 0xF900 <= cp <= 0xFAFF
            or 0x20000 <= cp <= 0x2A6DF
            or 0x2A700 <= cp <= 0x2B73F
            or 0x2B740 <= cp <= 0x2B81F):
        return Alphabet.HAN

    # Japanese Kana
    if (0x3040 <= cp <= 0x309F  # Hiragana
            or 0x30A0 <= cp <= 0x30FF  # Katakana
            or 0x31F0 <= cp <= 0x31FF  # Katakana Phonetic Extensions
            or 0xFF66 <= cp <= 0xFF9F):  # Halfwidth Katakana
        return Alphabet.JA_KANA

    # Hangul
    if (0xAC00 <= cp <= 0xD7AF  # Hangul Syllables
            or 0x1100 <= cp <= 0x11FF  # Hangul Jamo
            or 0x3130 <= cp <= 0x318F  # Hangul Compatibility Jamo
            or 0xA960 <= cp <= 0xA97F  # Hangul Jamo Extended-A
            or 0xD7B0 <= cp <= 0xD7FF):  # Hangul Jamo Extended-B
        return Alphabet.HANGUL

    # Cyrillic
    if (0x0400 <= cp <= 0x04FF
            or 0x0500 <= cp <= 0x052F
            or 0x2DE0 <= cp <= 0x2DFF
            or 0xA640 <= cp <= 0xA69F):
        return Alphabet.CYRILLIC

    # Arabic
    if (0x0600 <= cp <= 0x06FF
            or 0x0750 <= cp <= 0x077F
            or 0x08A0 <= cp <= 0x08FF
            or 0xFB50 <= cp <= 0xFDFF
            or 0xFE70 <= cp <= 0xFEFF):
        return Alphabet.ARABIC

    # Greek
    if 0x0370 <= cp <= 0x03FF or 0x1F00 <= cp <= 0x1FFF:
        return Alphabet.GREEK

    # Armenian
    if 0x0530 <= cp <= 0x058F or 0xFB00 <= cp <= 0xFB17:
        return Alphabet.ARMENIAN

    # Hebrew
    if 0x0590 <= cp <= 0x05FF or 0xFB1D <= cp <= 0xFB4F:
        return Alphabet.HEBREW

    # Devanagari
    if 0x0900 <= cp <= 0x097F or 0xA8E0 <= cp <= 0xA8FF:
        return Alphabet.DEVANAGARI

    # Bengali
    if 0x0980 <= cp <= 0x09FF:
        return Alphabet.BENGALI

    # Gurmukhi
    if 0x0A00 <= cp <= 0x0A7F:
        return Alphabet.GURMUKHI

    # Gujarati
    if 0x0A80 <= cp <= 0x0AFF:
        return Alphabet.GUJARATI

    # Tamil
    if 0x0B80 <= cp <= 0x0BFF:
        return Alphabet.TAMIL

    # Telugu
    if 0x0C00 <= cp <= 0x0C7F:
        return Alphabet.TELUGU

    # Kannada
    if 0x0C80 <= cp <= 0x0CFF:
        return Alphabet.KANNADA

    # Malayalam
    if 0x0D00 <= cp <= 0x0D7F:
        return Alphabet.MALAYALAM

    # Sinhala
    if 0x0D80 <= cp <= 0x0DFF:
        return Alphabet.SINHALA

    # Thai
    if 0x0E00 <= cp <= 0x0E7F:
        return Alphabet.THAI

    # Lao
    if 0x0E80 <= cp <= 0x0EFF:
        return Alphabet.LAO

    # Myanmar
    if 0x1000 <= cp <= 0x109F or 0xAA60 <= cp <= 0xAA7F:
        return Alphabet.MYANMAR

    # Georgian
    if (0x10A0 <= cp <= 0x10FF
            or 0x2D00 <= cp <= 0x2D2F
            or 0x1C90 <= cp <= 0x1CBF):
        return Alphabet.GEORGIAN

    # Ethiopic
    if (0x1200 <= cp <= 0x137F
            or 0x1380 <= cp <= 0x139F
            or 0x2D80 <= cp <= 0x2DDF
            or 0xAB00 <= cp <= 0xAB2F):
        return Alphabet.ETHIOPIC

    # Khmer
    if 0x1780 <= cp <= 0x17FF or 0x19E0 <= cp <= 0x19FF:
        return Alphabet.KHMER

    return Alphabet.UNKNOWN
