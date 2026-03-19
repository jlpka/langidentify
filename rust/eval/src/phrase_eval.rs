// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Phrase-level evaluation tool for langidentify.
//!
//! Reads a directory of test files (one phrase per line, named {lang_code}.txt)
//! and evaluates detection accuracy per language.
//!
//! Usage:
//!     cargo run --bin phrase-eval -- --languages efigs --testdir /path/to/phrases
//!     cargo run --bin phrase-eval -- --languages europe_west_common,cjk --testdir /path/to/phrases --misses

use langidentify::accent_remover::AccentRemover;
use langidentify::{Detector, Language};
use langidentify_eval::common::{load_model, test_file_for_lang, ModelVariant};
use std::collections::{HashMap, HashSet};
use std::process;

fn is_equivalent(expected: Language, detected: Language) -> bool {
    if expected == detected {
        return true;
    }
    let is_chinese =
        |l: Language| l == Language::ChineseSimplified || l == Language::ChineseTraditional;
    is_chinese(expected) && is_chinese(detected)
}

/// Known cross-detection pairs: (source, target) -> target iso code.
fn known_cross_note(expected: Language, languages: &[Language]) -> &'static str {
    const PAIRS: &[(Language, Language, &str)] = &[
        (Language::Malay, Language::Indonesian, " (id, known cross-detection)"),
        (Language::Afrikaans, Language::Dutch, " (nl, known cross-detection)"),
        (Language::Nynorsk, Language::Norwegian, " (no, known cross-detection)"),
    ];
    for &(src, tgt, note) in PAIRS {
        if expected == src && languages.contains(&tgt) {
            return note;
        }
    }
    ""
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (languages_str, testdir, misses, remove_accents, variant) = parse_args(&args);

    let languages = match Language::from_comma_separated(&languages_str) {
        Ok(langs) => langs,
        Err(e) => {
            eprintln!("Invalid languages: {}", e);
            process::exit(1);
        }
    };

    let model = load_model(variant, &languages).unwrap_or_else(|e| {
        eprintln!("Failed to load model: {}", e);
        process::exit(1);
    });

    let mut detector = Detector::new(model);
    let accent_remover = if remove_accents {
        Some(AccentRemover::default())
    } else {
        None
    };

    // Deduplicate test files (zh-hans and zh-hant both map to zh.txt).
    let mut seen_files = HashSet::new();
    let mut lang_file_pairs = Vec::new();
    let mut sorted_langs: Vec<Language> = languages.clone();
    sorted_langs.sort_by_key(|l| l.iso_code());
    for &lang in &sorted_langs {
        let filename = test_file_for_lang(lang);
        if seen_files.insert(filename.clone()) {
            lang_file_pairs.push((lang, filename));
        }
    }

    let mut overall_correct = 0usize;
    let mut overall_total = 0usize;
    let mut per_lang_stats: Vec<(String, usize, usize, f64)> = Vec::new();

    for (expected_lang, filename) in &lang_file_pairs {
        let filepath = format!("{}/{}", testdir, filename);
        let content = match std::fs::read_to_string(&filepath) {
            Ok(c) => c,
            Err(_) => {
                eprintln!(
                    "WARNING: {} not found, skipping {}",
                    filepath,
                    expected_lang.iso_code()
                );
                continue;
            }
        };

        let mut correct = 0usize;
        let mut total = 0usize;
        let mut skipped = 0usize;
        let mut cross_detections: HashMap<String, usize> = HashMap::new();

        for line in content.lines() {
            let phrase = line.trim_end_matches('\n');
            if phrase.trim().is_empty() {
                continue;
            }

            let text = if let Some(ref remover) = accent_remover {
                remover.remove(phrase).into_owned()
            } else {
                phrase.to_string()
            };

            let detected = detector.detect(&text);

            if detected == Language::Unknown {
                skipped += 1;
                continue;
            }

            total += 1;
            if is_equivalent(*expected_lang, detected) {
                correct += 1;
            } else {
                *cross_detections
                    .entry(detected.iso_code().to_string())
                    .or_insert(0) += 1;
                if misses {
                    let results = detector.results();
                    let display = if phrase.len() <= 80 {
                        phrase.to_string()
                    } else {
                        format!("{}...", &phrase[..80])
                    };
                    eprintln!(
                        "MISS [expected={} detected={}] gap={:.3} \"{}\"",
                        expected_lang.iso_code(),
                        detected.iso_code(),
                        results.gap,
                        display,
                    );
                }
            }
        }

        let iso = if expected_lang == &Language::ChineseSimplified
            || expected_lang == &Language::ChineseTraditional
        {
            "zh".to_string()
        } else {
            expected_lang.iso_code().to_string()
        };

        let pct = if total > 0 {
            100.0 * correct as f64 / total as f64
        } else {
            0.0
        };

        let mut cross_str = String::new();
        if !cross_detections.is_empty() {
            let mut ranked: Vec<_> = cross_detections.iter().collect();
            ranked.sort_by(|a, b| b.1.cmp(a.1));
            cross_str = format!(
                " [{}]",
                ranked
                    .iter()
                    .map(|(k, v)| format!("{}:{}", k, v))
                    .collect::<Vec<_>>()
                    .join(", ")
            );
        }

        let cross_note = known_cross_note(*expected_lang, &languages);

        eprintln!(
            "{}: {}/{} correct ({:.1}%), {} skipped{}{}",
            iso, correct, total, pct, skipped, cross_str, cross_note,
        );

        per_lang_stats.push((iso, correct, total, pct));
        overall_correct += correct;
        overall_total += total;
    }

    let overall_pct = if overall_total > 0 {
        100.0 * overall_correct as f64 / overall_total as f64
    } else {
        0.0
    };

    let lang_summary: String = per_lang_stats
        .iter()
        .map(|(iso, c, t, p)| format!("{}:{}/{}:{:.1}%", iso, c, t, p))
        .collect::<Vec<_>>()
        .join(" ");

    eprintln!(
        "Overall: {}/{} correct ({:.1}%) {}",
        overall_correct, overall_total, overall_pct, lang_summary,
    );
}

fn parse_args(args: &[String]) -> (String, String, bool, bool, ModelVariant) {
    let mut languages = None;
    let mut testdir = None;
    let mut misses = false;
    let mut remove_accents = false;
    let mut variant = ModelVariant::Best;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--languages" => {
                i += 1;
                languages = Some(args[i].clone());
            }
            "--testdir" => {
                i += 1;
                testdir = Some(args[i].clone());
            }
            "--misses" => misses = true,
            "--removeaccents" => remove_accents = true,
            "--model" => {
                i += 1;
                variant = ModelVariant::from_str(&args[i]);
            }
            "--help" | "-h" => {
                eprintln!(
                    "Usage: phrase-eval --languages <langs> --testdir <path> \
                     [--misses] [--removeaccents] [--model lite|full|best]"
                );
                process::exit(0);
            }
            _ => {
                eprintln!("Unknown argument: {}", args[i]);
                process::exit(1);
            }
        }
        i += 1;
    }
    let languages = languages.unwrap_or_else(|| {
        eprintln!("--languages is required");
        process::exit(1);
    });
    let testdir = testdir.unwrap_or_else(|| {
        eprintln!("--testdir is required");
        process::exit(1);
    });
    (languages, testdir, misses, remove_accents, variant)
}
