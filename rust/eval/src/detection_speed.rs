// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Benchmark detection throughput (words/s and detections/s).
//!
//! Usage:
//!     cargo run --release --bin detection-speed -- --languages efigs --testdir /path/to/phrases
//!     cargo run --release --bin detection-speed -- --languages efigsnp --testdir /path/to/phrases --duration 60

use langidentify::{Detector, Language};
use langidentify_eval::common::{load_model, load_phrases, ModelVariant};
use std::process;
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (languages_str, testdir, duration_secs, variant) = parse_args(&args);

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

    let phrases = load_phrases(&languages, &testdir);
    if phrases.is_empty() {
        eprintln!("Error: no phrases loaded");
        process::exit(1);
    }

    eprintln!(
        "Detect speed benchmark: {} phrases, {} languages, duration {}s",
        phrases.len(),
        languages.len(),
        duration_secs,
    );

    // Warmup.
    eprintln!("Warmup...");
    for phrase in &phrases {
        detector.detect(phrase);
    }

    // Benchmark loop.
    let duration = std::time::Duration::from_secs(duration_secs);
    let report_interval = std::time::Duration::from_secs(5);
    let bench_start = Instant::now();
    let mut next_report = bench_start + report_interval;

    let mut total_words: u64 = 0;
    let mut total_detections: u64 = 0;
    let mut interval_words: u64 = 0;
    let mut interval_start = bench_start;
    let mut phrase_idx = 0;

    while bench_start.elapsed() < duration {
        detector.detect(&phrases[phrase_idx]);
        let words = detector.results().scores.num_words as u64;
        total_words += words;
        total_detections += 1;
        interval_words += words;

        phrase_idx += 1;
        if phrase_idx >= phrases.len() {
            phrase_idx = 0;
        }

        let now = Instant::now();
        if now >= next_report {
            let interval_sec = interval_start.elapsed().as_secs_f64();
            let m_words_per_sec = interval_words as f64 / interval_sec / 1_000_000.0;
            let ns_per_word = if interval_words > 0 {
                interval_sec * 1e9 / interval_words as f64
            } else {
                0.0
            };
            let total_sec = bench_start.elapsed().as_secs_f64();
            let total_mw_per_sec = total_words as f64 / total_sec / 1_000_000.0;
            eprintln!(
                "  {:.0}s: {:.2} Mwords/s  {:.0} ns/word  ({} phrases, cumulative: {:.2} Mwords/s)",
                total_sec, m_words_per_sec, ns_per_word, total_detections, total_mw_per_sec,
            );
            interval_words = 0;
            interval_start = now;
            next_report = now + report_interval;
        }
    }

    let total_sec = bench_start.elapsed().as_secs_f64();
    let m_words_per_sec = total_words as f64 / total_sec / 1_000_000.0;
    let ns_per_word = if total_words > 0 {
        total_sec * 1e9 / total_words as f64
    } else {
        0.0
    };

    eprintln!();
    eprintln!(
        "Final: {} detections, {} words in {:.1}s",
        total_detections, total_words, total_sec,
    );
    eprintln!("  {:.2} Mwords/s  {:.0} ns/word", m_words_per_sec, ns_per_word);
}

fn parse_args(args: &[String]) -> (String, String, u64, ModelVariant) {
    let mut languages = None;
    let mut testdir = None;
    let mut duration = 30u64;
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
            "--duration" => {
                i += 1;
                duration = args[i].parse().unwrap_or(30);
            }
            "--model" => {
                i += 1;
                variant = ModelVariant::from_str(&args[i]);
            }
            "--help" | "-h" => {
                eprintln!(
                    "Usage: detection-speed --languages <langs> --testdir <path> \
                     [--duration <sec>] [--model lite|full|best]"
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
    (languages, testdir, duration, variant)
}
