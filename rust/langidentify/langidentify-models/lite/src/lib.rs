// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Lite model data for langidentify language detection.
//!
//! This crate is a facade that re-exports model data from two internal
//! sub-crates (`langidentify-models-lite-a` and `langidentify-models-lite-b`).
//! The data is split across two crates to stay within the crates.io 10 MB
//! per-crate size limit. Part A contains European Latin-script languages
//! (plus skipwords and CJ classifier data), and part B contains everything
//! else (African, Asian, Cyrillic, Arabic, etc.).
//!
//! The model data is accessed via the [`resolve`] function.

/// Resolves a model file name to its embedded data.
///
/// Returns `Some(&[u8])` if the file is found, `None` otherwise.
pub fn resolve(name: &str) -> Option<&'static [u8]> {
    langidentify_models_lite_a::resolve(name)
        .or_else(|| langidentify_models_lite_b::resolve(name))
}

/// Returns `true` if the named model file is available.
pub fn exists(name: &str) -> bool {
    langidentify_models_lite_a::exists(name) || langidentify_models_lite_b::exists(name)
}
