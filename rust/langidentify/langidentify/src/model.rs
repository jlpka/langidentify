// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

use crate::alphabet::Alphabet;
use crate::language::Language;
use crate::model_loader;
use crate::ngram_table::NgramTable;
use cjclassifier::CJClassifier;
use std::collections::HashMap;
use std::io;
use std::sync::Arc;

/// Immutable model containing ngram tables for a set of languages.
///
/// Model is relatively heavyweight to load (disk I/O + parsing), but once loaded
/// it is immutable and can be shared across threads via `Arc<Model>`.
pub struct Model {
    tables: Vec<NgramTable>, // index 0..max_ngram-1 for sizes 1..max_ngram
    languages: Vec<Language>,
    lang_index_map: HashMap<Language, usize>,
    min_log_prob: f64,
    tw_min_log_prob: f64,
    max_ngram: usize,
    alphabets: Vec<Alphabet>, // local index -> Alphabet
    alphabet_index_map: HashMap<Alphabet, usize>,
    alphabet_to_lang_indices: Vec<Vec<usize>>,
    alphabet_implies_one_language: Vec<bool>,
    cj_alphabet: Vec<bool>,
    cj_classifier: Option<Arc<CJClassifier>>,
    topwords_table: NgramTable,
}

impl Model {
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn new(
        tables: Vec<NgramTable>,
        languages: Vec<Language>,
        min_log_prob: f64,
        tw_min_log_prob: f64,
        max_ngram: usize,
        cj_classifier: Option<Arc<CJClassifier>>,
        topwords_table: NgramTable,
    ) -> Self {
        let mut lang_index_map = HashMap::new();
        for (i, &lang) in languages.iter().enumerate() {
            lang_index_map.insert(lang, i);
        }

        // Collect union of alphabets from all languages
        let mut alpha_set: Vec<Alphabet> = Vec::new();
        for &lang in &languages {
            for &a in lang.alphabets() {
                if !alpha_set.contains(&a) {
                    alpha_set.push(a);
                }
            }
        }

        let mut alphabet_index_map = HashMap::new();
        for (i, &a) in alpha_set.iter().enumerate() {
            alphabet_index_map.insert(a, i);
        }

        // Build alphabet -> language index mapping
        let mut alphabet_to_lang_indices = Vec::with_capacity(alpha_set.len());
        let mut alphabet_implies_one_language = Vec::with_capacity(alpha_set.len());
        for &alpha in &alpha_set {
            let mut lang_indices = Vec::new();
            for (li, &lang) in languages.iter().enumerate() {
                if lang.uses_alphabet(alpha) {
                    lang_indices.push(li);
                }
            }
            alphabet_implies_one_language.push(lang_indices.len() == 1);
            alphabet_to_lang_indices.push(lang_indices);
        }

        let cj_alphabet: Vec<bool> = alpha_set
            .iter()
            .map(|a| *a == Alphabet::Han || *a == Alphabet::JaKana)
            .collect();

        Model {
            tables,
            languages,
            lang_index_map,
            min_log_prob,
            tw_min_log_prob,
            max_ngram,
            alphabets: alpha_set,
            alphabet_index_map,
            alphabet_to_lang_indices,
            alphabet_implies_one_language,
            cj_alphabet,
            cj_classifier,
            topwords_table,
        }
    }

    // ========================================================================
    // Factory methods
    // ========================================================================

    /// Loads a Model from a filesystem directory.
    pub fn load_from_path(dir: &str, languages: &[Language]) -> io::Result<Model> {
        model_loader::load_from_path(dir, languages, 0.0, 0.0, 0.0)
    }

    /// Loads a Model from a filesystem directory with tuning parameters.
    pub fn load_from_path_with_params(
        dir: &str,
        languages: &[Language],
        min_log_prob: f64,
        tw_min_log_prob: f64,
        cj_min_log_prob: f64,
    ) -> io::Result<Model> {
        model_loader::load_from_path(
            dir,
            languages,
            min_log_prob,
            tw_min_log_prob,
            cj_min_log_prob,
        )
    }

    /// Loads the lite model from embedded data (requires `lite` feature or
    /// `langidentify-models-lite` dev-dependency).
    #[cfg(any(feature = "lite", test))]
    pub fn load_lite(languages: &[Language]) -> io::Result<Model> {
        model_loader::load_embedded(
            langidentify_models_lite::resolve,
            languages,
            -12.0,
            -12.0,
            0.0,
        )
    }

    /// Loads the full model from embedded data (requires `full` feature or
    /// `langidentify-models-full` dev-dependency).
    #[cfg(feature = "full")]
    pub fn load_full(languages: &[Language]) -> io::Result<Model> {
        model_loader::load_embedded(
            langidentify_models_full::resolve,
            languages,
            -15.0,
            -15.0,
            0.0,
        )
    }

    /// Loads from embedded data using the provided resolver function.
    pub fn load_embedded(
        resolve: fn(&str) -> Option<&'static [u8]>,
        languages: &[Language],
        min_log_prob: f64,
        tw_min_log_prob: f64,
        cj_min_log_prob: f64,
    ) -> io::Result<Model> {
        model_loader::load_embedded(
            resolve,
            languages,
            min_log_prob,
            tw_min_log_prob,
            cj_min_log_prob,
        )
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    /// Returns the NgramTable for the given ngram size (1..max_ngram).
    pub fn table(&self, ngram_size: usize) -> &NgramTable {
        &self.tables[ngram_size - 1]
    }

    /// Returns the ordered slice of languages in this model.
    pub fn languages(&self) -> &[Language] {
        &self.languages
    }

    /// Returns the number of languages in this model.
    pub fn num_languages(&self) -> usize {
        self.languages.len()
    }

    /// Maps a language to its column index, or None if not in the model.
    pub fn lang_index(&self, lang: Language) -> Option<usize> {
        self.lang_index_map.get(&lang).copied()
    }

    /// Returns the minimum log-probability floor.
    pub fn min_log_prob(&self) -> f64 {
        self.min_log_prob
    }

    /// Returns the maximum ngram size in this model.
    pub fn max_ngram(&self) -> usize {
        self.max_ngram
    }

    /// Returns the ordered slice of active alphabets in this model.
    pub fn alphabets(&self) -> &[Alphabet] {
        &self.alphabets
    }

    /// Returns the number of active alphabets.
    pub fn num_alphabets(&self) -> usize {
        self.alphabets.len()
    }

    /// Maps an alphabet to its local index, or None.
    pub fn alphabet_index(&self, alpha: Alphabet) -> Option<usize> {
        self.alphabet_index_map.get(&alpha).copied()
    }

    /// Returns the language indices associated with a given alphabet index.
    pub fn lang_indices_for_alphabet(&self, alpha_idx: usize) -> &[usize] {
        &self.alphabet_to_lang_indices[alpha_idx]
    }

    /// Returns true if this alphabet index maps to exactly one language.
    pub fn alphabet_implies_one_language(&self, alpha_idx: usize) -> bool {
        self.alphabet_implies_one_language[alpha_idx]
    }

    /// For a unique alphabet, returns the single language it maps to.
    pub fn unique_language_for_alphabet(&self, alpha_idx: usize) -> Language {
        self.languages[self.alphabet_to_lang_indices[alpha_idx][0]]
    }

    /// Returns true if the alphabet at this index is part of the CJ group (HAN or JA_KANA).
    pub fn is_cj_alphabet(&self, alpha_idx: usize) -> bool {
        self.cj_alphabet.get(alpha_idx).copied().unwrap_or(false)
    }

    /// Returns the CJClassifier, or None.
    pub fn cj_classifier(&self) -> Option<&CJClassifier> {
        self.cj_classifier.as_deref()
    }

    /// Returns the topwords table.
    pub fn topwords_table(&self) -> &NgramTable {
        &self.topwords_table
    }

    /// Returns the minimum log-probability floor for topwords.
    pub fn tw_min_log_prob(&self) -> f64 {
        self.tw_min_log_prob
    }

    /// Builds a boost array for the Detector.
    pub fn build_boost_array(&self, boosts: &[(Language, f64)]) -> Vec<f64> {
        let mut arr = vec![0.0; self.languages.len()];
        for &(lang, boost) in boosts {
            if let Some(li) = self.lang_index(lang) {
                arr[li] = boost;
            }
        }
        arr
    }

    /// Convenience: build a boost array for a single language.
    pub fn build_boost_single(&self, lang: Language, boost: f64) -> Vec<f64> {
        self.build_boost_array(&[(lang, boost)])
    }
}
