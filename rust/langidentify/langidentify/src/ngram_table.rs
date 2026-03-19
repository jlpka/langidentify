// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

use std::collections::HashMap;
use std::sync::Arc;

/// Computes the hash for a character slice, matching the Java implementation's
/// polynomial hash with MurmurHash3 finalization.
pub fn hash_ngram(chars: &[char]) -> u32 {
    let mut h: u32 = 0;
    for &c in chars {
        h = h.wrapping_mul(37).wrapping_add(c as u32);
    }
    // MurmurHash3 fmix32 partial finalizer
    h ^= h >> 16;
    h = h.wrapping_mul(0x85ebca6b);
    h ^= h >> 13;
    h
}

/// An entry in the immutable ngram table.
pub struct NgramEntry {
    /// Offset into the table's key_pool for this entry's key (in chars).
    key_offset: u32,
    /// Length of this entry's key in chars.
    key_len: u16,
    /// Language indices for this ngram. None = poisoned skipword entry.
    pub lang_indices: Option<Arc<[u32]>>,
    /// Offset into the table's prob_data array.
    pub prob_offset: u32,
    hash: u32,
}

/// Immutable open-addressing hash table mapping ngrams to per-language probability data.
pub struct NgramTable {
    entries: Box<[Option<NgramEntry>]>,
    mask: usize,
    entry_count: usize,
    prob_data: Box<[f32]>,
    key_pool: Box<[char]>,
}

impl NgramTable {
    pub fn empty() -> Self {
        NgramTable {
            entries: vec![None].into_boxed_slice(),
            mask: 0,
            entry_count: 0,
            prob_data: Box::new([]),
            key_pool: Box::new([]),
        }
    }

    /// Looks up the given ngram key. Returns the matching entry, or None.
    pub fn lookup(&self, key: &[char]) -> Option<&NgramEntry> {
        if self.entry_count == 0 {
            return None;
        }
        let hash = hash_ngram(key);
        let mut h = (hash as usize) & self.mask;
        loop {
            match &self.entries[h] {
                None => return None,
                Some(e) => {
                    if e.hash == hash && self.key_eq(e, key) {
                        return Some(e);
                    }
                }
            }
            h = (h + 1) & self.mask;
        }
    }

    #[inline]
    fn key_eq(&self, entry: &NgramEntry, key: &[char]) -> bool {
        let start = entry.key_offset as usize;
        let end = start + entry.key_len as usize;
        &self.key_pool[start..end] == key
    }

    /// Returns the flat array of all probability values.
    pub fn prob_data(&self) -> &[f32] {
        &self.prob_data
    }

    /// Returns the number of entries in the table.
    pub fn size(&self) -> usize {
        self.entry_count
    }
}

// ========================================================================
// Builder
// ========================================================================

/// Accumulates (langIndex, probability) entries during loading.
pub struct LangProbListBuilder {
    lang_indices: Vec<u32>,
    probs: Vec<f32>,
    poisoned: bool,
}

impl LangProbListBuilder {
    pub fn new() -> Self {
        LangProbListBuilder {
            lang_indices: Vec::with_capacity(4),
            probs: Vec::with_capacity(4),
            poisoned: false,
        }
    }

    /// Marks this builder as poisoned (skipword). Subsequent add() calls are ignored.
    pub fn poison(&mut self) {
        self.poisoned = true;
    }

    pub fn is_poisoned(&self) -> bool {
        self.poisoned
    }

    /// Adds a (langIndex, probability) entry. No-op if poisoned.
    pub fn add(&mut self, lang_idx: u32, prob: f32) {
        if !self.poisoned {
            self.lang_indices.push(lang_idx);
            self.probs.push(prob);
        }
    }

    pub fn size(&self) -> usize {
        self.lang_indices.len()
    }
}

/// Mutable builder for constructing an `NgramTable`. Uses an open-addressing hash table
/// internally for fast get/put during loading.
pub struct NgramTableBuilder {
    keys: Vec<Option<Box<[char]>>>,
    values: Vec<Option<LangProbListBuilder>>,
    hashes: Vec<u32>,
    mask: usize,
    size: usize,
}

const INITIAL_CAPACITY: usize = 1024;

impl NgramTableBuilder {
    pub fn new() -> Self {
        NgramTableBuilder {
            keys: vec![None; INITIAL_CAPACITY],
            values: vec_none(INITIAL_CAPACITY),
            hashes: vec![0u32; INITIAL_CAPACITY],
            mask: INITIAL_CAPACITY - 1,
            size: 0,
        }
    }

    /// Stores an entry in the builder table.
    pub fn put(&mut self, key: Box<[char]>, value: LangProbListBuilder) {
        if self.size * 4 >= self.keys.len() * 3 {
            self.resize();
        }
        let hash = hash_ngram(&key);
        let mut h = (hash as usize) & self.mask;
        loop {
            if self.keys[h].is_none() {
                self.keys[h] = Some(key);
                self.values[h] = Some(value);
                self.hashes[h] = hash;
                self.size += 1;
                return;
            }
            if self.hashes[h] == hash {
                if let Some(ref existing_key) = self.keys[h] {
                    if existing_key.as_ref() == key.as_ref() {
                        self.values[h] = Some(value);
                        return;
                    }
                }
            }
            h = (h + 1) & self.mask;
        }
    }

    /// Looks up the given ngram key and returns a mutable reference to its builder, or None.
    pub fn get_mut(&mut self, key: &[char]) -> Option<&mut LangProbListBuilder> {
        let hash = hash_ngram(key);
        let mut h = (hash as usize) & self.mask;
        loop {
            if self.keys[h].is_none() {
                return None;
            }
            if self.hashes[h] == hash {
                if let Some(ref existing_key) = self.keys[h] {
                    if existing_key.as_ref() == key {
                        return self.values[h].as_mut();
                    }
                }
            }
            h = (h + 1) & self.mask;
        }
    }

    fn resize(&mut self) {
        let new_cap = self.keys.len() * 2;
        let mut new_keys: Vec<Option<Box<[char]>>> = vec![None; new_cap];
        let mut new_values: Vec<Option<LangProbListBuilder>> = vec_none(new_cap);
        let mut new_hashes = vec![0u32; new_cap];
        let new_mask = new_cap - 1;

        for i in 0..self.keys.len() {
            if self.keys[i].is_some() {
                let hash = self.hashes[i];
                let mut h = (hash as usize) & new_mask;
                while new_keys[h].is_some() {
                    h = (h + 1) & new_mask;
                }
                new_keys[h] = self.keys[i].take();
                new_values[h] = self.values[i].take();
                new_hashes[h] = hash;
            }
        }
        self.keys = new_keys;
        self.values = new_values;
        self.hashes = new_hashes;
        self.mask = new_mask;
    }

    /// Compacts all builders into an immutable `NgramTable`, clamping probabilities
    /// below `prob_floor` up to that floor.
    pub fn compact(self, prob_floor: f32) -> NgramTable {
        let mut interner = IntArrayInterner::new();

        // First pass: compute total prob count and total key chars.
        let mut total_probs = 0usize;
        let mut total_key_chars = 0usize;
        for i in 0..self.keys.len() {
            if let Some(ref key) = self.keys[i] {
                total_key_chars += key.len();
                if let Some(ref val) = self.values[i] {
                    if !val.is_poisoned() {
                        total_probs += val.size();
                    }
                }
            }
        }
        let mut prob_data = vec![0.0f32; total_probs];
        let mut key_pool = vec!['\0'; total_key_chars];

        // Size the table: next power of 2 >= count * 4/3.
        let count = self.size;
        let min_capacity = (count * 4 / 3).max(1);
        let capacity = min_capacity.next_power_of_two().max(4);
        let out_mask = capacity - 1;
        let mut entries: Vec<Option<NgramEntry>> = (0..capacity).map(|_| None).collect();

        // Second pass: copy keys, probs, and insert.
        let mut prob_offset = 0usize;
        let mut key_offset = 0usize;
        for i in 0..self.keys.len() {
            if let Some(ref key) = self.keys[i] {
                let key_start = key_offset;
                let key_len = key.len();
                key_pool[key_start..key_start + key_len].copy_from_slice(key);
                key_offset += key_len;

                let val = self.values[i].as_ref().unwrap();
                let entry = if val.is_poisoned() {
                    NgramEntry {
                        key_offset: key_start as u32,
                        key_len: key_len as u16,
                        lang_indices: None,
                        prob_offset: 0,
                        hash: self.hashes[i],
                    }
                } else {
                    let sz = val.size();
                    prob_data[prob_offset..prob_offset + sz].copy_from_slice(&val.probs[..sz]);
                    // Clamp probabilities below floor
                    if prob_floor != 0.0 {
                        for j in prob_offset..prob_offset + sz {
                            if prob_data[j] < prob_floor {
                                prob_data[j] = prob_floor;
                            }
                        }
                    }
                    let interned = interner.intern(&val.lang_indices[..sz]);
                    let e = NgramEntry {
                        key_offset: key_start as u32,
                        key_len: key_len as u16,
                        lang_indices: Some(interned),
                        prob_offset: prob_offset as u32,
                        hash: self.hashes[i],
                    };
                    prob_offset += sz;
                    e
                };
                // Linear-probe insert
                let mut h = (entry.hash as usize) & out_mask;
                while entries[h].is_some() {
                    h = (h + 1) & out_mask;
                }
                entries[h] = Some(entry);
            }
        }

        NgramTable {
            entries: entries.into_boxed_slice(),
            mask: out_mask,
            entry_count: count,
            prob_data: prob_data.into_boxed_slice(),
            key_pool: key_pool.into_boxed_slice(),
        }
    }
}

fn vec_none(n: usize) -> Vec<Option<LangProbListBuilder>> {
    (0..n).map(|_| None).collect()
}

// ========================================================================
// IntArrayInterner
// ========================================================================

/// Interns `&[u32]` slices by content, so structurally equal arrays share one `Arc`.
struct IntArrayInterner {
    cache: HashMap<Vec<u32>, Arc<[u32]>>,
}

impl IntArrayInterner {
    fn new() -> Self {
        IntArrayInterner {
            cache: HashMap::new(),
        }
    }

    fn intern(&mut self, arr: &[u32]) -> Arc<[u32]> {
        if let Some(existing) = self.cache.get(arr) {
            return Arc::clone(existing);
        }
        let arc: Arc<[u32]> = arr.into();
        self.cache.insert(arr.to_vec(), Arc::clone(&arc));
        arc
    }
}
