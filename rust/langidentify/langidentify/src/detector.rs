// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

use crate::alphabet::Alphabet;
use crate::language::Language;
use crate::model::Model;
use crate::ngram_table::NgramEntry;
use crate::word_segmenter::WordSegmenter;
use cjclassifier::{CJLanguage, Results as CjResults};
use std::sync::Arc;

/// Accumulated per-language scoring data during word-level ngram and topword lookups.
pub struct Scores {
    pub ngram_scores: Vec<f64>,
    pub ngram_hits_per_lang: Vec<i32>,
    pub tw_scores: Vec<f64>,
    pub tw_hits_per_lang: Vec<i32>,
    pub tw_num_lookups: i32,
    pub alphabet_counts: Vec<usize>,
    pub num_words: usize,
}

impl Scores {
    fn new(model: &Model) -> Self {
        let num_langs = model.num_languages();
        Scores {
            ngram_scores: vec![0.0; num_langs],
            ngram_hits_per_lang: vec![0; num_langs],
            tw_scores: vec![0.0; num_langs],
            tw_hits_per_lang: vec![0; num_langs],
            tw_num_lookups: 0,
            alphabet_counts: vec![0; model.num_alphabets()],
            num_words: 0,
        }
    }

    pub fn clear(&mut self) {
        self.num_words = 0;
        self.ngram_scores.fill(0.0);
        self.ngram_hits_per_lang.fill(0);
        self.tw_scores.fill(0.0);
        self.tw_hits_per_lang.fill(0);
        self.tw_num_lookups = 0;
        self.alphabet_counts.fill(0);
    }

    /// Returns the total number of scored characters across all alphabets.
    pub fn num_chars(&self) -> usize {
        self.alphabet_counts.iter().sum()
    }
}

/// Detection results computed after scoring.
pub struct Results {
    tw_scratch: Vec<f64>,
    pub scores: Scores,
    pub cj_results: Option<CjResults>,
    pub total_scores: Vec<f64>,
    pub result: Language,
    pub gap: f64,
    pub predominant_alpha_idx: i32,
}

impl Results {
    fn new(model: &Model) -> Self {
        let num_langs = model.num_languages();
        let cj_results = if model.cj_classifier().is_some() {
            Some(CjResults::new())
        } else {
            None
        };
        Results {
            tw_scratch: vec![0.0; num_langs],
            scores: Scores::new(model),
            cj_results,
            total_scores: vec![0.0; num_langs],
            result: Language::Unknown,
            gap: 0.0,
            predominant_alpha_idx: -1,
        }
    }

    fn compute_result_for_cj(&mut self, model: &Model, boosts: Option<&[f64]>) -> bool {
        let ja_kana_idx = model.alphabet_index(Alphabet::JaKana);
        let cj_unified_idx = model.alphabet_index(Alphabet::Han);
        let kana_chars = ja_kana_idx
            .map(|i| self.scores.alphabet_counts[i])
            .unwrap_or(0);
        let han_chars = cj_unified_idx
            .map(|i| self.scores.alphabet_counts[i])
            .unwrap_or(0);
        let total_cj = kana_chars + han_chars;

        if let Some(cjc) = model.cj_classifier() {
            if total_cj > 0 && kana_chars > 0 {
                let kana_ratio = kana_chars as f64 / total_cj as f64;
                if kana_ratio > cjc.tolerated_kana_threshold() {
                    // Kana fraction exceeds threshold — classify as Japanese
                    self.predominant_alpha_idx = ja_kana_idx.unwrap_or(0) as i32;
                    self.result = Language::Japanese;
                    self.gap = 1.0;
                    self.total_scores.fill(0.0);
                    if let Some(ja_li) = model.lang_index(Language::Japanese) {
                        self.total_scores[ja_li] = 1.0;
                    }
                    return true;
                }
            }
        }

        if let Some(ref mut cj_results) = self.cj_results {
            // Map detector-level boosts to CJ boosts
            map_cj_boosts(model, boosts, cj_results);

            if let Some(cjc) = model.cj_classifier() {
                let cj_result = cjc.compute_result(cj_results);
                if cj_result != CJLanguage::Unknown {
                    self.predominant_alpha_idx = cj_unified_idx.unwrap_or(0) as i32;
                    self.result = convert_cj_language(cj_result);
                    self.gap = cj_results.gap;
                    // Populate total_scores from CJ results
                    self.total_scores.fill(0.0);
                    for (ci, &cj_lang) in cjclassifier::CJ_LANGUAGES.iter().enumerate() {
                        let lang = convert_cj_language(cj_lang);
                        if let Some(li) = model.lang_index(lang) {
                            self.total_scores[li] = cj_results.total_scores[ci];
                        }
                    }
                    return true;
                }
            }
        }

        // Fall through with HAN as predominant
        self.predominant_alpha_idx = cj_unified_idx.unwrap_or(0) as i32;
        false
    }

    fn normalized_ngram_scores_to_total_with_floor(&mut self, min_log_prob: f64) {
        let num_langs = self.scores.ngram_scores.len();
        let mut max_hits = 0;
        for li in 0..num_langs {
            self.total_scores[li] = self.scores.ngram_scores[li];
            if self.scores.ngram_hits_per_lang[li] > max_hits {
                max_hits = self.scores.ngram_hits_per_lang[li];
            }
        }
        let factor = if max_hits > 0 {
            1.0 / max_hits as f64
        } else {
            1.0
        };
        for li in 0..num_langs {
            self.total_scores[li] +=
                (max_hits - self.scores.ngram_hits_per_lang[li]) as f64 * min_log_prob;
            self.total_scores[li] *= factor;
        }
    }

    fn normalized_tw_scores_to_scratch(&mut self, model: &Model) -> f64 {
        if self.scores.tw_num_lookups == 0 {
            return 0.0;
        }
        let num_langs = self.scores.tw_scores.len();
        let mut tw_max_hits = 0;
        for li in 0..num_langs {
            self.tw_scratch[li] = self.scores.tw_scores[li];
            if self.scores.tw_hits_per_lang[li] > tw_max_hits {
                tw_max_hits = self.scores.tw_hits_per_lang[li];
            }
        }
        if tw_max_hits == 0 {
            return 0.0;
        }
        let tw_min_log_prob = model.tw_min_log_prob();
        let factor = 1.0 / tw_max_hits as f64;
        for li in 0..num_langs {
            self.tw_scratch[li] +=
                (tw_max_hits - self.scores.tw_hits_per_lang[li]) as f64 * tw_min_log_prob;
            self.tw_scratch[li] *= factor;
        }

        let ratio = tw_max_hits as f64 / self.scores.tw_num_lookups as f64;
        if ratio < 0.5 {
            0.5 * tw_max_hits as f64 / self.scores.tw_num_lookups as f64
        } else {
            0.8 * tw_max_hits as f64 / self.scores.tw_num_lookups as f64
        }
    }
}

fn convert_cj_language(cj: CJLanguage) -> Language {
    match cj {
        CJLanguage::ChineseSimplified => Language::ChineseSimplified,
        CJLanguage::ChineseTraditional => Language::ChineseTraditional,
        CJLanguage::Japanese => Language::Japanese,
        CJLanguage::Unknown => Language::Unknown,
    }
}

fn map_cj_boosts(model: &Model, detector_boosts: Option<&[f64]>, cj_results: &mut CjResults) {
    cj_results.boosts = [0.0; 3];
    if let Some(boosts) = detector_boosts {
        for (ci, &cj_lang) in cjclassifier::CJ_LANGUAGES.iter().enumerate() {
            let lang = convert_cj_language(cj_lang);
            if let Some(li) = model.lang_index(lang) {
                if li < boosts.len() {
                    cj_results.boosts[ci] = boosts[li];
                }
            }
        }
    }
}

// ========================================================================
// Detector
// ========================================================================

/// Detects the language of text using n-gram and topword scoring against a loaded Model.
///
/// Detector is inexpensive to construct and intentionally not thread-safe
/// (not `Sync`). For concurrent detection, use a separate instance per thread.
pub struct Detector {
    model: Arc<Model>,
    min_ngram: usize,
    stop_if_ngram_covered: usize,
    max_ngram: usize,
    use_topwords: bool,
    segmenter: WordSegmenter,
    results: Results,
}

impl Detector {
    pub fn new(model: Arc<Model>) -> Self {
        let stop = model.max_ngram().min(3);
        let max = model.max_ngram();
        let results = Results::new(&model);
        Detector {
            model,
            min_ngram: 1,
            stop_if_ngram_covered: stop,
            max_ngram: max,
            use_topwords: true,
            segmenter: WordSegmenter::new(),
            results,
        }
    }

    /// Adjusts accuracy parameters.
    pub fn set_accuracy_params(
        &mut self,
        min_ngram: usize,
        stop_if_ngram_covered: usize,
        max_ngram: usize,
        use_topwords: bool,
    ) -> bool {
        if min_ngram < 1
            || max_ngram > self.model.max_ngram()
            || min_ngram > max_ngram
            || stop_if_ngram_covered < min_ngram
            || stop_if_ngram_covered > max_ngram
        {
            return false;
        }
        self.min_ngram = min_ngram;
        self.stop_if_ngram_covered = stop_if_ngram_covered;
        self.max_ngram = max_ngram;
        self.use_topwords = use_topwords;
        true
    }

    /// Detects the language of the given text.
    pub fn detect(&mut self, text: &str) -> Language {
        self.results.scores.clear();
        if let Some(ref mut cj) = self.results.cj_results {
            cj.clear();
        }
        self.add_text_internal(text);
        self.results.compute_result_with_model(&self.model, None);
        self.results.result
    }

    /// Detects the language with boosts applied.
    pub fn detect_with_boosts(&mut self, text: &str, boosts: &[f64]) -> Language {
        self.results.scores.clear();
        if let Some(ref mut cj) = self.results.cj_results {
            cj.clear();
        }
        self.add_text_internal(text);
        self.results
            .compute_result_with_model(&self.model, Some(boosts));
        self.results.result
    }

    /// Clears the scores for use with the addText API.
    pub fn clear_scores(&mut self) {
        self.results.scores.clear();
        if let Some(ref mut cj) = self.results.cj_results {
            cj.clear();
        }
    }

    /// Adds text to the current detection accumulator.
    pub fn add_text(&mut self, text: &str) {
        self.add_text_internal(text);
    }

    /// Computes the result from all text added since the last clear.
    pub fn compute_result(&mut self) -> Language {
        self.results.compute_result_with_model(&self.model, None);
        self.results.result
    }

    /// Computes the result with boosts.
    pub fn compute_result_with_boosts(&mut self, boosts: &[f64]) -> Language {
        self.results
            .compute_result_with_model(&self.model, Some(boosts));
        self.results.result
    }

    /// Returns the detection results.
    pub fn results(&self) -> &Results {
        &self.results
    }

    /// Returns a mutable reference to the results (for advanced use).
    pub fn results_mut(&mut self) -> &mut Results {
        &mut self.results
    }

    /// Returns the model.
    pub fn model(&self) -> &Model {
        &self.model
    }

    fn add_text_internal(&mut self, text: &str) {
        // Destructure to get split borrows
        let Self {
            model,
            segmenter,
            results,
            min_ngram,
            stop_if_ngram_covered,
            max_ngram,
            use_topwords,
        } = self;
        let model = &**model;
        let min_n = *min_ngram;
        let stop_covered = *stop_if_ngram_covered;
        let max_n = *max_ngram;
        let use_tw = *use_topwords;

        segmenter.segment(text, model, |word_buf, word_len, alpha_idx, is_cj| {
            if is_cj {
                // CJ path: feed to CJClassifier via cj_results.scores
                if let Some(cjc) = model.cj_classifier() {
                    let text: String = word_buf[..word_len].iter().collect();
                    if let Some(ref mut cj_res) = results.cj_results {
                        cjc.add_text(&text, &mut cj_res.scores);
                    }
                }
                results.scores.alphabet_counts[alpha_idx] += word_len;
                results.scores.num_words += 1;
            } else if model.alphabet_implies_one_language(alpha_idx)
                || score_word(
                    word_buf,
                    word_len,
                    use_tw,
                    min_n,
                    stop_covered,
                    max_n,
                    model,
                    &mut results.scores,
                )
            {
                results.scores.alphabet_counts[alpha_idx] += word_len;
                results.scores.num_words += 1;
            }
        });
    }
}

impl Results {
    fn compute_result_with_model(&mut self, model: &Model, boosts: Option<&[f64]>) {
        // Use model's min_log_prob for normalization
        let num_langs = self.scores.ngram_scores.len();

        // Determine predominant alphabet
        self.predominant_alpha_idx = -1;
        let mut max_weighted_count: f64 = 0.0;
        let alphas = model.alphabets();
        let cj_unified_idx = model.alphabet_index(Alphabet::Han);
        let ja_kana_idx = model.alphabet_index(Alphabet::JaKana);
        let mut cj_group_weight: f64 = 0.0;

        for (ai, &count) in self.scores.alphabet_counts.iter().enumerate() {
            if count > 0 {
                let wc = count as f64 * alphas[ai].weight();
                if self.cj_results.is_some()
                    && (Some(ai) == cj_unified_idx || Some(ai) == ja_kana_idx)
                {
                    cj_group_weight += wc;
                } else if wc > max_weighted_count {
                    max_weighted_count = wc;
                    self.predominant_alpha_idx = ai as i32;
                }
            }
        }

        if cj_group_weight > max_weighted_count {
            if self.compute_result_for_cj(model, boosts) {
                return;
            }
        }

        if self.predominant_alpha_idx < 0 {
            self.total_scores.fill(0.0);
            self.result = Language::Unknown;
            self.gap = 1.0;
            return;
        }
        let predominant_alpha_idx = self.predominant_alpha_idx as usize;

        if model.alphabet_implies_one_language(predominant_alpha_idx) {
            let li = model.lang_indices_for_alphabet(predominant_alpha_idx)[0];
            for i in 0..num_langs {
                self.total_scores[i] = if i == li { 1.0 } else { 0.0 };
            }
            self.result = model.unique_language_for_alphabet(predominant_alpha_idx);
            self.gap = 1.0;
            return;
        }

        // Normalize ngram scores
        self.normalized_ngram_scores_to_total_with_floor(model.min_log_prob());

        // Blend in topwords
        let tw_factor = self.normalized_tw_scores_to_scratch(model);
        if tw_factor > 0.0 {
            let ngram_weight = 1.0 - tw_factor;
            for li in 0..num_langs {
                self.total_scores[li] =
                    (self.total_scores[li] * ngram_weight) + (self.tw_scratch[li] * tw_factor);
            }
        }

        if let Some(boosts) = boosts {
            for li in 0..num_langs {
                if li < boosts.len() && boosts[li] != 0.0 {
                    self.total_scores[li] += boosts[li] * self.total_scores[li].abs();
                }
            }
        }

        let candidates = model.lang_indices_for_alphabet(predominant_alpha_idx);
        let mut best_idx = candidates[0];
        let mut second_idx: Option<usize> = None;
        for &li in &candidates[1..] {
            if self.total_scores[li] > self.total_scores[best_idx] {
                second_idx = Some(best_idx);
                best_idx = li;
            } else if second_idx.is_none()
                || self.total_scores[li] > self.total_scores[second_idx.unwrap()]
            {
                second_idx = Some(li);
            }
        }

        if self.total_scores[best_idx] != 0.0 {
            self.result = model.languages()[best_idx];
            let second = second_idx
                .map(|si| self.total_scores[si])
                .unwrap_or(self.total_scores[best_idx]);
            self.gap = if second != 0.0 {
                1.0 - (self.total_scores[best_idx] / second)
            } else {
                0.0
            };
        } else {
            self.result = Language::Unknown;
        }
    }
}

// ========================================================================
// Scoring
// ========================================================================

fn score_word(
    word_buf: &[char],
    word_len: usize,
    use_topwords: bool,
    min_ngram: usize,
    stop_if_covered: usize,
    max_ngram: usize,
    model: &Model,
    scores: &mut Scores,
) -> bool {
    if use_topwords && !score_topwords(word_buf, word_len, model, scores) {
        return false; // skipword
    }

    // Score ngrams within the word
    let max_n = max_ngram.min(word_len);
    for n in (min_ngram..=max_n).rev() {
        let table = model.table(n);
        let prob_data = table.prob_data();
        let mut is_fully_covered = true;
        for start in 0..=(word_len - n) {
            let ngram = &word_buf[start..start + n];
            if let Some(entry) = table.lookup(ngram) {
                sum_ngram_entry(entry, prob_data, scores);
            } else {
                is_fully_covered = false;
            }
        }
        if is_fully_covered && n <= stop_if_covered {
            break;
        }
    }
    true
}

fn sum_ngram_entry(entry: &NgramEntry, prob_data: &[f32], scores: &mut Scores) {
    if let Some(ref lang_indices) = entry.lang_indices {
        let prob_ofs = entry.prob_offset as usize;
        for (j, &li) in lang_indices.iter().enumerate() {
            let li = li as usize;
            scores.ngram_scores[li] += prob_data[prob_ofs + j] as f64;
            scores.ngram_hits_per_lang[li] += 1;
        }
    }
}

fn score_topwords(word_buf: &[char], word_len: usize, model: &Model, scores: &mut Scores) -> bool {
    // Only score multi-character words (or non-ASCII single chars)
    if word_len > 1 || (word_len == 1 && word_buf[0] as u32 >= 0x80) {
        let tw_table = model.topwords_table();
        let key = &word_buf[..word_len];
        scores.tw_num_lookups += 1;

        if let Some(entry) = tw_table.lookup(key) {
            if entry.lang_indices.is_none() {
                // Skipword - back out stats
                scores.tw_num_lookups -= 1;
                return false;
            }
            if let Some(ref lang_indices) = entry.lang_indices {
                let tw_prob = tw_table.prob_data();
                let prob_ofs = entry.prob_offset as usize;
                for (j, &li) in lang_indices.iter().enumerate() {
                    let li = li as usize;
                    scores.tw_scores[li] += tw_prob[prob_ofs + j] as f64;
                    scores.tw_hits_per_lang[li] += 1;
                }
            }
        } else if word_len > 2 {
            let apos = mid_word_apostrophe_position(word_buf, word_len);
            if apos > 0 {
                score_apostrophe_topwords(tw_table, word_buf, apos, word_len, scores);
            }
        }
    }
    true
}

fn score_apostrophe_topwords(
    tw_table: &crate::ngram_table::NgramTable,
    word_buf: &[char],
    apos: usize,
    word_len: usize,
    scores: &mut Scores,
) {
    // Look up prefix including apostrophe (e.g. "l'")
    let prefix = &word_buf[..apos + 1];
    if let Some(entry) = tw_table.lookup(prefix) {
        if let Some(ref lang_indices) = entry.lang_indices {
            let tw_prob = tw_table.prob_data();
            let prob_ofs = entry.prob_offset as usize;
            for (j, &li) in lang_indices.iter().enumerate() {
                let li = li as usize;
                scores.tw_scores[li] += tw_prob[prob_ofs + j] as f64;
                scores.tw_hits_per_lang[li] += 1;
            }
        }
    }
    // Look up suffix after apostrophe (e.g. "homme")
    let suffix_len = word_len - apos - 1;
    if suffix_len > 1 {
        scores.tw_num_lookups += 1;
        let suffix = &word_buf[apos + 1..word_len];
        if let Some(entry) = tw_table.lookup(suffix) {
            if let Some(ref lang_indices) = entry.lang_indices {
                let tw_prob = tw_table.prob_data();
                let prob_ofs = entry.prob_offset as usize;
                for (j, &li) in lang_indices.iter().enumerate() {
                    let li = li as usize;
                    scores.tw_scores[li] += tw_prob[prob_ofs + j] as f64;
                    scores.tw_hits_per_lang[li] += 1;
                }
            }
        }
    }
}

fn mid_word_apostrophe_position(chars: &[char], len: usize) -> usize {
    let end = len - 1; // don't count the edge
    for i in 1..end {
        if chars[i] == '\'' {
            return i;
        }
    }
    0 // 0 means not found (can't be at position 0)
}
