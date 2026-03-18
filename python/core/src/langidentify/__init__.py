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

"""langidentify — fast language detection using n-gram and topword scoring.

Usage::

    from langidentify import Detector, Model, Language

    languages = Language.from_comma_separated("en,fr,de")
    model = Model.load(languages)
    detector = Detector(model)
    lang = detector.detect("Bonjour le monde")
    print(lang)  # Language.FRENCH
"""

from langidentify.alphabet import Alphabet
from langidentify.accent_remover import AccentRemover
from langidentify.detector import Detector, Results, Scores
from langidentify.language import Language
from langidentify.model import Model

__all__ = [
    "Alphabet",
    "AccentRemover",
    "Detector",
    "Language",
    "Model",
    "Results",
    "Scores",
]

__version__ = "1.0.1"
