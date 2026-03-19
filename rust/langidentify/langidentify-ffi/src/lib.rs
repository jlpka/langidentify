// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! C FFI bindings for the langidentify language detection library.
//!
//! All strings are passed as `(const char*, size_t)` pairs and must be valid UTF-8.
//! Opaque types (`LangidModel`, `LangidDetector`) are heap-allocated and must be
//! freed with the corresponding `_free` function.

use langidentify::{Detector, Language, Model};
use std::panic::catch_unwind;
use std::sync::Arc;

// ========================================================================
// Error codes
// ========================================================================

pub const LANGID_OK: i32 = 0;
pub const LANGID_ERR_NULL: i32 = -1;
pub const LANGID_ERR_UTF8: i32 = -2;
pub const LANGID_ERR_LOAD: i32 = -3;
pub const LANGID_ERR_PARAM: i32 = -4;
pub const LANGID_ERR_PANIC: i32 = -5;

// ========================================================================
// Opaque wrapper types
// ========================================================================

pub struct LangidModel {
    inner: Arc<Model>,
}

pub struct LangidDetector {
    inner: Detector,
}

// ========================================================================
// Helpers
// ========================================================================

/// Converts a (ptr, len) pair to a &str, returning LANGID_ERR_NULL or
/// LANGID_ERR_UTF8 on failure.
unsafe fn ptr_len_to_str<'a>(ptr: *const u8, len: usize) -> Result<&'a str, i32> {
    if ptr.is_null() {
        return Err(LANGID_ERR_NULL);
    }
    let bytes = unsafe { std::slice::from_raw_parts(ptr, len) };
    std::str::from_utf8(bytes).map_err(|_| LANGID_ERR_UTF8)
}

/// Writes an ISO code (static &str) to out-params.
unsafe fn write_iso(
    iso: &'static str,
    out_iso: *mut *const u8,
    out_iso_len: *mut usize,
) {
    if !out_iso.is_null() {
        unsafe { *out_iso = iso.as_ptr() };
    }
    if !out_iso_len.is_null() {
        unsafe { *out_iso_len = iso.len() };
    }
}

// ========================================================================
// Model lifecycle
// ========================================================================

/// Loads a model from a filesystem directory.
///
/// `dir_path` + `dir_path_len`: path to the model directory (UTF-8).
/// `languages_csv` + `languages_csv_len`: comma-separated language codes (UTF-8).
/// `min_log_prob`, `tw_min_log_prob`, `cj_min_log_prob`: tuning parameters (pass 0.0 for defaults).
/// On success, `*out_model` is set to a new model handle.
#[no_mangle]
pub unsafe extern "C" fn langid_model_load_path(
    dir_path: *const u8,
    dir_path_len: usize,
    languages_csv: *const u8,
    languages_csv_len: usize,
    min_log_prob: f64,
    tw_min_log_prob: f64,
    cj_min_log_prob: f64,
    out_model: *mut *mut LangidModel,
) -> i32 {
    if out_model.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(|| {
        let dir = unsafe { ptr_len_to_str(dir_path, dir_path_len) }?;
        let csv = unsafe { ptr_len_to_str(languages_csv, languages_csv_len) }?;
        let languages = Language::from_comma_separated(csv).map_err(|_| LANGID_ERR_PARAM)?;
        let model = Model::load_from_path_with_params(
            dir,
            &languages,
            min_log_prob,
            tw_min_log_prob,
            cj_min_log_prob,
        )
        .map_err(|_| LANGID_ERR_LOAD)?;
        Ok(Box::into_raw(Box::new(LangidModel {
            inner: Arc::new(model),
        })))
    });
    match result {
        Ok(Ok(ptr)) => {
            unsafe { *out_model = ptr };
            LANGID_OK
        }
        Ok(Err(code)) => code,
        Err(_) => LANGID_ERR_PANIC,
    }
}

/// Loads the embedded lite model (requires the `lite` feature).
///
/// `languages_csv` + `languages_csv_len`: comma-separated language codes.
/// On success, `*out_model` is set.
#[cfg(feature = "lite")]
#[no_mangle]
pub unsafe extern "C" fn langid_model_load_lite(
    languages_csv: *const u8,
    languages_csv_len: usize,
    out_model: *mut *mut LangidModel,
) -> i32 {
    if out_model.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(|| {
        let csv = unsafe { ptr_len_to_str(languages_csv, languages_csv_len) }?;
        let languages = Language::from_comma_separated(csv).map_err(|_| LANGID_ERR_PARAM)?;
        let model = Model::load_lite(&languages).map_err(|_| LANGID_ERR_LOAD)?;
        Ok(Box::into_raw(Box::new(LangidModel {
            inner: Arc::new(model),
        })))
    });
    match result {
        Ok(Ok(ptr)) => {
            unsafe { *out_model = ptr };
            LANGID_OK
        }
        Ok(Err(code)) => code,
        Err(_) => LANGID_ERR_PANIC,
    }
}

/// Loads the embedded full model (requires the `full` feature).
#[cfg(feature = "full")]
#[no_mangle]
pub unsafe extern "C" fn langid_model_load_full(
    languages_csv: *const u8,
    languages_csv_len: usize,
    out_model: *mut *mut LangidModel,
) -> i32 {
    if out_model.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(|| {
        let csv = unsafe { ptr_len_to_str(languages_csv, languages_csv_len) }?;
        let languages = Language::from_comma_separated(csv).map_err(|_| LANGID_ERR_PARAM)?;
        let model = Model::load_full(&languages).map_err(|_| LANGID_ERR_LOAD)?;
        Ok(Box::into_raw(Box::new(LangidModel {
            inner: Arc::new(model),
        })))
    });
    match result {
        Ok(Ok(ptr)) => {
            unsafe { *out_model = ptr };
            LANGID_OK
        }
        Ok(Err(code)) => code,
        Err(_) => LANGID_ERR_PANIC,
    }
}

/// Frees a model. Safe to call with NULL.
#[no_mangle]
pub unsafe extern "C" fn langid_model_free(model: *mut LangidModel) {
    if !model.is_null() {
        let _ = catch_unwind(|| {
            drop(unsafe { Box::from_raw(model) });
        });
    }
}

/// Returns the number of languages in the model.
#[no_mangle]
pub unsafe extern "C" fn langid_model_num_languages(model: *const LangidModel) -> usize {
    if model.is_null() {
        return 0;
    }
    unsafe { (*model).inner.num_languages() }
}

/// Gets the ISO code of the language at `idx`.
///
/// On success, `*out_iso` and `*out_iso_len` point to a static string (do not free).
#[no_mangle]
pub unsafe extern "C" fn langid_model_language_iso(
    model: *const LangidModel,
    idx: usize,
    out_iso: *mut *const u8,
    out_iso_len: *mut usize,
) -> i32 {
    if model.is_null() {
        return LANGID_ERR_NULL;
    }
    let m = unsafe { &(*model).inner };
    let langs = m.languages();
    if idx >= langs.len() {
        return LANGID_ERR_PARAM;
    }
    let iso = langs[idx].iso_code();
    unsafe { write_iso(iso, out_iso, out_iso_len) };
    LANGID_OK
}

// ========================================================================
// Detector lifecycle
// ========================================================================

/// Creates a detector from a model.
///
/// The model is reference-counted; the detector keeps it alive even if
/// `langid_model_free` is called on the original handle.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_new(
    model: *const LangidModel,
    out_detector: *mut *mut LangidDetector,
) -> i32 {
    if model.is_null() || out_detector.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(|| {
        let arc = unsafe { (*model).inner.clone() };
        let detector = Detector::new(arc);
        Box::into_raw(Box::new(LangidDetector { inner: detector }))
    });
    match result {
        Ok(ptr) => {
            unsafe { *out_detector = ptr };
            LANGID_OK
        }
        Err(_) => LANGID_ERR_PANIC,
    }
}

/// Frees a detector. Safe to call with NULL.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_free(detector: *mut LangidDetector) {
    if !detector.is_null() {
        let _ = catch_unwind(|| {
            drop(unsafe { Box::from_raw(detector) });
        });
    }
}

/// Sets accuracy parameters on the detector. Returns 0 on success, -4 on invalid params.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_set_accuracy(
    detector: *mut LangidDetector,
    min_ngram: usize,
    stop_if_ngram_covered: usize,
    max_ngram: usize,
    use_topwords: i32,
) -> i32 {
    if detector.is_null() {
        return LANGID_ERR_NULL;
    }
    let d = unsafe { &mut (*detector).inner };
    if d.set_accuracy_params(min_ngram, stop_if_ngram_covered, max_ngram, use_topwords != 0) {
        LANGID_OK
    } else {
        LANGID_ERR_PARAM
    }
}

// ========================================================================
// One-shot detection
// ========================================================================

/// Detects the language of `text`.
///
/// On success, `*out_iso` and `*out_iso_len` point to a static ISO code string.
#[no_mangle]
pub unsafe extern "C" fn langid_detect(
    detector: *mut LangidDetector,
    text: *const u8,
    text_len: usize,
    out_iso: *mut *const u8,
    out_iso_len: *mut usize,
) -> i32 {
    if detector.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let s = unsafe { ptr_len_to_str(text, text_len) }?;
        let d = unsafe { &mut (*detector).inner };
        let lang = d.detect(s);
        Ok(lang.iso_code())
    }));
    match result {
        Ok(Ok(iso)) => {
            unsafe { write_iso(iso, out_iso, out_iso_len) };
            LANGID_OK
        }
        Ok(Err(code)) => code,
        Err(_) => LANGID_ERR_PANIC,
    }
}

/// Detects the language with per-language boosts applied.
///
/// `boosts` must point to an array of `boosts_len` doubles, where `boosts_len`
/// equals `langid_model_num_languages()`.
#[no_mangle]
pub unsafe extern "C" fn langid_detect_with_boosts(
    detector: *mut LangidDetector,
    text: *const u8,
    text_len: usize,
    boosts: *const f64,
    boosts_len: usize,
    out_iso: *mut *const u8,
    out_iso_len: *mut usize,
) -> i32 {
    if detector.is_null() || boosts.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let s = unsafe { ptr_len_to_str(text, text_len) }?;
        let boost_slice = unsafe { std::slice::from_raw_parts(boosts, boosts_len) };
        let d = unsafe { &mut (*detector).inner };
        let lang = d.detect_with_boosts(s, boost_slice);
        Ok(lang.iso_code())
    }));
    match result {
        Ok(Ok(iso)) => {
            unsafe { write_iso(iso, out_iso, out_iso_len) };
            LANGID_OK
        }
        Ok(Err(code)) => code,
        Err(_) => LANGID_ERR_PANIC,
    }
}

// ========================================================================
// Incremental (add_text) API
// ========================================================================

/// Clears accumulated scores for a fresh detection.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_clear(detector: *mut LangidDetector) {
    if !detector.is_null() {
        let _ = catch_unwind(std::panic::AssertUnwindSafe(|| {
            unsafe { (*detector).inner.clear_scores() };
        }));
    }
}

/// Adds text to the detection accumulator.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_add_text(
    detector: *mut LangidDetector,
    text: *const u8,
    text_len: usize,
) -> i32 {
    if detector.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let s = unsafe { ptr_len_to_str(text, text_len) }?;
        unsafe { (*detector).inner.add_text(s) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => LANGID_OK,
        Ok(Err(code)) => code,
        Err(_) => LANGID_ERR_PANIC,
    }
}

/// Computes the result from all text added since the last clear.
///
/// On success, `*out_iso` and `*out_iso_len` point to a static ISO code string.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_compute_result(
    detector: *mut LangidDetector,
    out_iso: *mut *const u8,
    out_iso_len: *mut usize,
) -> i32 {
    if detector.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let d = unsafe { &mut (*detector).inner };
        d.compute_result().iso_code()
    }));
    match result {
        Ok(iso) => {
            unsafe { write_iso(iso, out_iso, out_iso_len) };
            LANGID_OK
        }
        Err(_) => LANGID_ERR_PANIC,
    }
}

/// Computes the result with boosts from all text added since the last clear.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_compute_result_with_boosts(
    detector: *mut LangidDetector,
    boosts: *const f64,
    boosts_len: usize,
    out_iso: *mut *const u8,
    out_iso_len: *mut usize,
) -> i32 {
    if detector.is_null() || boosts.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let boost_slice = unsafe { std::slice::from_raw_parts(boosts, boosts_len) };
        let d = unsafe { &mut (*detector).inner };
        d.compute_result_with_boosts(boost_slice).iso_code()
    }));
    match result {
        Ok(iso) => {
            unsafe { write_iso(iso, out_iso, out_iso_len) };
            LANGID_OK
        }
        Err(_) => LANGID_ERR_PANIC,
    }
}

// ========================================================================
// Results access
// ========================================================================

/// Gets the confidence gap from the last detection.
///
/// 0.0 = ambiguous, higher = more confident. Written to `*out_gap`.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_gap(
    detector: *const LangidDetector,
    out_gap: *mut f64,
) -> i32 {
    if detector.is_null() || out_gap.is_null() {
        return LANGID_ERR_NULL;
    }
    unsafe { *out_gap = (*detector).inner.results().gap };
    LANGID_OK
}

/// Gets the number of words scored in the last detection.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_num_words(
    detector: *const LangidDetector,
) -> usize {
    if detector.is_null() {
        return 0;
    }
    unsafe { (*detector).inner.results().scores.num_words }
}

/// Copies the total per-language scores from the last detection into `out_scores`.
///
/// `out_scores` must point to a buffer of at least `out_scores_len` doubles.
/// `out_scores_len` should equal `langid_model_num_languages()`.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_total_scores(
    detector: *const LangidDetector,
    out_scores: *mut f64,
    out_scores_len: usize,
) -> i32 {
    if detector.is_null() || out_scores.is_null() {
        return LANGID_ERR_NULL;
    }
    let results = unsafe { (*detector).inner.results() };
    let n = out_scores_len.min(results.total_scores.len());
    let dst = unsafe { std::slice::from_raw_parts_mut(out_scores, n) };
    dst.copy_from_slice(&results.total_scores[..n]);
    LANGID_OK
}

/// Gets the ISO code of the detected language from the last detection.
///
/// Equivalent to calling detect/compute_result and reading the result, but
/// does not re-run detection -- just reads the cached result.
#[no_mangle]
pub unsafe extern "C" fn langid_detector_result_iso(
    detector: *const LangidDetector,
    out_iso: *mut *const u8,
    out_iso_len: *mut usize,
) -> i32 {
    if detector.is_null() {
        return LANGID_ERR_NULL;
    }
    let iso = unsafe { (*detector).inner.results().result.iso_code() };
    unsafe { write_iso(iso, out_iso, out_iso_len) };
    LANGID_OK
}

// ========================================================================
// Boost helpers
// ========================================================================

/// Builds a boost array with a single language boosted.
///
/// `lang_iso` + `lang_iso_len`: ISO code of the language to boost.
/// `boost`: boost value (e.g. 0.08).
/// `out_boosts`: caller-allocated array of `out_boosts_len` doubles (filled with
/// zeros except the boosted language).
/// `out_boosts_len` should equal `langid_model_num_languages()`.
#[no_mangle]
pub unsafe extern "C" fn langid_model_build_boost_single(
    model: *const LangidModel,
    lang_iso: *const u8,
    lang_iso_len: usize,
    boost: f64,
    out_boosts: *mut f64,
    out_boosts_len: usize,
) -> i32 {
    if model.is_null() || out_boosts.is_null() {
        return LANGID_ERR_NULL;
    }
    let result = catch_unwind(|| {
        let iso = unsafe { ptr_len_to_str(lang_iso, lang_iso_len) }?;
        let lang = Language::from_string(iso);
        if lang == Language::Unknown {
            return Err(LANGID_ERR_PARAM);
        }
        let m = unsafe { &(*model).inner };
        let boosts = m.build_boost_single(lang, boost);
        let dst = unsafe { std::slice::from_raw_parts_mut(out_boosts, out_boosts_len) };
        let n = out_boosts_len.min(boosts.len());
        dst[..n].copy_from_slice(&boosts[..n]);
        for i in n..out_boosts_len {
            dst[i] = 0.0;
        }
        Ok(())
    });
    match result {
        Ok(Ok(())) => LANGID_OK,
        Ok(Err(code)) => code,
        Err(_) => LANGID_ERR_PANIC,
    }
}
