// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Fast, accurate language detection using n-gram and topword scoring.
//!
//! # Quick Start
//!
//! ```no_run
//! use langidentify::{Language, Model, Detector};
//! use std::sync::Arc;
//!
//! let languages = Language::from_comma_separated("en,fr,de,es").unwrap();
//! let model = Arc::new(Model::load_from_path("./models/lite", &languages).unwrap());
//! let mut detector = Detector::new(model);
//! assert_eq!(Language::French, detector.detect("Bonjour le monde"));
//! ```

pub mod accent_remover;
pub mod alphabet;
pub mod detector;
pub mod language;
pub mod model;
pub mod model_loader;
pub mod ngram_table;
pub mod word_segmenter;

// Re-export key types at crate root
pub use alphabet::Alphabet;
pub use detector::{Detector, Results, Scores};
pub use language::Language;
pub use model::Model;
