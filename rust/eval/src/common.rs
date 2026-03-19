// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

use langidentify::{Language, Model};
use std::io;
use std::sync::Arc;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ModelVariant {
    Lite,
    Full,
    Best,
}

impl ModelVariant {
    pub fn from_str(s: &str) -> ModelVariant {
        match s {
            "lite" => ModelVariant::Lite,
            "full" => ModelVariant::Full,
            _ => ModelVariant::Best,
        }
    }
}

pub fn load_model(variant: ModelVariant, languages: &[Language]) -> io::Result<Arc<Model>> {
    let model = match variant {
        ModelVariant::Lite => Model::load_embedded(
            langidentify_models_lite::resolve,
            languages,
            -12.0,
            -12.0,
            0.0,
        )?,
        ModelVariant::Full => Model::load_embedded(
            langidentify_models_full::resolve,
            languages,
            -15.0,
            -15.0,
            0.0,
        )?,
        ModelVariant::Best => {
            // Try full first, fall back to lite.
            if langidentify_models_full::exists("skipwords.txt") {
                Model::load_embedded(
                    langidentify_models_full::resolve,
                    languages,
                    -15.0,
                    -15.0,
                    0.0,
                )?
            } else {
                Model::load_embedded(
                    langidentify_models_lite::resolve,
                    languages,
                    -12.0,
                    -12.0,
                    0.0,
                )?
            }
        }
    };
    Ok(Arc::new(model))
}

/// Maps a language to its test filename.
pub fn test_file_for_lang(lang: Language) -> String {
    if lang == Language::ChineseSimplified || lang == Language::ChineseTraditional {
        "zh.txt".to_string()
    } else {
        format!("{}.txt", lang.iso_code())
    }
}

/// Loads all phrases from test files into a Vec.
pub fn load_phrases(
    languages: &[Language],
    testdir: &str,
) -> Vec<String> {
    let mut phrases = Vec::new();
    let mut seen_files = std::collections::HashSet::new();

    let mut sorted_langs: Vec<Language> = languages.to_vec();
    sorted_langs.sort_by_key(|l| l.iso_code());

    for &lang in &sorted_langs {
        let filename = test_file_for_lang(lang);
        if !seen_files.insert(filename.clone()) {
            continue;
        }
        let filepath = format!("{}/{}", testdir, filename);
        let content = match std::fs::read_to_string(&filepath) {
            Ok(c) => c,
            Err(_) => {
                eprintln!("Warning: {} not found, skipping {}", filepath, lang.iso_code());
                continue;
            }
        };
        let mut count = 0;
        for line in content.lines() {
            let phrase = line.trim();
            if !phrase.is_empty() {
                phrases.push(phrase.to_string());
                count += 1;
            }
        }
        eprintln!("  loaded {} phrases for {}", count, lang.iso_code());
    }
    phrases
}
