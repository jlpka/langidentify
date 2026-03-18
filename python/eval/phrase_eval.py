#!/usr/bin/env python3
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

"""Phrase-level evaluation tool for langidentify.

Reads a directory of test files (one phrase per line, named {lang_code}.txt)
and evaluates detection accuracy per language.

Usage:
    python3 phrase_eval.py --languages efigs --testdir /path/to/phrases
    python3 phrase_eval.py --languages europe_west_common,cjk --testdir /path/to/phrases --misses
"""

import argparse
import os
import sys

from langidentify import Detector, Language, Model
from langidentify.accent_remover import AccentRemover


def _is_equivalent(expected, detected):
  """Treat Chinese simplified and traditional as equivalent."""
  if expected == detected:
    return True
  chinese = {Language.CHINESE_SIMPLIFIED, Language.CHINESE_TRADITIONAL}
  return expected in chinese and detected in chinese


# Pairs of languages known to cross-detect due to linguistic similarity.
# (source, target): source frequently gets detected as target.
_KNOWN_CROSS_DETECTION = {
  (Language.MALAY, Language.INDONESIAN): "id",
  (Language.AFRIKAANS, Language.DUTCH): "nl",
  (Language.NYNORSK, Language.NORWEGIAN): "no",
}


def _known_cross_note(expected_lang, languages):
  """Return a note string if this language has a known cross-detection pair configured."""
  for (src, tgt), tgt_iso in _KNOWN_CROSS_DETECTION.items():
    if expected_lang == src and tgt in languages:
      return f" ({tgt_iso}, known cross-detection)"
  return ""


def _test_file_for_lang(lang):
  """Map a language to its test filename."""
  if lang.is_chinese():
    return "zh.txt"
  return f"{lang.iso_code}.txt"


def main():
  parser = argparse.ArgumentParser(
    description="Evaluate langidentify phrase detection accuracy.")
  parser.add_argument(
    "--languages", required=True,
    help="Comma-separated language codes or group aliases (e.g. efigs, europe_west_common)")
  parser.add_argument(
    "--testdir", required=True,
    help="Directory containing test files named {lang_code}.txt, one phrase per line")
  parser.add_argument(
    "--misses", action="store_true",
    help="Print each missed phrase to stderr")
  parser.add_argument(
    "--removeaccents", action="store_true",
    help="Remove accents from phrases before detection")
  parser.add_argument(
    "--model", choices=["lite", "full", "best"], default="best",
    help="Model variant: lite, full, or best (auto-detect, default)")
  args = parser.parse_args()

  languages = Language.from_comma_separated(args.languages)
  if not languages:
    print("No languages matched.", file=sys.stderr)
    sys.exit(1)

  if args.model == "lite":
    model = Model.load_lite(languages)
  elif args.model == "full":
    model = Model.load_full(languages)
  else:
    model = Model.load(languages)

  detector = Detector(model)
  accent_remover = AccentRemover() if args.removeaccents else None

  # Deduplicate test files (zh-hans and zh-hant both map to zh.txt).
  seen_files = set()
  lang_file_pairs = []
  for lang in sorted(languages, key=lambda l: l.iso_code):
    filename = _test_file_for_lang(lang)
    if filename in seen_files:
      continue
    seen_files.add(filename)
    lang_file_pairs.append((lang, filename))

  overall_correct = 0
  overall_total = 0
  per_lang_stats = []

  for expected_lang, filename in lang_file_pairs:
    filepath = os.path.join(args.testdir, filename)
    if not os.path.exists(filepath):
      print(f"WARNING: {filepath} not found, skipping {expected_lang.iso_code}",
            file=sys.stderr)
      continue

    correct = 0
    total = 0
    skipped = 0
    cross_detections = {}

    with open(filepath, encoding="utf-8") as f:
      for line in f:
        phrase = line.rstrip("\n")
        if not phrase.strip():
          continue

        text = phrase
        if accent_remover:
          text = accent_remover.remove(text)

        detected = detector.detect(text)

        if detected == Language.UNKNOWN:
          skipped += 1
          continue

        total += 1
        if _is_equivalent(expected_lang, detected):
          correct += 1
        else:
          cross_detections[detected.iso_code] = (
            cross_detections.get(detected.iso_code, 0) + 1)
          if args.misses:
            results = detector.results
            display = phrase if len(phrase) <= 80 else phrase[:80] + "..."
            print(
              f"MISS [expected={expected_lang.iso_code} "
              f"detected={detected.iso_code}] "
              f"gap={results.gap:.3f} \"{display}\"",
              file=sys.stderr)

    iso = expected_lang.iso_code
    if expected_lang.is_chinese():
      iso = "zh"
    pct = (100.0 * correct / total) if total > 0 else 0.0
    cross_str = ""
    if cross_detections:
      ranked = sorted(cross_detections.items(), key=lambda x: -x[1])
      cross_str = " [" + ", ".join(f"{k}:{v}" for k, v in ranked) + "]"
    cross_note = _known_cross_note(expected_lang, languages)
    print(f"{iso}: {correct}/{total} correct ({pct:.1f}%), {skipped} skipped{cross_str}{cross_note}",
          file=sys.stderr)

    per_lang_stats.append((iso, correct, total, pct))
    overall_correct += correct
    overall_total += total

  overall_pct = (100.0 * overall_correct / overall_total) if overall_total > 0 else 0.0
  lang_summary = " ".join(
    f"{iso}:{c}/{t}:{p:.1f}%" for iso, c, t, p in per_lang_stats)
  print(
    f"Overall: {overall_correct}/{overall_total} correct "
    f"({overall_pct:.1f}%) {lang_summary}",
    file=sys.stderr)


if __name__ == "__main__":
  main()
