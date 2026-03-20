// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Lite model data (part B) for langidentify language detection.
//!
//! The lite model data is split across two crates (`-a` and `-b`) to stay
//! within the crates.io 10 MB per-crate size limit. The split is by language
//! family: part A contains European Latin-script languages (plus skipwords and
//! CJ classifier data), and part B contains everything else (African, Asian,
//! Cyrillic, Arabic, etc.).
//!
//! Users should depend on `langidentify-models-lite` (the facade crate) rather
//! than on `-a` or `-b` directly.

include!(concat!(env!("OUT_DIR"), "/models.rs"));
