import argparse
import subprocess
import time
from concurrent.futures import ProcessPoolExecutor
from functools import partial

# python3 scripts/runwikieval.py --language_model fr,de,it,nl --eval_languages fr,de,it,nl
#
# or with flags, e.g.
#  python3 ./scripts/runwikieval.py --language_model europe_west_common --eval_languages de,en,es,fi,fr,it,nl,pt --minlogprob -12 --twminlogprob -12 | grep Overall | sort
#
# It will typically run with parallelism=4 processes. Each eval process can keep roughly 2 threads busy.

def run_java(eval_lang: str, language_model: str, limit: int, extra_args: list):
    cmd = [
        "java", "-jar", "tools/target/langidentify-tools-1.0.2.jar",
        "wikieval",
        "--languages", f"{language_model}",
        "--model", "../wikidata/derived/",
        "--infiles", f"/Volumes/devdata/wikidata/orig/{eval_lang}wiki-20260201-pages-articles.xml.bz2:{eval_lang}",
        "--byparagraph",
        "--limit", f"{limit}",
    ] + extra_args
    print(f"Starting: {eval_lang} {cmd}", flush=True)
    start_time = time.time()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in proc.stdout:
        if line.startswith(f"{eval_lang}: "):
            print(line, end="", flush=True)
        else:
            print(f"{eval_lang}: {line}", end="", flush=True)
    proc.wait()
    elapsed = time.time() - start_time
    print(f"Finished: {eval_lang} Took: {elapsed:.1f}s", flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run ngrams extraction in parallel")
    parser.add_argument("--eval_languages", required=True, help="Comma-separated language codes to evaluate, e.g. en,fr,de")
    parser.add_argument("--language_model", required=True, help="Comma-separated language codes in the model, e.g. en,fr,de")
    parser.add_argument("--parallelism", type=int, default=4, help="Number of parallel workers (default 4)")
    parser.add_argument("--limit", type=int, default=1000000, help="Number of paragraphs to eval")
    # passed through if present.
    parser.add_argument("--minlogprob", type=float, help="Min log-prob threshold")
    parser.add_argument("--twminlogprob", type=float, help="Min topword log-prob threshold")
    parser.add_argument("--cjminlogprob", type=float, help="CJ classifier log-prob floor")
    parser.add_argument("--maxngram", type=int, help="Max ngram size (0=auto)")
    parser.add_argument("--minngram", type=int, help="Min ngram size (default 1)")
    parser.add_argument("--stopifngramcovered", type=int, help="Stop lower ngrams if n-grams fully covered")
    parser.add_argument("--removeaccents", action="store_true", help="Remove accents/ligatures before detection")
    parser.add_argument("--skiptopwords", action="store_true", help="Skip loading topwords data")
    parser.add_argument("--sampledmisses", type=int, help="Print out a sampled set of this many misses")
    args = parser.parse_args()

    extra_args = []
    for name in ["minlogprob", "twminlogprob", "cjminlogprob", "maxngram", "minngram", "stopifngramcovered", "sampledmisses"]:
        val = getattr(args, name)
        if val is not None:
            extra_args.extend([f"--{name}", str(val)])
    for name in ["removeaccents", "skiptopwords"]:
        if getattr(args, name):
            extra_args.append(f"--{name}")

    eval_languages = args.eval_languages.split(",")

    with ProcessPoolExecutor(max_workers=args.parallelism) as executor:
        list(executor.map(partial(run_java, language_model=args.language_model, limit=args.limit, extra_args=extra_args), eval_languages))
