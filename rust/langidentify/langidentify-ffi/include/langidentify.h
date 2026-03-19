/*
 * langidentify - Fast, high-accuracy language detection.
 *
 * Copyright 2026 Jeremy Lilley
 * Licensed under the Apache License, Version 2.0
 *
 * C interface for the langidentify Rust library. All strings are passed
 * as (const char*, size_t) pairs and must be valid UTF-8. Returned ISO
 * code pointers are static and must NOT be freed by the caller.
 *
 * Thread safety:
 *   - LangidModel is immutable after creation and safe to share across threads.
 *   - LangidDetector is NOT thread-safe. Use one per thread.
 *
 * Usage:
 *   LangidModel* model = NULL;
 *   langid_model_load_path("./models/lite", 14, "en,fr,de,es", 11,
 *                          0.0, 0.0, 0.0, &model);
 *   LangidDetector* det = NULL;
 *   langid_detector_new(model, &det);
 *
 *   const char* iso; size_t iso_len;
 *   langid_detect(det, "Bonjour le monde", 16, &iso, &iso_len);
 *   // iso now points to "fr", iso_len == 2
 *
 *   langid_detector_free(det);
 *   langid_model_free(model);
 */

#ifndef LANGIDENTIFY_H
#define LANGIDENTIFY_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Error codes */
#define LANGID_OK          0
#define LANGID_ERR_NULL   -1
#define LANGID_ERR_UTF8   -2
#define LANGID_ERR_LOAD   -3
#define LANGID_ERR_PARAM  -4
#define LANGID_ERR_PANIC  -5

/* Opaque types */
typedef struct LangidModel LangidModel;
typedef struct LangidDetector LangidDetector;

/* ======================================================================
 * Model lifecycle
 * ====================================================================== */

/**
 * Loads a model from a filesystem directory.
 *
 * @param dir_path        Path to model directory (UTF-8, not null-terminated).
 * @param dir_path_len    Length of dir_path in bytes.
 * @param languages_csv   Comma-separated language codes (e.g. "en,fr,de").
 * @param languages_csv_len Length of languages_csv in bytes.
 * @param min_log_prob    Ngram log-probability floor (0.0 for default).
 * @param tw_min_log_prob Topword log-probability floor (0.0 for default).
 * @param cj_min_log_prob CJ classifier floor (0.0 for default).
 * @param out_model       On success, receives the new model handle.
 * @return LANGID_OK on success, negative error code on failure.
 */
int32_t langid_model_load_path(
    const char* dir_path, size_t dir_path_len,
    const char* languages_csv, size_t languages_csv_len,
    double min_log_prob, double tw_min_log_prob, double cj_min_log_prob,
    LangidModel** out_model);

/**
 * Loads the embedded lite model (requires library built with "lite" feature).
 */
int32_t langid_model_load_lite(
    const char* languages_csv, size_t languages_csv_len,
    LangidModel** out_model);

/**
 * Loads the embedded full model (requires library built with "full" feature).
 */
int32_t langid_model_load_full(
    const char* languages_csv, size_t languages_csv_len,
    LangidModel** out_model);

/** Frees a model. Safe to call with NULL. */
void langid_model_free(LangidModel* model);

/** Returns the number of languages in the model, or 0 if model is NULL. */
size_t langid_model_num_languages(const LangidModel* model);

/**
 * Gets the ISO 639-1 code of the language at index `idx`.
 *
 * @param out_iso     Receives a pointer to a static string (do NOT free).
 * @param out_iso_len Receives the length of the ISO code.
 */
int32_t langid_model_language_iso(
    const LangidModel* model, size_t idx,
    const char** out_iso, size_t* out_iso_len);

/* ======================================================================
 * Detector lifecycle
 * ====================================================================== */

/**
 * Creates a detector from a model.
 *
 * The model is reference-counted internally; the detector keeps it alive
 * even after langid_model_free() is called on the original handle.
 */
int32_t langid_detector_new(
    const LangidModel* model,
    LangidDetector** out_detector);

/** Frees a detector. Safe to call with NULL. */
void langid_detector_free(LangidDetector* detector);

/**
 * Sets accuracy parameters.
 *
 * @param use_topwords  0 to disable topwords, nonzero to enable.
 * @return LANGID_OK or LANGID_ERR_PARAM if parameters are invalid.
 */
int32_t langid_detector_set_accuracy(
    LangidDetector* detector,
    size_t min_ngram,
    size_t stop_if_ngram_covered,
    size_t max_ngram,
    int32_t use_topwords);

/* ======================================================================
 * One-shot detection
 * ====================================================================== */

/**
 * Detects the language of a text string.
 *
 * @param text        Text to detect (UTF-8, not null-terminated).
 * @param text_len    Length of text in bytes.
 * @param out_iso     Receives a pointer to the ISO code (static, do NOT free).
 * @param out_iso_len Receives the length of the ISO code.
 */
int32_t langid_detect(
    LangidDetector* detector,
    const char* text, size_t text_len,
    const char** out_iso, size_t* out_iso_len);

/**
 * Detects language with per-language boosts.
 *
 * @param boosts      Array of boost values, one per language in the model.
 * @param boosts_len  Length of boosts array (must equal langid_model_num_languages).
 */
int32_t langid_detect_with_boosts(
    LangidDetector* detector,
    const char* text, size_t text_len,
    const double* boosts, size_t boosts_len,
    const char** out_iso, size_t* out_iso_len);

/* ======================================================================
 * Incremental (add_text) API
 * ====================================================================== */

/** Clears accumulated scores for a fresh detection. */
void langid_detector_clear(LangidDetector* detector);

/** Adds text to the detection accumulator. */
int32_t langid_detector_add_text(
    LangidDetector* detector,
    const char* text, size_t text_len);

/**
 * Computes the result from all text added since the last clear.
 *
 * @param out_iso     Receives the ISO code of the detected language.
 * @param out_iso_len Receives the length of the ISO code.
 */
int32_t langid_detector_compute_result(
    LangidDetector* detector,
    const char** out_iso, size_t* out_iso_len);

/** Computes result with boosts from all text added since the last clear. */
int32_t langid_detector_compute_result_with_boosts(
    LangidDetector* detector,
    const double* boosts, size_t boosts_len,
    const char** out_iso, size_t* out_iso_len);

/* ======================================================================
 * Results access
 * ====================================================================== */

/** Gets the confidence gap from the last detection (0.0=ambiguous, higher=confident). */
int32_t langid_detector_gap(
    const LangidDetector* detector,
    double* out_gap);

/** Gets the number of words scored in the last detection. */
size_t langid_detector_num_words(const LangidDetector* detector);

/**
 * Copies per-language total scores from the last detection.
 *
 * @param out_scores     Caller-allocated array of doubles.
 * @param out_scores_len Size of the array (should equal langid_model_num_languages).
 */
int32_t langid_detector_total_scores(
    const LangidDetector* detector,
    double* out_scores, size_t out_scores_len);

/** Gets the ISO code of the cached result (does not re-run detection). */
int32_t langid_detector_result_iso(
    const LangidDetector* detector,
    const char** out_iso, size_t* out_iso_len);

/* ======================================================================
 * Boost helpers
 * ====================================================================== */

/**
 * Fills a boost array with zeros except for one boosted language.
 *
 * @param lang_iso       ISO code of the language to boost.
 * @param lang_iso_len   Length of lang_iso.
 * @param boost          Boost value (e.g. 0.08).
 * @param out_boosts     Caller-allocated array (filled on success).
 * @param out_boosts_len Size of out_boosts (should equal langid_model_num_languages).
 */
int32_t langid_model_build_boost_single(
    const LangidModel* model,
    const char* lang_iso, size_t lang_iso_len,
    double boost,
    double* out_boosts, size_t out_boosts_len);

#ifdef __cplusplus
}
#endif

#endif /* LANGIDENTIFY_H */
