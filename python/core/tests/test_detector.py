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

"""Tests for langidentify using the lite model loaded from disk."""

import os
import pytest

from langidentify import Detector, Language, Model

# Path to the lite model data (relative to this test file).
_LITE_MODEL_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "..", "models-lite",
    "src", "main", "resources", "com", "jlpka", "langidentify", "models", "lite")


@pytest.fixture(scope="module")
def model_efigs():
    """Load EFIGS model (English, French, Italian, German, Spanish)."""
    languages = Language.from_comma_separated("en,fr,de,it,es")
    return Model.load_from_path(_LITE_MODEL_PATH, languages)


@pytest.fixture(scope="module")
def detector_efigs(model_efigs):
    return Detector(model_efigs)


class TestLanguage:
    def test_from_string(self):
        assert Language.from_string("en") == Language.ENGLISH
        assert Language.from_string("English") == Language.ENGLISH
        assert Language.from_string("eng") == Language.ENGLISH
        assert Language.from_string("xx") == Language.UNKNOWN

    def test_from_comma_separated(self):
        langs = Language.from_comma_separated("en,fr,de")
        assert Language.ENGLISH in langs
        assert Language.FRENCH in langs
        assert Language.GERMAN in langs

    def test_group_aliases(self):
        langs = Language.from_comma_separated("efigs")
        assert len(langs) == 5
        assert Language.ENGLISH in langs

    def test_iso_codes(self):
        assert Language.ENGLISH.iso_code == "en"
        assert Language.ENGLISH.iso_code3 == "eng"

    def test_is_chinese(self):
        assert Language.CHINESE_SIMPLIFIED.is_chinese()
        assert Language.CHINESE_TRADITIONAL.is_chinese()
        assert not Language.JAPANESE.is_chinese()


class TestAlphabet:
    def test_get_alphabet(self):
        from langidentify.alphabet import get_alphabet, Alphabet
        assert get_alphabet("a") == Alphabet.LATIN
        assert get_alphabet("Z") == Alphabet.LATIN
        assert get_alphabet(" ") == Alphabet.UNKNOWN
        assert get_alphabet("5") == Alphabet.UNKNOWN

    def test_get_alphabet_nonlatin(self):
        from langidentify.alphabet import get_alphabet, Alphabet
        assert get_alphabet("\u0410") == Alphabet.CYRILLIC  # А
        assert get_alphabet("\u4e00") == Alphabet.HAN  # 一
        assert get_alphabet("\u3042") == Alphabet.JA_KANA  # あ
        assert get_alphabet("\uAC00") == Alphabet.HANGUL  # 가
        assert get_alphabet("\u0E01") == Alphabet.THAI  # ก

    def test_extended_latin(self):
        from langidentify.alphabet import get_alphabet, Alphabet
        assert get_alphabet("é") == Alphabet.LATIN
        assert get_alphabet("ñ") == Alphabet.LATIN
        assert get_alphabet("ü") == Alphabet.LATIN


class TestAccentRemover:
    def test_basic(self):
        from langidentify.accent_remover import AccentRemover
        ar = AccentRemover()
        assert ar.remove("café") == "cafe"
        assert ar.remove("naïve") == "naive"
        assert ar.remove("hello") == "hello"
        assert ar.remove("straße") == "strasse"


class TestDetectorEFIGS:
    def test_english(self, detector_efigs):
        assert detector_efigs.detect("This is a test") == Language.ENGLISH

    def test_french(self, detector_efigs):
        assert detector_efigs.detect("Bonjour le monde") == Language.FRENCH

    def test_german(self, detector_efigs):
        assert detector_efigs.detect("Das ist ein Test auf Deutsch") == Language.GERMAN

    def test_spanish(self, detector_efigs):
        assert detector_efigs.detect(
            "Esta es una prueba en español") == Language.SPANISH

    def test_italian(self, detector_efigs):
        assert detector_efigs.detect(
            "Questa è una prova in italiano") == Language.ITALIAN

    def test_longer_english(self, detector_efigs):
        text = (
            "The quick brown fox jumps over the lazy dog. "
            "This sentence contains enough words to be detected reliably.")
        assert detector_efigs.detect(text) == Language.ENGLISH

    def test_results_gap(self, detector_efigs):
        detector_efigs.detect(
            "The quick brown fox jumps over the lazy dog")
        assert detector_efigs.results.gap > 0.0

    def test_incremental_api(self, detector_efigs):
        detector_efigs.clear_scores()
        detector_efigs.add_text("This is ")
        detector_efigs.add_text("a test in English")
        lang = detector_efigs.compute_result()
        assert lang == Language.ENGLISH


class TestDetectorMultiScript:
    """Test detection with multiple scripts/alphabets."""

    @pytest.fixture(scope="class")
    def model_multi(self):
        languages = Language.from_comma_separated("en,ru,th,el,ar")
        return Model.load_from_path(_LITE_MODEL_PATH, languages)

    @pytest.fixture(scope="class")
    def detector_multi(self, model_multi):
        return Detector(model_multi)

    def test_russian(self, detector_multi):
        assert detector_multi.detect(
            "Это тест на русском языке") == Language.RUSSIAN

    def test_thai(self, detector_multi):
        assert detector_multi.detect(
            "นี่คือการทดสอบภาษาไทย") == Language.THAI

    def test_greek(self, detector_multi):
        assert detector_multi.detect(
            "Αυτό είναι ένα τεστ στα ελληνικά") == Language.GREEK

    def test_arabic(self, detector_multi):
        assert detector_multi.detect(
            "هذا اختبار باللغة العربية") == Language.ARABIC


class TestDetectorCJK:
    """Test detection with Chinese, Japanese, and Korean."""

    @pytest.fixture(scope="class")
    def model_cjk(self):
        languages = Language.from_comma_separated("en,ja,zh-hans,zh-hant,ko")
        return Model.load_from_path(_LITE_MODEL_PATH, languages)

    @pytest.fixture(scope="class")
    def detector_cjk(self, model_cjk):
        return Detector(model_cjk)

    def test_japanese(self, detector_cjk):
        assert detector_cjk.detect(
            "これは日本語のテストです") == Language.JAPANESE

    def test_japanese_kanji_only(self, detector_cjk):
        assert detector_cjk.detect("事務所") == Language.JAPANESE

    def test_chinese_simplified(self, detector_cjk):
        assert detector_cjk.detect(
            "这是一个简体中文测试") == Language.CHINESE_SIMPLIFIED

    def test_chinese_traditional(self, detector_cjk):
        assert detector_cjk.detect(
            "這是一個繁體中文測試") == Language.CHINESE_TRADITIONAL

    def test_korean(self, detector_cjk):
        assert detector_cjk.detect(
            "이것은 한국어 테스트입니다") == Language.KOREAN

    def test_english_among_cjk(self, detector_cjk):
        assert detector_cjk.detect(
            "This is a test in English") == Language.ENGLISH


class TestBoosts:
    def test_boost_changes_result(self, model_efigs, detector_efigs):
        # "table" is ambiguous between English and French
        boosts = model_efigs.build_boost_array({Language.FRENCH: 0.3})
        lang = detector_efigs.detect("table", boosts)
        assert lang == Language.FRENCH
