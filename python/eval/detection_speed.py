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

"""Benchmark detection throughput (words/s and detections/s).

Loads test phrases from a directory of {lang_code}.txt files and repeatedly
detects them for a fixed duration, reporting throughput periodically.

Usage:
    python3 detection_speed.py --languages efigs --testdir /path/to/phrases
    python3 detection_speed.py --languages efigsnp --testdir /path/to/phrases --duration 60
    python3 detection_speed.py --languages efigs --testdir /path/to/phrases --model full
"""

import argparse
import os
import sys
import time

from langidentify import Detector, Language, Model
from langidentify.accent_remover import AccentRemover


def _test_file_for_lang(lang):
  """Map a language to its test filename."""
  if lang.is_chinese():
    return "zh.txt"
  return f"{lang.iso_code}.txt"


def main():
  parser = argparse.ArgumentParser(
    description="Benchmark detection throughput.")
  parser.add_argument(
    "--languages", required=True,
    help="Comma-separated language codes or group aliases (e.g. efigs, europe_west_common)")
  parser.add_argument(
    "--testdir", required=True,
    help="Directory containing test files named {lang_code}.txt, one phrase per line")
  parser.add_argument(
    "--model", choices=["lite", "full", "best"], default="best",
    help="Model variant: lite, full, or best (auto-detect, default)")
  parser.add_argument(
    "--duration", type=int, default=30,
    help="Benchmark duration in seconds (default: 30)")
  parser.add_argument(
    "--removeaccents", action="store_true",
    help="Remove accents from phrases before detection")
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

  # Load all phrases into memory.
  phrases = []
  seen_files = set()
  for lang in sorted(languages, key=lambda l: l.iso_code):
    filename = _test_file_for_lang(lang)
    if filename in seen_files:
      continue
    seen_files.add(filename)

    filepath = os.path.join(args.testdir, filename)
    if not os.path.exists(filepath):
      print(f"Warning: {filepath} not found, skipping {lang.iso_code}",
            file=sys.stderr)
      continue

    count = 0
    with open(filepath, encoding="utf-8") as f:
      for line in f:
        phrase = line.rstrip("\n")
        if not phrase.strip():
          continue
        if accent_remover:
          phrase = accent_remover.remove(phrase)
        phrases.append(phrase)
        count += 1
    print(f"  loaded {count} phrases for {lang.iso_code}", file=sys.stderr)

  if not phrases:
    print("Error: no phrases loaded", file=sys.stderr)
    sys.exit(1)

  print(
    f"Detect speed benchmark: {len(phrases)} phrases, "
    f"{len(languages)} languages, duration {args.duration}s",
    file=sys.stderr)

  # Warmup: one pass through all phrases.
  print("Warmup...", file=sys.stderr)
  for phrase in phrases:
    detector.detect(phrase)

  # Benchmark loop.
  deadline = time.monotonic() + args.duration
  report_interval = 5.0
  next_report = time.monotonic() + report_interval

  total_words = 0
  total_detections = 0
  interval_words = 0
  interval_detections = 0
  interval_start = time.monotonic()
  bench_start = interval_start
  phrase_idx = 0

  while time.monotonic() < deadline:
    detector.detect(phrases[phrase_idx])
    words = detector.results.scores.num_words
    total_words += words
    total_detections += 1
    interval_words += words
    interval_detections += 1

    phrase_idx += 1
    if phrase_idx >= len(phrases):
      phrase_idx = 0

    now = time.monotonic()
    if now >= next_report:
      interval_sec = now - interval_start
      m_words_per_sec = interval_words / interval_sec / 1_000_000
      ns_per_word = (interval_sec * 1e9 / interval_words) if interval_words > 0 else 0
      total_sec = now - bench_start
      total_mw_per_sec = total_words / total_sec / 1_000_000
      print(
        f"  {total_sec:.0f}s: {m_words_per_sec:.2f} Mwords/s  "
        f"{ns_per_word:.0f} ns/word  "
        f"({total_detections} phrases, cumulative: {total_mw_per_sec:.2f} Mwords/s)",
        file=sys.stderr)
      interval_words = 0
      interval_detections = 0
      interval_start = now
      next_report = now + report_interval

  end = time.monotonic()
  total_sec = end - bench_start
  m_words_per_sec = total_words / total_sec / 1_000_000
  ns_per_word = (total_sec * 1e9 / total_words) if total_words > 0 else 0

  print(file=sys.stderr)
  print(
    f"Final: {total_detections} detections, {total_words} words in {total_sec:.1f}s",
    file=sys.stderr)
  print(
    f"  {m_words_per_sec:.2f} Mwords/s  {ns_per_word:.0f} ns/word",
    file=sys.stderr)


if __name__ == "__main__":
  main()
