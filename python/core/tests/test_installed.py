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

"""Tests for langidentify using installed package data (no filesystem paths).

These tests verify that the package works correctly when installed via pip,
loading model data from the installed package rather than from a filesystem path.
"""

import pytest

from langidentify import Detector, Language, Model


@pytest.fixture(scope="module")
def model_efigs():
    """Load EFIGS model from installed package data."""
    languages = Language.from_comma_separated("en,fr,de,it,es")
    return Model.load(languages)


@pytest.fixture(scope="module")
def detector_efigs(model_efigs):
    return Detector(model_efigs)


class TestInstalledDetection:
    def test_english(self, detector_efigs):
        assert detector_efigs.detect(
            "This is a test in English") == Language.ENGLISH

    def test_french(self, detector_efigs):
        assert detector_efigs.detect(
            "Bonjour le monde") == Language.FRENCH

    def test_german(self, detector_efigs):
        assert detector_efigs.detect(
            "Das ist ein Test auf Deutsch") == Language.GERMAN

    def test_spanish(self, detector_efigs):
        assert detector_efigs.detect(
            "Esta es una prueba en español") == Language.SPANISH

    def test_italian(self, detector_efigs):
        assert detector_efigs.detect(
            "Questa è una prova in italiano") == Language.ITALIAN


class TestInstalledMultiScript:
    @pytest.fixture(scope="class")
    def model_multi(self):
        languages = Language.from_comma_separated("en,ru,th,el,ar")
        return Model.load(languages)

    @pytest.fixture(scope="class")
    def detector_multi(self, model_multi):
        return Detector(model_multi)

    def test_russian(self, detector_multi):
        assert detector_multi.detect(
            "Это тест на русском языке") == Language.RUSSIAN

    def test_thai(self, detector_multi):
        assert detector_multi.detect(
            "นี่คือการทดสอบภาษาไทย") == Language.THAI


class TestInstalledCJK:
    @pytest.fixture(scope="class")
    def model_cjk(self):
        languages = Language.from_comma_separated("en,ja,zh-hans,zh-hant,ko")
        return Model.load(languages)

    @pytest.fixture(scope="class")
    def detector_cjk(self, model_cjk):
        return Detector(model_cjk)

    def test_japanese(self, detector_cjk):
        assert detector_cjk.detect(
            "これは日本語のテストです") == Language.JAPANESE

    def test_chinese_simplified(self, detector_cjk):
        assert detector_cjk.detect(
            "这是一个简体中文测试") == Language.CHINESE_SIMPLIFIED

    def test_korean(self, detector_cjk):
        assert detector_cjk.detect(
            "이것은 한국어 테스트입니다") == Language.KOREAN


class TestInstalledIncremental:
    def test_incremental_api(self, detector_efigs):
        detector_efigs.clear_scores()
        detector_efigs.add_text("This is ")
        detector_efigs.add_text("a test in English")
        lang = detector_efigs.compute_result()
        assert lang == Language.ENGLISH

    def test_results_gap(self, detector_efigs):
        detector_efigs.detect(
            "The quick brown fox jumps over the lazy dog")
        assert detector_efigs.results.gap > 0.0
