/*
 * useffi.c — Example of using the langidentify C FFI from C.
 *
 * Performs a single ad-hoc language detection and prints the result
 * with per-language score breakdown.
 *
 * Building (from the rust/ directory):
 *
 *   # 1. Build the FFI library (release, with embedded lite model):
 *   cd langidentify
 *   cargo build --release -p langidentify-ffi --features lite
 *
 *   # 2. Compile and link (dynamic):
 *   cd ../eval/src
 *   cc -O2 useffi.c \
 *       -I../../langidentify/langidentify-ffi/include \
 *       -L../../langidentify/target/release \
 *       -llangidentify_ffi \
 *       -o useffi
 *
 *   # 3. Run (macOS — set dylib search path):
 *   DYLD_LIBRARY_PATH=../../langidentify/target/release \
 *       ./useffi --languages efigs --phrase "Bonjour le monde"
 *
 *   # On Linux, use LD_LIBRARY_PATH instead of DYLD_LIBRARY_PATH.
 *
 *   # To link statically instead (no runtime library path needed):
 *   cc -O2 useffi.c \
 *       -I../../langidentify/langidentify-ffi/include \
 *       ../../langidentify/target/release/liblangidentify_ffi.a \
 *       -lpthread -ldl -lm \
 *       -o useffi
 *
 * Expected output:
 *
 *   Result: fr
 *   Gap:    0.2186
 *   Words:  3
 *
 *     lang       total
 *     ----       -----
 *       fr      -4.832
 *       en      -6.195
 *       it      -6.370
 *       es      -6.455
 *       de      -7.039
 */

#include "langidentify.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_LANGS 128

static void usage(const char* prog) {
    fprintf(stderr,
        "Usage: %s --languages <langs> --phrase <text> [--modeldir <path>]\n"
        "\n"
        "  --languages  Comma-separated language codes (e.g. efigs, en,fr,de)\n"
        "  --phrase     Text to detect\n"
        "  --modeldir   Path to model data directory (optional; uses embedded\n"
        "               lite model if omitted, requires --features lite)\n",
        prog);
    exit(1);
}

/* Simple insertion sort for score indices (descending by score). */
static void sort_descending(size_t* order, const double* scores, size_t n) {
    size_t i, j;
    for (i = 1; i < n; i++) {
        size_t key = order[i];
        double key_score = scores[key];
        j = i;
        while (j > 0 && scores[order[j - 1]] < key_score) {
            order[j] = order[j - 1];
            j--;
        }
        order[j] = key;
    }
}

int main(int argc, char* argv[]) {
    const char* languages = NULL;
    const char* phrase = NULL;
    const char* modeldir = NULL;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--languages") == 0 && i + 1 < argc) {
            languages = argv[++i];
        } else if (strcmp(argv[i], "--phrase") == 0 && i + 1 < argc) {
            phrase = argv[++i];
        } else if (strcmp(argv[i], "--modeldir") == 0 && i + 1 < argc) {
            modeldir = argv[++i];
        } else {
            usage(argv[0]);
        }
    }
    if (!languages || !phrase) {
        usage(argv[0]);
    }

    /* Load model */
    LangidModel* model = NULL;
    int32_t rc;

    if (modeldir) {
        rc = langid_model_load_path(
            modeldir, strlen(modeldir),
            languages, strlen(languages),
            0.0, 0.0, 0.0,
            &model);
    } else {
        rc = langid_model_load_lite(
            languages, strlen(languages),
            &model);
    }

    if (rc != LANGID_OK) {
        fprintf(stderr, "Failed to load model (error %d)\n", rc);
        if (!modeldir) {
            fprintf(stderr, "Hint: build with --features lite, or pass --modeldir\n");
        }
        return 1;
    }

    /* Create detector */
    LangidDetector* detector = NULL;
    rc = langid_detector_new(model, &detector);
    if (rc != LANGID_OK) {
        fprintf(stderr, "Failed to create detector (error %d)\n", rc);
        langid_model_free(model);
        return 1;
    }

    /* Detect */
    const char* iso = NULL;
    size_t iso_len = 0;
    rc = langid_detect(detector, phrase, strlen(phrase), &iso, &iso_len);
    if (rc != LANGID_OK) {
        fprintf(stderr, "Detection failed (error %d)\n", rc);
        langid_detector_free(detector);
        langid_model_free(model);
        return 1;
    }

    double gap = 0.0;
    langid_detector_gap(detector, &gap);
    size_t num_words = langid_detector_num_words(detector);

    printf("Result: %.*s\n", (int)iso_len, iso);
    printf("Gap:    %.4f\n", gap);
    printf("Words:  %zu\n\n", num_words);

    /* Print per-language scores */
    size_t num_langs = langid_model_num_languages(model);
    double scores[MAX_LANGS];
    size_t order[MAX_LANGS];

    if (num_langs > MAX_LANGS) num_langs = MAX_LANGS;
    langid_detector_total_scores(detector, scores, num_langs);

    for (i = 0; i < (int)num_langs; i++) order[i] = i;
    sort_descending(order, scores, num_langs);

    printf("%6s  %10s\n", "lang", "total");
    printf("%6s  %10s\n", "----", "-----");
    for (i = 0; i < (int)num_langs; i++) {
        size_t li = order[i];
        const char* lang_iso = NULL;
        size_t lang_iso_len = 0;
        langid_model_language_iso(model, li, &lang_iso, &lang_iso_len);
        printf("%6.*s  %10.3f\n", (int)lang_iso_len, lang_iso, scores[li]);
    }

    /* Cleanup */
    langid_detector_free(detector);
    langid_model_free(model);
    return 0;
}
