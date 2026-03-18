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

"""Supported languages for detection.

Each language has ISO 639-1 and 639-3 codes, optional aliases, and an
associated set of Alphabets.
"""

from enum import Enum

from langidentify.alphabet import Alphabet, LATIN_SET, CYRILLIC_SET, ARABIC_SET


class Language(Enum):
    """Supported languages for detection.

    Use ``from_string()`` to parse a single language code, or
    ``from_comma_separated()`` to parse a comma-separated list (which also
    supports group aliases like "cjk" and "europe_west_common").
    """

    # value = (iso_code, iso_code3, alt_names_csv, alphabets_frozenset)
    UNKNOWN = ("", "", "", frozenset())

    # LATIN alphabet
    # Afrikaans evolved from Dutch and they remain largely mutually intelligible.
    # See: https://en.wikipedia.org/wiki/Comparison_of_Afrikaans_and_Dutch
    # Having both may cause cross-detection (Afrikaans will frequently get detected as Dutch).
    AFRIKAANS = ("af", "afr", "", LATIN_SET)
    ALBANIAN = ("sq", "sqi", "", LATIN_SET)
    AZERBAIJANI = ("az", "aze", "", LATIN_SET)
    BASQUE = ("eu", "eus", "", LATIN_SET)
    CATALAN = ("ca", "cat", "", LATIN_SET)
    CROATIAN = ("hr", "hrv", "", LATIN_SET)
    CZECH = ("cs", "ces", "", LATIN_SET)
    DANISH = ("da", "dan", "", LATIN_SET)
    DUTCH = ("nl", "nld", "", LATIN_SET)
    ENGLISH = ("en", "eng", "", LATIN_SET)
    ESPERANTO = ("eo", "epo", "", LATIN_SET)
    ESTONIAN = ("et", "est", "", LATIN_SET)
    FINNISH = ("fi", "fin", "", LATIN_SET)
    FRENCH = ("fr", "fra", "", LATIN_SET)
    GANDA = ("lg", "lug", "", LATIN_SET)
    GERMAN = ("de", "deu", "", LATIN_SET)
    HUNGARIAN = ("hu", "hun", "", LATIN_SET)
    ICELANDIC = ("is", "isl", "", LATIN_SET)
    INDONESIAN = ("id", "ind", "", LATIN_SET)
    ITALIAN = ("it", "ita", "", LATIN_SET)
    IRISH = ("ga", "gle", "gaelic", LATIN_SET)
    LATIN = ("la", "lat", "", LATIN_SET)
    LATVIAN = ("lv", "lav", "", LATIN_SET)
    LITHUANIAN = ("lt", "lit", "", LATIN_SET)
    LUXEMBOURGISH = ("lb", "ltz", "", LATIN_SET)
    # Malay and Indonesian are closely related standardizations of the same language.
    # See: https://en.wikipedia.org/wiki/Comparison_of_Indonesian_and_Standard_Malay
    # Having both may cause cross-detection (Malay will frequently get detected as Indonesian).
    MALAY = ("ms", "msa", "malaysian", LATIN_SET)
    MAORI = ("mi", "mri", "", LATIN_SET)
    # Norwegian: "no"/nb is Norwegian Bokmal dialect, nn is Norwegian Nynorsk dialect.
    # If you just want a single Norwegian cluster, use "no", which is 4x the corpus size.
    # They're close enough that they sometimes cross-detect.
    NORWEGIAN = ("no", "nor", "bokmal,nb", LATIN_SET)
    NYNORSK = ("nn", "nno", "", LATIN_SET)
    OROMO = ("om", "orm", "", LATIN_SET)
    POLISH = ("pl", "pol", "", LATIN_SET)
    PORTUGUESE = ("pt", "por", "", LATIN_SET)
    ROMANIAN = ("ro", "ron", "", LATIN_SET)
    SHONA = ("sn", "sna", "", LATIN_SET)
    SLOVAK = ("sk", "slk", "", LATIN_SET)
    SLOVENIAN = ("sl", "slv", "slovene", LATIN_SET)
    SOMALI = ("so", "som", "", LATIN_SET)
    SOTHO = ("st", "sot", "", LATIN_SET)
    SPANISH = ("es", "spa", "", LATIN_SET)
    SWAHILI = ("sw", "swa", "", LATIN_SET)
    SWEDISH = ("sv", "swe", "", LATIN_SET)
    TAGALOG = ("tl", "tgl", "", LATIN_SET)
    TSONGA = ("ts", "tso", "", LATIN_SET)
    TSWANA = ("tn", "tsn", "", LATIN_SET)
    TURKISH = ("tr", "tur", "", LATIN_SET)
    VIETNAMESE = ("vi", "vie", "", LATIN_SET)
    WELSH = ("cy", "cym", "", LATIN_SET)
    XHOSA = ("xh", "xho", "", LATIN_SET)
    YORUBA = ("yo", "yor", "", LATIN_SET)
    ZULU = ("zu", "zul", "", LATIN_SET)

    # CJK
    CHINESE_SIMPLIFIED = (
        "zh-hans", "zho-hans",
        "chinese,zh,zh-cn,zh-hans-cn,zh-hans-sg",
        frozenset([Alphabet.HAN]))
    CHINESE_TRADITIONAL = (
        "zh-hant", "zho-hant",
        "zh-hant-hk,zh-hk,zh-hant-tw,zh-tw",
        frozenset([Alphabet.HAN]))
    JAPANESE = ("ja", "jpn", "jp", frozenset([Alphabet.HAN, Alphabet.JA_KANA]))
    KOREAN = ("ko", "kor", "", frozenset([Alphabet.HANGUL]))

    # ARABIC
    ARABIC = ("ar", "ara", "", ARABIC_SET)
    PASHTO = ("ps", "pus", "", ARABIC_SET)
    PERSIAN = ("fa", "fas", "farsi", ARABIC_SET)
    URDU = ("ur", "urd", "", ARABIC_SET)

    # CYRILLIC
    BELARUSIAN = ("be", "bel", "", CYRILLIC_SET)
    BULGARIAN = ("bg", "bul", "", CYRILLIC_SET)
    MACEDONIAN = ("mk", "mkd", "", CYRILLIC_SET)
    MONGOLIAN = ("mn", "mon", "", CYRILLIC_SET)
    RUSSIAN = ("ru", "rus", "", CYRILLIC_SET)
    SERBIAN = ("sr", "srp", "", CYRILLIC_SET)
    UKRAINIAN = ("uk", "ukr", "", CYRILLIC_SET)

    # ETHIOPIC
    AMHARIC = ("am", "amh", "", frozenset([Alphabet.ETHIOPIC]))
    TIGRINYA = ("ti", "tig", "", frozenset([Alphabet.ETHIOPIC]))

    # Singleton alphabets
    ARMENIAN = ("hy", "hye", "", frozenset([Alphabet.ARMENIAN]))
    BENGALI = ("bn", "ben", "", frozenset([Alphabet.BENGALI]))
    BURMESE = ("my", "mya", "myanmar", frozenset([Alphabet.MYANMAR]))
    GEORGIAN = ("ka", "kat", "", frozenset([Alphabet.GEORGIAN]))
    GREEK = ("el", "ell", "", frozenset([Alphabet.GREEK]))
    GUJARATI = ("gu", "guj", "", frozenset([Alphabet.GUJARATI]))
    HEBREW = ("he", "heb", "", frozenset([Alphabet.HEBREW]))
    HINDI = ("hi", "hin", "", frozenset([Alphabet.DEVANAGARI]))
    KANNADA = ("kn", "kan", "", frozenset([Alphabet.KANNADA]))
    KHMER = ("km", "khm", "", frozenset([Alphabet.KHMER]))
    LAO = ("lo", "lao", "", frozenset([Alphabet.LAO]))
    MALAYALAM = ("ml", "mal", "", frozenset([Alphabet.MALAYALAM]))
    PUNJABI = ("pa", "pan", "", frozenset([Alphabet.GURMUKHI]))
    SINHALA = ("si", "sin", "", frozenset([Alphabet.SINHALA]))
    TAMIL = ("ta", "tam", "", frozenset([Alphabet.TAMIL]))
    TELUGU = ("te", "tel", "", frozenset([Alphabet.TELUGU]))
    THAI = ("th", "tha", "", frozenset([Alphabet.THAI]))

    def __init__(self, iso_code, iso_code3, alt_names_csv, alphabets):
        self.iso_code = iso_code
        self.iso_code3 = iso_code3
        self.alt_names = (
            alt_names_csv.split(",") if alt_names_csv else [])
        self.alphabets = alphabets

    def is_chinese(self):
        """Returns True if this is CHINESE_SIMPLIFIED or CHINESE_TRADITIONAL."""
        return self in (Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL)

    @staticmethod
    def from_string(s):
        """Parses a language name (case-insensitive).

        Accepts the enum name, ISO 639-1/639-3 codes, and aliases.
        Returns UNKNOWN if the input is unrecognized.
        """
        if s is None:
            return Language.UNKNOWN
        return _BY_NAME.get(s.lower(), Language.UNKNOWN)

    @staticmethod
    def from_comma_separated(s):
        """Parses a comma-separated string of language codes into a list.

        Also supports group aliases such as "cjk" or "europe_west_common".
        Raises ValueError if any code is unrecognized.
        """
        if not s:
            return []
        result = []
        for code in s.split(","):
            code = code.strip().lower()
            lang = Language.from_string(code)
            if lang != Language.UNKNOWN:
                result.append(lang)
            else:
                group = GROUP_ALIASES.get(code)
                if group is not None:
                    result.extend(group)
                else:
                    raise ValueError(f"Could not parse '{code}'")
        return result


def _languages_for_alphabet(alpha):
    result = set()
    for lang in Language:
        if alpha in lang.alphabets:
            result.add(lang)
    return frozenset(result)


def _unique_alphabet_languages():
    by_alphabet = {}
    for lang in Language:
        if lang == Language.UNKNOWN:
            continue
        for a in lang.alphabets:
            by_alphabet.setdefault(a, []).append(lang)
    result = set()
    for lang in Language:
        if lang == Language.UNKNOWN or len(lang.alphabets) != 1:
            continue
        sole = next(iter(lang.alphabets))
        if len(by_alphabet[sole]) == 1:
            result.add(lang)
    return frozenset(result)


# Build the name lookup map.
_BY_NAME = {}
for _lang in Language:
    _BY_NAME[_lang.name.lower()] = _lang
    if _lang.iso_code:
        _BY_NAME[_lang.iso_code] = _lang
    if _lang.iso_code3:
        _BY_NAME[_lang.iso_code3] = _lang
    for _name in _lang.alt_names:
        _BY_NAME[_name] = _lang

# Language groups
EFIGS = frozenset([
    Language.ENGLISH, Language.FRENCH, Language.ITALIAN,
    Language.GERMAN, Language.SPANISH])
EFIGSNP = EFIGS | frozenset([Language.DUTCH, Language.PORTUGUESE])
NORDIC_COMMON = frozenset([
    Language.DANISH, Language.SWEDISH, Language.NORWEGIAN, Language.FINNISH])
EUROPE_WEST_COMMON = EFIGSNP | NORDIC_COMMON
EUROPE_LATIN = frozenset([
    Language.ALBANIAN, Language.BASQUE, Language.CATALAN, Language.CROATIAN,
    Language.CZECH, Language.DANISH, Language.DUTCH, Language.ENGLISH,
    Language.ESTONIAN, Language.FINNISH, Language.FRENCH, Language.GERMAN,
    Language.HUNGARIAN, Language.ICELANDIC, Language.ITALIAN, Language.IRISH,
    Language.LATVIAN, Language.LITHUANIAN, Language.LUXEMBOURGISH,
    Language.NORWEGIAN, Language.NYNORSK, Language.POLISH,
    Language.PORTUGUESE, Language.ROMANIAN, Language.SLOVAK,
    Language.SLOVENIAN, Language.SPANISH, Language.SWEDISH, Language.TURKISH,
    Language.WELSH])
EUROPE_EAST_LATIN = frozenset([
    Language.ALBANIAN, Language.CROATIAN, Language.CZECH, Language.ESTONIAN,
    Language.HUNGARIAN, Language.LATVIAN, Language.LITHUANIAN, Language.POLISH,
    Language.ROMANIAN, Language.SLOVAK, Language.SLOVENIAN])
EUROPE_CYRILLIC = frozenset([
    Language.BELARUSIAN, Language.BULGARIAN, Language.MACEDONIAN,
    Language.RUSSIAN, Language.SERBIAN, Language.UKRAINIAN])
EUROPE = EUROPE_LATIN | EUROPE_CYRILLIC
EUROPE_COMMON = EUROPE_WEST_COMMON | EUROPE_EAST_LATIN | EUROPE_CYRILLIC
CHINESE_SCRIPTS = frozenset([
    Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL])
CJ = frozenset([
    Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL,
    Language.JAPANESE])
CJK = CJ | frozenset([Language.KOREAN])

LATIN_ALPHABET = _languages_for_alphabet(Alphabet.LATIN)
CYRILLIC_ALPHABET = _languages_for_alphabet(Alphabet.CYRILLIC)
ARABIC_ALPHABET = _languages_for_alphabet(Alphabet.ARABIC)
UNIQUE_ALPHABET = _unique_alphabet_languages()
ALL = frozenset(lang for lang in Language if lang != Language.UNKNOWN)

GROUP_ALIASES = {
    "all": ALL,
    "cj": CJ,
    "cjk": CJK,
    "efigs": EFIGS,
    "efigsnp": EFIGSNP,
    "nordic": NORDIC_COMMON,
    "europe_west_common": EUROPE_WEST_COMMON,
    "europe_common": EUROPE_COMMON,
    "europe_east_latin": EUROPE_EAST_LATIN,
    "europe_latin": EUROPE_LATIN,
    "europe_cyrillic": EUROPE_CYRILLIC,
    "europe": EUROPE,
    "latin_alphabet": LATIN_ALPHABET,
    "cyrillic_alphabet": CYRILLIC_ALPHABET,
    "arabic_alphabet": ARABIC_ALPHABET,
    "unique_alphabet": UNIQUE_ALPHABET,
}
