// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

use crate::alphabet::Alphabet;
use crate::language::Language;
use crate::model::Model;
use crate::ngram_table::{LangProbListBuilder, NgramTable, NgramTableBuilder};
use cjclassifier::CJClassifier;
use flate2::read::GzDecoder;
use std::collections::HashSet;
use std::fs::File;
use std::io::{self, BufRead, BufReader};
use std::path::Path;

// ========================================================================
// ResourceResolver abstraction
// ========================================================================

trait ResourceResolver: Send + Sync {
    fn open(&self, name: &str) -> io::Result<Box<dyn BufRead>>;
    fn exists(&self, name: &str) -> bool;
}

struct FileResolver {
    prefix: String,
}

impl FileResolver {
    fn new(raw: &str) -> Self {
        let stripped = raw.trim_end_matches(|c| c == '/' || c == std::path::MAIN_SEPARATOR);
        let prefix = if Path::new(stripped).is_dir() {
            format!("{}{}", stripped, std::path::MAIN_SEPARATOR)
        } else {
            stripped.to_string()
        };
        FileResolver { prefix }
    }
}

impl ResourceResolver for FileResolver {
    fn exists(&self, name: &str) -> bool {
        Path::new(&format!("{}{}", self.prefix, name)).exists()
    }

    fn open(&self, name: &str) -> io::Result<Box<dyn BufRead>> {
        let path = format!("{}{}", self.prefix, name);
        let file = File::open(&path)?;
        let reader = BufReader::with_capacity(1024 * 1024, file);
        if name.ends_with(".gz") {
            Ok(Box::new(BufReader::with_capacity(
                1024 * 1024,
                GzDecoder::new(reader),
            )))
        } else {
            Ok(Box::new(reader))
        }
    }
}

struct EmbeddedResolver {
    resolve_fn: fn(&str) -> Option<&'static [u8]>,
}

impl ResourceResolver for EmbeddedResolver {
    fn exists(&self, name: &str) -> bool {
        (self.resolve_fn)(name).is_some()
    }

    fn open(&self, name: &str) -> io::Result<Box<dyn BufRead>> {
        match (self.resolve_fn)(name) {
            Some(bytes) => {
                let cursor = io::Cursor::new(bytes);
                if name.ends_with(".gz") {
                    Ok(Box::new(BufReader::with_capacity(
                        1024 * 1024,
                        GzDecoder::new(cursor),
                    )))
                } else {
                    Ok(Box::new(BufReader::new(cursor)))
                }
            }
            None => Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("Embedded resource not found: {}", name),
            )),
        }
    }
}

// ========================================================================
// Entry points
// ========================================================================

pub fn load_from_path(
    prefix: &str,
    languages: &[Language],
    min_log_prob: f64,
    tw_min_log_prob: f64,
    cj_min_log_prob: f64,
) -> io::Result<Model> {
    let resolver = FileResolver::new(prefix);
    actually_load(
        &resolver,
        languages,
        min_log_prob,
        tw_min_log_prob,
        cj_min_log_prob,
    )
}

pub fn load_embedded(
    resolve_fn: fn(&str) -> Option<&'static [u8]>,
    languages: &[Language],
    min_log_prob: f64,
    tw_min_log_prob: f64,
    cj_min_log_prob: f64,
) -> io::Result<Model> {
    let resolver = EmbeddedResolver { resolve_fn };
    actually_load(
        &resolver,
        languages,
        min_log_prob,
        tw_min_log_prob,
        cj_min_log_prob,
    )
}

// ========================================================================
// Core loading logic
// ========================================================================

fn actually_load(
    resolver: &dyn ResourceResolver,
    languages: &[Language],
    min_log_prob: f64,
    tw_min_log_prob: f64,
    cj_min_log_prob: f64,
) -> io::Result<Model> {
    // Canonicalize language order (deduplicate, maintain order)
    let mut seen = HashSet::new();
    let ordered_langs: Vec<Language> = languages
        .iter()
        .filter(|l| **l != Language::Unknown && seen.insert(**l))
        .copied()
        .collect();

    if ordered_langs.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "At least one language required",
        ));
    }

    let skip_topwords = tw_min_log_prob.is_nan();
    let skip_ngrams = compute_skip_ngrams(&ordered_langs);
    let load_min_log_prob = if min_log_prob == 0.0 {
        f64::MIN
    } else {
        min_log_prob
    };
    let load_tw_min_log_prob = if skip_topwords {
        0.0
    } else if tw_min_log_prob == 0.0 {
        f64::MIN
    } else {
        tw_min_log_prob
    };

    // Resolve ngram file names
    let mut ngram_names: Vec<Option<String>> = vec![None; ordered_langs.len()];
    for (li, lang) in ordered_langs.iter().enumerate() {
        if skip_ngrams[li] {
            continue;
        }
        let iso = lang.iso_code();
        let gz_name = format!("ngrams-{}.txt.gz", iso);
        let txt_name = format!("ngrams-{}.txt", iso);
        if resolver.exists(&gz_name) {
            ngram_names[li] = Some(gz_name);
        } else if resolver.exists(&txt_name) {
            ngram_names[li] = Some(txt_name);
        } else {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!(
                    "Ngram file not found for {}: tried {} and {}",
                    iso, gz_name, txt_name
                ),
            ));
        }
    }

    // Resolve topword file names
    let mut topword_names: Vec<Option<String>> = vec![None; ordered_langs.len()];
    if !skip_topwords {
        for (li, lang) in ordered_langs.iter().enumerate() {
            if skip_ngrams[li] {
                continue;
            }
            let iso = lang.iso_code();
            let gz_name = format!("topwords-{}.txt.gz", iso);
            let txt_name = format!("topwords-{}.txt", iso);
            if resolver.exists(&gz_name) {
                topword_names[li] = Some(gz_name);
            } else if resolver.exists(&txt_name) {
                topword_names[li] = Some(txt_name);
            }
        }
    }

    // Determine if CJ classifier is needed
    let has_ja = ordered_langs.contains(&Language::Japanese);
    let has_zh_hans = ordered_langs.contains(&Language::ChineseSimplified);
    let has_zh_hant = ordered_langs.contains(&Language::ChineseTraditional);
    let needs_cj = (has_ja && (has_zh_hans || has_zh_hant)) || (has_zh_hans && has_zh_hant);

    // --- Parallel loading of ngrams, topwords, and CJ classifier ---
    type NgramResult = io::Result<(Vec<NgramTable>, Vec<LoadNgramInfo>, usize, f64)>;
    type TopwordResult = io::Result<(NgramTable, f64)>;
    type CjResult = Option<std::sync::Arc<cjclassifier::CJClassifier>>;

    let (ngram_result, topword_result, cj_classifier) = std::thread::scope(|s| {
        // Thread 1: Load and compact ngrams
        let ngram_handle = s.spawn(|| -> NgramResult {
            let load_max_ngram = 5usize;
            let mut builders: Vec<NgramTableBuilder> = (0..load_max_ngram)
                .map(|_| NgramTableBuilder::new())
                .collect();
            let mut ngram_infos: Vec<LoadNgramInfo> = (0..ordered_langs.len())
                .map(|_| LoadNgramInfo {
                    wanted_min_log_prob: load_min_log_prob,
                    count: 0,
                    seen_min_log_prob: f64::NAN,
                    seen_max_ngram: 0,
                })
                .collect();

            for li in 0..ordered_langs.len() {
                if let Some(ref name) = ngram_names[li] {
                    load_language_ngrams(
                        resolver,
                        name,
                        li as u32,
                        &mut builders,
                        &mut ngram_infos[li],
                    )?;
                }
            }

            let mut eff_max_ngram = 0usize;
            for info in &ngram_infos {
                eff_max_ngram = eff_max_ngram.max(info.seen_max_ngram);
            }
            if eff_max_ngram == 0 {
                eff_max_ngram = load_max_ngram;
            }

            let mut compact_floor = load_min_log_prob;
            for info in &ngram_infos {
                if !info.seen_min_log_prob.is_nan() {
                    compact_floor = compact_floor.max(info.seen_min_log_prob);
                }
            }
            if compact_floor == f64::MIN {
                compact_floor = 0.0;
            }

            let mut tables: Vec<NgramTable> = Vec::with_capacity(eff_max_ngram);
            for n in 0..eff_max_ngram {
                let builder = std::mem::replace(&mut builders[n], NgramTableBuilder::new());
                tables.push(builder.compact(compact_floor as f32));
            }

            Ok((tables, ngram_infos, eff_max_ngram, compact_floor))
        });

        // Thread 2: Load and compact topwords
        let topword_handle = s.spawn(|| -> TopwordResult {
            if skip_topwords {
                return Ok((NgramTable::empty(), 0.0));
            }

            let mut tw_builder = NgramTableBuilder::new();

            if resolver.exists("skipwords.txt") {
                load_skip_words(resolver, "skipwords.txt", &mut tw_builder)?;
            }

            let mut tw_compact_floor = load_tw_min_log_prob;
            let mut any_tw_loaded = false;

            for li in 0..ordered_langs.len() {
                if let Some(ref name) = topword_names[li] {
                    let file_min_log_prob = load_top_words(
                        resolver,
                        name,
                        li as u32,
                        &mut tw_builder,
                        load_tw_min_log_prob,
                    )?;
                    if !file_min_log_prob.is_nan() {
                        tw_compact_floor = tw_compact_floor.max(file_min_log_prob);
                    }
                    any_tw_loaded = true;
                }
            }

            if tw_compact_floor == f64::MIN {
                tw_compact_floor = 0.0;
            }

            let table = if any_tw_loaded {
                tw_builder.compact(tw_compact_floor as f32)
            } else {
                NgramTable::empty()
            };

            Ok((table, tw_compact_floor))
        });

        // Thread 3: Load CJ classifier
        let cj_handle = s.spawn(|| -> CjResult {
            if !needs_cj {
                return None;
            }
            let cjc = if cj_min_log_prob != 0.0 {
                CJClassifier::load_with_floor(cj_min_log_prob)
            } else {
                CJClassifier::load()
            };
            match cjc {
                Ok(arc) => Some(arc),
                Err(e) => {
                    log::warn!("Failed to load CJClassifier: {}", e);
                    None
                }
            }
        });

        // Join all threads
        let ngram_result = ngram_handle.join().expect("ngram loading thread panicked");
        let topword_result = topword_handle.join().expect("topword loading thread panicked");
        let cj_classifier = cj_handle.join().expect("CJ classifier loading thread panicked");

        (ngram_result, topword_result, cj_classifier)
    });

    let (tables, ngram_infos, eff_max_ngram, compact_floor) = ngram_result?;
    let (topwords_table, _tw_compact_floor) = topword_result?;

    // Compute effective values
    let mut effective_min_log_prob = load_min_log_prob;
    for info in &ngram_infos {
        if !info.seen_min_log_prob.is_nan() {
            effective_min_log_prob = effective_min_log_prob.max(info.seen_min_log_prob);
        }
    }
    if effective_min_log_prob == f64::MIN {
        effective_min_log_prob = 0.0;
    }

    let effective_tw_min_log_prob = if skip_topwords {
        0.0
    } else {
        compact_floor.max(load_tw_min_log_prob)
    };

    log::info!(
        "ModelLoader: loaded {} languages, maxNgram={}, minLogProb={:.1}",
        ordered_langs.len(),
        eff_max_ngram,
        effective_min_log_prob
    );

    Ok(Model::new(
        tables,
        ordered_langs,
        effective_min_log_prob,
        effective_tw_min_log_prob,
        eff_max_ngram,
        cj_classifier,
        topwords_table,
    ))
}

// ========================================================================
// Helpers
// ========================================================================

struct LoadNgramInfo {
    wanted_min_log_prob: f64,
    count: usize,
    seen_min_log_prob: f64,
    seen_max_ngram: usize,
}

fn compute_skip_ngrams(ordered_langs: &[Language]) -> Vec<bool> {
    use std::collections::HashMap;
    let mut alpha_lang_count: HashMap<Alphabet, usize> = HashMap::new();
    for lang in ordered_langs {
        for &alpha in lang.alphabets() {
            *alpha_lang_count.entry(alpha).or_insert(0) += 1;
        }
    }
    ordered_langs
        .iter()
        .map(|lang| {
            let all_unique = lang
                .alphabets()
                .iter()
                .all(|a| alpha_lang_count.get(a).copied().unwrap_or(0) <= 1);
            let is_cj = matches!(
                lang,
                Language::ChineseSimplified | Language::ChineseTraditional | Language::Japanese
            );
            all_unique || is_cj
        })
        .collect()
}

// ========================================================================
// Ngram loading
// ========================================================================

fn load_language_ngrams(
    resolver: &dyn ResourceResolver,
    name: &str,
    lang_idx: u32,
    builders: &mut [NgramTableBuilder],
    info: &mut LoadNgramInfo,
) -> io::Result<()> {
    let mut min_log_prob = info.wanted_min_log_prob;
    let mut totals = [0i64; 6]; // index 1..5
    let mut reader = resolver.open(name)?;

    // Parse header
    let mut header = String::new();
    reader.read_line(&mut header)?;
    let header = header.trim();
    if header.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("Empty ngram file: {}", name),
        ));
    }
    let header = header.strip_prefix("# ").unwrap_or(header);

    let mut found_min_log_prob = false;
    let mut seen_max_n = 0usize;
    let parts: Vec<&str> = header.split(' ').collect();
    let mut pi = 0;
    while pi < parts.len() {
        let part = parts[pi];
        if part == "MinLogProb:" && pi + 1 < parts.len() {
            let parsed: f64 = parts[pi + 1]
                .parse()
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}", e)))?;
            info.seen_min_log_prob = parsed;
            found_min_log_prob = true;
            if parsed > min_log_prob {
                min_log_prob = parsed;
            }
            pi += 2;
            continue;
        }
        if let Some(colon_idx) = part.find(':') {
            if let Ok(n) = part[..colon_idx].parse::<usize>() {
                let value = &part[colon_idx + 1..];
                if let Some(slash_idx) = value.find('/') {
                    if let Ok(total) = value[slash_idx + 1..].parse::<i64>() {
                        if (1..=5).contains(&n) {
                            totals[n] = total;
                            if n > seen_max_n {
                                seen_max_n = n;
                            }
                        }
                    }
                }
            }
        }
        pi += 1;
    }

    if !found_min_log_prob {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("Missing MinLogProb in ngram file header: {}", name),
        ));
    }
    info.seen_max_ngram = seen_max_n;

    // Read ngram lines
    let mut line = String::new();
    let mut entry_count = 0usize;

    loop {
        line.clear();
        let bytes_read = reader.read_line(&mut line)?;
        if bytes_read == 0 {
            break;
        }
        let line_trimmed = line.trim();
        if line_trimmed.is_empty() || line_trimmed.starts_with('#') {
            continue;
        }

        let space_idx = match line_trimmed.rfind(' ') {
            Some(idx) if idx > 0 => idx,
            _ => continue,
        };

        let ngram_str = &line_trimmed[..space_idx];
        let value_str = &line_trimmed[space_idx + 1..];
        let n = ngram_str.chars().count();
        if n < 1 || n > 5 || totals[n] == 0 {
            continue;
        }

        let log_prob: f32 = if value_str.starts_with('-') {
            value_str
                .parse()
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}", e)))?
        } else {
            let count: i64 = value_str
                .parse()
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}", e)))?;
            (count as f64 / totals[n] as f64).ln() as f32
        };

        if (log_prob as f64) < min_log_prob {
            continue;
        }

        let chars: Vec<char> = ngram_str.chars().collect();
        let builder = &mut builders[n - 1];

        if let Some(existing) = builder.get_mut(&chars) {
            existing.add(lang_idx, log_prob);
        } else {
            let mut lp_builder = LangProbListBuilder::new();
            lp_builder.add(lang_idx, log_prob);
            builder.put(chars.into_boxed_slice(), lp_builder);
        }

        entry_count += 1;
    }

    info.count = entry_count;
    Ok(())
}

// ========================================================================
// Skipwords loading
// ========================================================================

fn load_skip_words(
    resolver: &dyn ResourceResolver,
    name: &str,
    builder: &mut NgramTableBuilder,
) -> io::Result<()> {
    let mut reader = resolver.open(name)?;
    let mut line = String::new();

    loop {
        line.clear();
        let bytes_read = reader.read_line(&mut line)?;
        if bytes_read == 0 {
            break;
        }
        let word = line.trim();
        if word.is_empty() || word.starts_with('#') {
            continue;
        }

        let chars: Vec<char> = word.chars().collect();
        if let Some(existing) = builder.get_mut(&chars) {
            existing.poison();
        } else {
            let mut lp_builder = LangProbListBuilder::new();
            lp_builder.poison();
            builder.put(chars.into_boxed_slice(), lp_builder);
        }
    }
    Ok(())
}

// ========================================================================
// Topwords loading
// ========================================================================

/// Returns the file's MinLogProb value (or NaN if not found).
fn load_top_words(
    resolver: &dyn ResourceResolver,
    name: &str,
    lang_idx: u32,
    builder: &mut NgramTableBuilder,
    mut tw_min_log_prob: f64,
) -> io::Result<f64> {
    let mut reader = resolver.open(name)?;

    // Parse header: "# Count: NNN MinLogProb: -X.X"
    let mut header = String::new();
    reader.read_line(&mut header)?;
    let header = header.trim();

    if !header.starts_with("# Count: ") {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("Expected '# Count: NNN' header in topwords file: {}", name),
        ));
    }

    let after_count = header["# Count: ".len()..].trim();
    let (total_str, rest) = match after_count.find(' ') {
        Some(idx) => (&after_count[..idx], Some(after_count[idx + 1..].trim())),
        None => (after_count, None),
    };
    let total: i64 = total_str
        .parse()
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}", e)))?;
    if total <= 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("Invalid total count in topwords file: {}", name),
        ));
    }

    let file_min_tw_log_prob = if let Some(rest) = rest {
        if let Some(stripped) = rest.strip_prefix("MinLogProb: ") {
            let val: f64 = stripped
                .trim()
                .parse()
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}", e)))?;
            if val > tw_min_log_prob {
                tw_min_log_prob = val;
            }
            val
        } else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("Missing MinLogProb in topwords file header: {}", name),
            ));
        }
    } else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("Missing MinLogProb in topwords file header: {}", name),
        ));
    };

    let mut line = String::new();

    loop {
        line.clear();
        let bytes_read = reader.read_line(&mut line)?;
        if bytes_read == 0 {
            break;
        }
        let line_trimmed = line.trim();
        if line_trimmed.is_empty() || line_trimmed.starts_with('#') {
            continue;
        }

        let space_idx = match line_trimmed.rfind(' ') {
            Some(idx) if idx > 0 => idx,
            _ => continue,
        };

        let word = &line_trimmed[..space_idx];
        let chars: Vec<char> = word.chars().collect();

        // Skip single-character ASCII words
        if chars.len() == 1 && (chars[0] as u32) <= 0x7f {
            continue;
        }

        let value_str = &line_trimmed[space_idx + 1..];
        let log_prob: f32 = if value_str.starts_with('-') {
            value_str
                .parse()
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}", e)))?
        } else {
            let count: i64 = value_str
                .parse()
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}", e)))?;
            (count as f64 / total as f64).ln() as f32
        };

        if (log_prob as f64) < tw_min_log_prob {
            break; // sorted descending, so remaining are below threshold
        }

        if let Some(existing) = builder.get_mut(&chars) {
            existing.add(lang_idx, log_prob);
        } else {
            let mut lp_builder = LangProbListBuilder::new();
            lp_builder.add(lang_idx, log_prob);
            builder.put(chars.into_boxed_slice(), lp_builder);
        }
    }

    Ok(file_min_tw_log_prob)
}
