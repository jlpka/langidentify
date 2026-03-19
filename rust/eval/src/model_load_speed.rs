// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

//! Benchmark model loading time and memory consumption.
//!
//! Usage:
//!     cargo run --release --bin model-load-speed -- --languages efigs
//!     cargo run --release --bin model-load-speed -- --languages europe_west_common --iterations 5

use langidentify::{Language, Model};
use langidentify_eval::common::ModelVariant;
use std::alloc::{GlobalAlloc, Layout, System};
use std::process;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

// ========================================================================
// Counting allocator — tracks current and peak heap usage.
// ========================================================================

struct CountingAllocator;

static ALLOCATED: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let ptr = unsafe { System.alloc(layout) };
        if !ptr.is_null() {
            let current = ALLOCATED.fetch_add(layout.size(), Ordering::Relaxed) + layout.size();
            // Update peak (relaxed CAS loop).
            let mut peak = PEAK.load(Ordering::Relaxed);
            while current > peak {
                match PEAK.compare_exchange_weak(peak, current, Ordering::Relaxed, Ordering::Relaxed)
                {
                    Ok(_) => break,
                    Err(actual) => peak = actual,
                }
            }
        }
        ptr
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) };
        ALLOCATED.fetch_sub(layout.size(), Ordering::Relaxed);
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

fn reset_peak() {
    PEAK.store(ALLOCATED.load(Ordering::Relaxed), Ordering::Relaxed);
}

fn current_allocated_mb() -> f64 {
    ALLOCATED.load(Ordering::Relaxed) as f64 / (1024.0 * 1024.0)
}

fn peak_allocated_mb() -> f64 {
    PEAK.load(Ordering::Relaxed) as f64 / (1024.0 * 1024.0)
}

// ========================================================================

fn load_model_raw(variant: ModelVariant, languages: &[Language]) -> Model {
    match variant {
        ModelVariant::Lite => Model::load_embedded(
            langidentify_models_lite::resolve,
            languages,
            -12.0,
            -12.0,
            0.0,
        )
        .unwrap(),
        ModelVariant::Full => Model::load_embedded(
            langidentify_models_full::resolve,
            languages,
            -15.0,
            -15.0,
            0.0,
        )
        .unwrap(),
        ModelVariant::Best => {
            if langidentify_models_full::exists("skipwords.txt") {
                Model::load_embedded(
                    langidentify_models_full::resolve,
                    languages,
                    -15.0,
                    -15.0,
                    0.0,
                )
                .unwrap()
            } else {
                Model::load_embedded(
                    langidentify_models_lite::resolve,
                    languages,
                    -12.0,
                    -12.0,
                    0.0,
                )
                .unwrap()
            }
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (languages_str, iterations, variant) = parse_args(&args);

    let languages = match Language::from_comma_separated(&languages_str) {
        Ok(langs) => langs,
        Err(e) => {
            eprintln!("Invalid languages: {}", e);
            process::exit(1);
        }
    };

    let variant_name = match variant {
        ModelVariant::Lite => "lite",
        ModelVariant::Full => "full",
        ModelVariant::Best => "best",
    };

    println!("Model:      {}", variant_name);
    println!("Languages:  {} configured", languages.len());
    println!("Iterations: {}", iterations);
    println!();

    let mut times = Vec::with_capacity(iterations);

    // Time iterations — drop the model between iterations to measure load cost.
    for i in 0..iterations {
        let t0 = Instant::now();
        let m = load_model_raw(variant, &languages);
        let elapsed = t0.elapsed().as_secs_f64();
        times.push(elapsed);
        println!("  Iteration {}: {:.3}s", i + 1, elapsed);
        drop(m);
    }

    let avg: f64 = times.iter().sum::<f64>() / times.len() as f64;
    let best = times.iter().cloned().fold(f64::INFINITY, f64::min);
    let worst = times.iter().cloned().fold(0.0f64, f64::max);

    println!();
    println!(
        "Load time:  avg={:.3}s  best={:.3}s  worst={:.3}s",
        avg, best, worst,
    );

    // Memory measurement — load once more with tracking.
    let before = current_allocated_mb();
    reset_peak();
    let model = load_model_raw(variant, &languages);
    let after = current_allocated_mb();
    let peak = peak_allocated_mb();

    println!(
        "Memory:     {:.1} MB resident, {:.1} MB peak during load",
        after - before,
        peak - before,
    );

    // Report table sizes.
    let mut total_entries = 0usize;
    for n in 1..=model.max_ngram() {
        total_entries += model.table(n).size();
    }
    let tw_entries = model.topwords_table().size();
    println!(
        "Tables:     {} ngram entries, {} topword entries",
        total_entries, tw_entries,
    );
}

fn parse_args(args: &[String]) -> (String, usize, ModelVariant) {
    let mut languages = None;
    let mut iterations = 3usize;
    let mut variant = ModelVariant::Best;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--languages" => {
                i += 1;
                languages = Some(args[i].clone());
            }
            "--iterations" => {
                i += 1;
                iterations = args[i].parse().unwrap_or(3);
            }
            "--model" => {
                i += 1;
                variant = ModelVariant::from_str(&args[i]);
            }
            "--help" | "-h" => {
                eprintln!(
                    "Usage: model-load-speed --languages <langs> \
                     [--iterations <n>] [--model lite|full|best]"
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
    (languages, iterations, variant)
}
