// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Full model data for langidentify language detection.
//!
//! This crate embeds the full (high-accuracy) model files at compile time using `include_bytes!`.
//! The model data is accessed via the [`resolve`] function.

include!(concat!(env!("OUT_DIR"), "/models.rs"));
