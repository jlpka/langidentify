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

"""Benchmark model loading time and memory consumption.

Usage:
    python3 model_load_speed.py --languages efigs
    python3 model_load_speed.py --languages europe_west_common --iterations 5
    python3 model_load_speed.py --languages all
"""

import argparse
import resource
import sys
import time
import tracemalloc

from langidentify import Language, Model
from langidentify.model_loader import clear_cache


def _load_model(args_model, languages):
  if args_model == "lite":
    return Model.load_lite(languages)
  elif args_model == "full":
    return Model.load_full(languages)
  else:
    return Model.load(languages)


def main():
  parser = argparse.ArgumentParser(
    description="Benchmark model loading time and memory consumption.")
  parser.add_argument(
    "--languages", required=True,
    help="Comma-separated language codes or group aliases (e.g. efigs, europe_west_common)")
  parser.add_argument(
    "--model", choices=["lite", "full", "best"], default="best",
    help="Model variant: lite, full, or best (auto-detect, default)")
  parser.add_argument(
    "--iterations", type=int, default=3,
    help="Number of loading iterations to average (default: 3)")
  args = parser.parse_args()

  languages = Language.from_comma_separated(args.languages)
  if not languages:
    print("No languages matched.", file=sys.stderr)
    sys.exit(1)

  print(f"Model:      {args.model}")
  print(f"Languages:  {len(languages)} configured")
  print(f"Iterations: {args.iterations}")
  print()

  # Time iterations without tracemalloc (which adds significant overhead).
  times = []
  for i in range(args.iterations):
    clear_cache()

    t0 = time.monotonic()
    model = _load_model(args.model, languages)
    elapsed = time.monotonic() - t0
    times.append(elapsed)

    rss_raw = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    if sys.platform == "darwin":
      rss_mb = rss_raw / (1024 * 1024)
    else:
      rss_mb = rss_raw / 1024

    print(f"  Iteration {i + 1}: {elapsed:.3f}s  RSS: {rss_mb:.1f} MB")

  avg = sum(times) / len(times)
  best = min(times)
  worst = max(times)

  print()
  print(f"Load time:  avg={avg:.3f}s  best={best:.3f}s  worst={worst:.3f}s")

  # Run one more load with tracemalloc to measure Python memory allocation.
  # This is done separately because tracemalloc adds ~8-10x overhead.
  print()
  print("Running once more with tracemalloc for accurate memory measurement "
        "(slower, RSS is not reclaimed between loads)...")
  clear_cache()
  tracemalloc.start()
  model = _load_model(args.model, languages)
  current, peak = tracemalloc.get_traced_memory()
  tracemalloc.stop()

  print(f"Memory:     {current / 1024 / 1024:.1f} MB current, "
        f"{peak / 1024 / 1024:.1f} MB peak (tracemalloc)")

  # Count entries across tables.
  total_entries = 0
  for n in range(1, model.max_ngram + 1):
    table = model.get_table(n)
    total_entries += len(table)
  tw_entries = len(model._topwords_table)

  print(f"Tables:     {total_entries:,} ngram entries, "
        f"{tw_entries:,} topword entries")


if __name__ == "__main__":
  main()
