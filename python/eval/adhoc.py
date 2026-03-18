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

"""Ad-hoc language detection of a single phrase from the command line.

Usage:
    python3 adhoc.py --languages efigs --phrase "this is a test sentence"
    python3 adhoc.py --languages europe_west_common --phrase "Bonjour le monde"
"""

import argparse
import sys

from langidentify import Detector, Language, Model


def main():
  parser = argparse.ArgumentParser(
    description="Detect the language of a single phrase.")
  parser.add_argument(
    "--languages", required=True,
    help="Comma-separated language codes or group aliases (e.g. efigs, europe_west_common)")
  parser.add_argument(
    "--phrase", required=True,
    help="The phrase to detect")
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

  result = detector.detect(args.phrase)
  results = detector.results

  print(f"Result: {result.iso_code} ({result.name.replace('_', ' ').title()})")
  print(f"Gap:    {results.gap:.4f}")
  print()

  # Show per-language score breakdown, sorted by total score (best first).
  langs = model.languages
  scores = results.scores
  total = results.total_scores

  # Build (total_score, lang_index) pairs sorted best first.
  order = sorted(range(len(langs)), key=lambda i: total[i], reverse=True)

  print(f"{'lang':>6}  {'ngram':>10}  {'hits':>5}  {'tw':>10}  {'tw_hits':>7}  {'total':>10}")
  print(f"{'----':>6}  {'-----':>10}  {'----':>5}  {'--':>10}  {'------':>7}  {'-----':>10}")
  for li in order:
    print(
      f"{langs[li].iso_code:>6}  "
      f"{scores.ngram_scores[li]:>10.3f}  "
      f"{scores.ngram_hits_per_lang[li]:>5d}  "
      f"{scores.tw_scores[li]:>10.3f}  "
      f"{scores.tw_hits_per_lang[li]:>7d}  "
      f"{total[li]:>10.3f}")


if __name__ == "__main__":
  main()
