import argparse
import subprocess
import time
from concurrent.futures import ProcessPoolExecutor
from functools import partial

# python3 scripts/calcngrams.py --alphabet latin --languages bs

# or with skipwords, for languages without a big wiki context:
#   python3 scripts/calcngrams.py --alphabet latin --languages bs  --skipwords "../wikidata/derived/topwords-en.txt/100"

WIKI_ORIG = "/Volumes/devdata/wikidata/orig"
WIKI_DERIVED = "../wikidata/derived"

def run_java(lang:str, alphabet:str, skipwords:str):
    cmd = [
        "java", "-cp", "tools/target/langidentify-tools-1.0.jar",
        "com.jlpka.langidentify.tools.ModelBuilder", "ngrams",
        "--infile", f"{WIKI_ORIG}/{lang}wiki-20260201-pages-articles.xml.bz2",
        "--outfile", f"{WIKI_DERIVED}/ngrams-{lang}.txt",
        "--topwords", f"{WIKI_DERIVED}/topwords-{lang}.txt",
        "--alphabet", f"{alphabet}", "--minlogprob", "-18", "--twminlogprob", "-15",
        "--twaslogprob"
    ]
    if skipwords:
        cmd.extend(["--skipwords", skipwords])
    print(f"Starting: {lang} {cmd}", flush=True)
    start_time = time.time()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in proc.stdout:
        if line.startswith(f"{lang}: "):
            print(line, end="", flush=True)
        else:
            print(f"{lang}: {line}", end="", flush=True)
    proc.wait()
    elapsed = time.time() - start_time
    print(f"Finished: {lang} Took: {elapsed:.1f}s", flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run ngrams extraction in parallel")
    parser.add_argument("--alphabet", required=True, help="Alphabet, e.g. latin, cyrillic, arabic")
    parser.add_argument("--languages", required=True, help="Comma-separated language codes, e.g. en,fr,de")
    parser.add_argument("--skipwords", type=str, default=None, help="Skipwords file, e.g. ../wikidata/derived/topwords-en.txt/100")
    parser.add_argument("--parallelism", type=int, default=4, help="Number of parallel workers (default 4)")
    args = parser.parse_args()

    alphabet = args.alphabet
    langs = args.languages.split(",")

    with ProcessPoolExecutor(max_workers=args.parallelism) as executor:
        list(executor.map(partial(run_java, alphabet=alphabet, skipwords=args.skipwords), langs))
