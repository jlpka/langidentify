// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Ad-hoc language detection of a single phrase from the command line.
//!
//! Usage:
//!     cargo run --bin adhoc -- --languages efigs --phrase "this is a test sentence"
//!     cargo run --bin adhoc -- --languages europe_west_common --phrase "Bonjour le monde"

use langidentify::{Detector, Language};
use langidentify_eval::common::{load_model, ModelVariant};
use std::process;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (languages_str, phrase, variant) = parse_args(&args);

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

    let mut detector = Detector::new(model.clone());
    let result = detector.detect(&phrase);
    let results = detector.results();

    println!("Result: {} ({:?})", result.iso_code(), result);
    println!("Gap:    {:.4}", results.gap);
    println!();

    let langs = model.languages();
    let scores = &results.scores;
    let total = &results.total_scores;

    // Sort by total score descending.
    let mut order: Vec<usize> = (0..langs.len()).collect();
    order.sort_by(|&a, &b| total[b].partial_cmp(&total[a]).unwrap());

    println!(
        "{:>6}  {:>10}  {:>5}  {:>10}  {:>7}  {:>10}",
        "lang", "ngram", "hits", "tw", "tw_hits", "total"
    );
    println!(
        "{:>6}  {:>10}  {:>5}  {:>10}  {:>7}  {:>10}",
        "----", "-----", "----", "--", "------", "-----"
    );
    for li in order {
        println!(
            "{:>6}  {:>10.3}  {:>5}  {:>10.3}  {:>7}  {:>10.3}",
            langs[li].iso_code(),
            scores.ngram_scores[li],
            scores.ngram_hits_per_lang[li],
            scores.tw_scores[li],
            scores.tw_hits_per_lang[li],
            total[li],
        );
    }
}

fn parse_args(args: &[String]) -> (String, String, ModelVariant) {
    let mut languages = None;
    let mut phrase = None;
    let mut variant = ModelVariant::Best;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--languages" => {
                i += 1;
                languages = Some(args[i].clone());
            }
            "--phrase" => {
                i += 1;
                phrase = Some(args[i].clone());
            }
            "--model" => {
                i += 1;
                variant = ModelVariant::from_str(&args[i]);
            }
            "--help" | "-h" => {
                eprintln!("Usage: adhoc --languages <langs> --phrase <text> [--model lite|full|best]");
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
    let phrase = phrase.unwrap_or_else(|| {
        eprintln!("--phrase is required");
        process::exit(1);
    });
    (languages, phrase, variant)
}
