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

/// Minimum width (1, 2, or 4 bytes) needed to represent all chars in the key.
fn min_width(chars: &[char]) -> u8 {
    let mut max_cp: u32 = 0;
    for &c in chars {
        let cp = c as u32;
        if cp > max_cp {
            max_cp = cp;
        }
    }
    if max_cp < 0x100 {
        1
    } else if max_cp < 0x10000 {
        2
    } else {
        4
    }
}

/// An entry in the immutable ngram table.
pub struct NgramEntry {
    /// Byte offset into the table's key_pool.
    key_offset: u32,
    /// Number of characters in the key.
    key_len: u16,
    /// Bytes per character in the key pool (1, 2, or 4).
    key_width: u8,
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
    key_pool: Box<[u8]>,
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
        let len = entry.key_len as usize;
        if len != key.len() {
            return false;
        }
        let start = entry.key_offset as usize;
        match entry.key_width {
            1 => {
                let pool = &self.key_pool[start..];
                for i in 0..len {
                    if pool[i] as u32 != key[i] as u32 {
                        return false;
                    }
                }
                true
            }
            2 => {
                let pool = &self.key_pool[start..];
                for i in 0..len {
                    let off = i * 2;
                    let stored = u16::from_le_bytes([pool[off], pool[off + 1]]) as u32;
                    if stored != key[i] as u32 {
                        return false;
                    }
                }
                true
            }
            _ => {
                let pool = &self.key_pool[start..];
                for i in 0..len {
                    let off = i * 4;
                    let stored =
                        u32::from_le_bytes([pool[off], pool[off + 1], pool[off + 2], pool[off + 3]]);
                    if stored != key[i] as u32 {
                        return false;
                    }
                }
                true
            }
        }
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

        // First pass: compute total prob count and total key pool bytes.
        let mut total_probs = 0usize;
        let mut total_key_bytes = 0usize;
        for i in 0..self.keys.len() {
            if let Some(ref key) = self.keys[i] {
                let width = min_width(key) as usize;
                total_key_bytes += key.len() * width;
                if let Some(ref val) = self.values[i] {
                    if !val.is_poisoned() {
                        total_probs += val.size();
                    }
                }
            }
        }
        let mut prob_data = vec![0.0f32; total_probs];
        let mut key_pool = vec![0u8; total_key_bytes];

        // Size the table: next power of 2 >= count * 4/3.
        let count = self.size;
        let min_capacity = (count * 4 / 3).max(1);
        let capacity = min_capacity.next_power_of_two().max(4);
        let out_mask = capacity - 1;
        let mut entries: Vec<Option<NgramEntry>> = (0..capacity).map(|_| None).collect();

        // Second pass: write keys to pool, copy probs, and insert entries.
        let mut prob_offset = 0usize;
        let mut key_byte_offset = 0usize;
        for i in 0..self.keys.len() {
            if let Some(ref key) = self.keys[i] {
                let width = min_width(key);
                let key_start = key_byte_offset;
                let key_len = key.len();

                // Write key chars to pool at the determined width.
                match width {
                    1 => {
                        for (j, &c) in key.iter().enumerate() {
                            key_pool[key_byte_offset + j] = c as u8;
                        }
                    }
                    2 => {
                        for (j, &c) in key.iter().enumerate() {
                            let bytes = (c as u16).to_le_bytes();
                            key_pool[key_byte_offset + j * 2] = bytes[0];
                            key_pool[key_byte_offset + j * 2 + 1] = bytes[1];
                        }
                    }
                    _ => {
                        for (j, &c) in key.iter().enumerate() {
                            let bytes = (c as u32).to_le_bytes();
                            let off = key_byte_offset + j * 4;
                            key_pool[off] = bytes[0];
                            key_pool[off + 1] = bytes[1];
                            key_pool[off + 2] = bytes[2];
                            key_pool[off + 3] = bytes[3];
                        }
                    }
                }
                key_byte_offset += key_len * width as usize;

                let val = self.values[i].as_ref().unwrap();
                let entry = if val.is_poisoned() {
                    NgramEntry {
                        key_offset: key_start as u32,
                        key_len: key_len as u16,
                        key_width: width,
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
                        key_width: width,
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a minimal NgramTable containing the given keys with distinct single-lang entries.
    fn build_table(keys: &[&[char]]) -> NgramTable {
        let mut builder = NgramTableBuilder::new();
        for (i, key) in keys.iter().enumerate() {
            let mut lpb = LangProbListBuilder::new();
            lpb.add(i as u32, 0.5);
            builder.put(key.to_vec().into_boxed_slice(), lpb);
        }
        builder.compact(0.0)
    }

    /// Helper: build table from string keys with specified (lang_idx, prob) entries.
    fn add_entry(builder: &mut NgramTableBuilder, key: &str, lang_idx: u32, prob: f32) {
        let chars: Vec<char> = key.chars().collect();
        if let Some(lpb) = builder.get_mut(&chars) {
            lpb.add(lang_idx, prob);
        } else {
            let mut lpb = LangProbListBuilder::new();
            lpb.add(lang_idx, prob);
            builder.put(chars.into_boxed_slice(), lpb);
        }
    }

    fn chars(s: &str) -> Vec<char> {
        s.chars().collect()
    }

    // ========================================================================
    // Width truncation tests
    // ========================================================================

    #[test]
    fn lookup_width1_no_false_match_on_truncation() {
        let key_a: Vec<char> = vec!['A'];
        let table = build_table(&[&key_a]);

        assert!(table.lookup(&['A']).is_some());
        assert!(table.lookup(&['\u{0141}']).is_none()); // Ł
        assert!(table.lookup(&['\u{0241}']).is_none()); // Ɂ
    }

    #[test]
    fn lookup_width2_no_false_match_on_truncation() {
        let key_alpha: Vec<char> = vec!['\u{03B1}'];
        let table = build_table(&[&key_alpha]);

        assert!(table.lookup(&['\u{03B1}']).is_some());
        assert!(table.lookup(&['\u{103B1}']).is_none());
    }

    #[test]
    fn lookup_multi_char_key() {
        let key_ab: Vec<char> = vec!['a', 'b'];
        let table = build_table(&[&key_ab]);

        assert!(table.lookup(&['a', 'b']).is_some());
        assert!(table.lookup(&['\u{0161}', 'b']).is_none()); // š
        assert!(table.lookup(&['a', '\u{0162}']).is_none()); // Ţ
    }

    // ========================================================================
    // Empty table
    // ========================================================================

    #[test]
    fn empty_table_lookup_returns_none() {
        let table = NgramTableBuilder::new().compact(0.0);
        assert!(table.lookup(&chars("th")).is_none());
    }

    #[test]
    fn empty_table_size_is_zero() {
        let table = NgramTableBuilder::new().compact(0.0);
        assert_eq!(0, table.size());
    }

    #[test]
    fn empty_table_prob_data_is_empty() {
        let table = NgramTableBuilder::new().compact(0.0);
        assert_eq!(0, table.prob_data().len());
    }

    // ========================================================================
    // Single entry
    // ========================================================================

    #[test]
    fn single_entry_lookup_finds_it() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "th", 0, -2.5);
        let table = b.compact(0.0);

        let entry = table.lookup(&chars("th"));
        assert!(entry.is_some());
        let entry = entry.unwrap();
        let li = entry.lang_indices.as_ref().unwrap();
        assert_eq!(&[0u32], li.as_ref());
        assert!((table.prob_data()[entry.prob_offset as usize] - (-2.5)).abs() < 1e-6);
    }

    #[test]
    fn single_entry_size_is_one() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "th", 0, -2.5);
        let table = b.compact(0.0);
        assert_eq!(1, table.size());
    }

    #[test]
    fn single_entry_lookup_miss_returns_none() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "th", 0, -2.5);
        let table = b.compact(0.0);
        assert!(table.lookup(&chars("he")).is_none());
    }

    // ========================================================================
    // Multiple entries
    // ========================================================================

    #[test]
    fn multiple_entries_all_found() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "th", 0, -2.0);
        add_entry(&mut b, "he", 1, -3.0);
        add_entry(&mut b, "in", 2, -4.0);
        let table = b.compact(0.0);

        assert_eq!(3, table.size());

        let e1 = table.lookup(&chars("th")).unwrap();
        assert!((table.prob_data()[e1.prob_offset as usize] - (-2.0)).abs() < 1e-6);

        let e2 = table.lookup(&chars("he")).unwrap();
        assert!((table.prob_data()[e2.prob_offset as usize] - (-3.0)).abs() < 1e-6);

        let e3 = table.lookup(&chars("in")).unwrap();
        assert!((table.prob_data()[e3.prob_offset as usize] - (-4.0)).abs() < 1e-6);
    }

    #[test]
    fn multiple_entries_miss_returns_none() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "th", 0, -2.0);
        add_entry(&mut b, "he", 1, -3.0);
        let table = b.compact(0.0);
        assert!(table.lookup(&chars("zz")).is_none());
    }

    // ========================================================================
    // Multi-language entries
    // ========================================================================

    #[test]
    fn multi_lang_entry_all_languages_present() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "the", 0, -1.5);
        add_entry(&mut b, "the", 1, -3.0);
        add_entry(&mut b, "the", 2, -5.0);
        let table = b.compact(0.0);

        assert_eq!(1, table.size());

        let entry = table.lookup(&chars("the")).unwrap();
        let li = entry.lang_indices.as_ref().unwrap();
        assert_eq!(3, li.len());

        let pd = table.prob_data();
        let mut found = [false; 3];
        for j in 0..li.len() {
            let idx = li[j] as usize;
            let prob = pd[entry.prob_offset as usize + j];
            match idx {
                0 => { assert!((prob - (-1.5)).abs() < 1e-6); found[0] = true; }
                1 => { assert!((prob - (-3.0)).abs() < 1e-6); found[1] = true; }
                2 => { assert!((prob - (-5.0)).abs() < 1e-6); found[2] = true; }
                _ => panic!("unexpected lang index"),
            }
        }
        assert!(found[0] && found[1] && found[2]);
    }

    // ========================================================================
    // probData flat array
    // ========================================================================

    #[test]
    fn prob_data_contains_all_values() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "aa", 0, -1.0);
        add_entry(&mut b, "aa", 1, -2.0);
        add_entry(&mut b, "bb", 0, -3.0);
        let table = b.compact(0.0);

        // Total probs = 2 (for "aa") + 1 (for "bb") = 3
        assert_eq!(3, table.prob_data().len());
    }

    // ========================================================================
    // langIndices interning
    // ========================================================================

    #[test]
    fn lang_indices_interning_same_patterns_share_arc() {
        let mut b = NgramTableBuilder::new();
        // Two different ngrams, both mapping to languages {0, 1}
        add_entry(&mut b, "ab", 0, -1.0);
        add_entry(&mut b, "ab", 1, -2.0);
        add_entry(&mut b, "cd", 0, -3.0);
        add_entry(&mut b, "cd", 1, -4.0);
        let table = b.compact(0.0);

        let e1 = table.lookup(&chars("ab")).unwrap();
        let e2 = table.lookup(&chars("cd")).unwrap();
        let li1 = e1.lang_indices.as_ref().unwrap();
        let li2 = e2.lang_indices.as_ref().unwrap();
        // Interned: same Arc instance
        assert!(Arc::ptr_eq(li1, li2));
    }

    #[test]
    fn lang_indices_interning_different_patterns_not_shared() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "ab", 0, -1.0); // {0}
        add_entry(&mut b, "cd", 1, -2.0); // {1}
        let table = b.compact(0.0);

        let e1 = table.lookup(&chars("ab")).unwrap();
        let e2 = table.lookup(&chars("cd")).unwrap();
        let li1 = e1.lang_indices.as_ref().unwrap();
        let li2 = e2.lang_indices.as_ref().unwrap();
        assert!(!Arc::ptr_eq(li1, li2));
    }

    // ========================================================================
    // Different ngram lengths
    // ========================================================================

    #[test]
    fn different_lengths_do_not_collide() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "a", 0, -1.0);
        add_entry(&mut b, "ab", 0, -2.0);
        add_entry(&mut b, "abc", 0, -3.0);
        let table = b.compact(0.0);

        assert_eq!(3, table.size());

        let e1 = table.lookup(&chars("a")).unwrap();
        let e2 = table.lookup(&chars("ab")).unwrap();
        let e3 = table.lookup(&chars("abc")).unwrap();
        assert!((table.prob_data()[e1.prob_offset as usize] - (-1.0)).abs() < 1e-6);
        assert!((table.prob_data()[e2.prob_offset as usize] - (-2.0)).abs() < 1e-6);
        assert!((table.prob_data()[e3.prob_offset as usize] - (-3.0)).abs() < 1e-6);
    }

    // ========================================================================
    // Many entries (stress open-addressing)
    // ========================================================================

    #[test]
    fn many_entries_all_retrievable() {
        let mut b = NgramTableBuilder::new();
        let count = 1000;
        for i in 0..count {
            let key = format!("{:04}", i);
            add_entry(&mut b, &key, 0, -(i as f32));
        }
        let table = b.compact(0.0);

        assert_eq!(count, table.size());

        for i in 0..count {
            let key = format!("{:04}", i);
            let c: Vec<char> = key.chars().collect();
            let entry = table.lookup(&c);
            assert!(entry.is_some(), "Missing entry for: {}", key);
            assert!(
                (table.prob_data()[entry.unwrap().prob_offset as usize] - (-(i as f32))).abs()
                    < 1e-6
            );
        }

        // Verify misses
        assert!(table.lookup(&chars("9999")).is_none());
        assert!(table.lookup(&chars("abcd")).is_none());
    }

    // ========================================================================
    // Builder: get_mut during loading
    // ========================================================================

    #[test]
    fn builder_get_mut_returns_existing() {
        let mut b = NgramTableBuilder::new();
        let mut lpb = LangProbListBuilder::new();
        lpb.add(0, -1.0);
        b.put("th".chars().collect::<Vec<_>>().into_boxed_slice(), lpb);

        let found = b.get_mut(&chars("th"));
        assert!(found.is_some());
        assert_eq!(1, found.unwrap().size());
    }

    #[test]
    fn builder_get_mut_returns_none_for_missing() {
        let b = NgramTableBuilder::new();
        // Need mutable reference but we know it's empty
        let mut b = b;
        assert!(b.get_mut(&chars("th")).is_none());
    }

    // ========================================================================
    // Unicode ngrams
    // ========================================================================

    #[test]
    fn unicode_ngrams_lookup_works() {
        let mut b = NgramTableBuilder::new();
        add_entry(&mut b, "日本", 0, -1.0);
        add_entry(&mut b, "中国", 1, -2.0);
        add_entry(&mut b, "café", 2, -3.0);
        let table = b.compact(0.0);

        assert_eq!(3, table.size());
        assert!(table.lookup(&chars("日本")).is_some());
        assert!(table.lookup(&chars("中国")).is_some());
        assert!(table.lookup(&chars("café")).is_some());
        assert!(table.lookup(&chars("한국")).is_none());
    }

    // ========================================================================
    // Poisoned (skipword) entries
    // ========================================================================

    #[test]
    fn poisoned_entry_has_no_lang_indices() {
        let mut b = NgramTableBuilder::new();
        let mut lpb = LangProbListBuilder::new();
        lpb.poison();
        b.put("http".chars().collect::<Vec<_>>().into_boxed_slice(), lpb);
        let table = b.compact(0.0);

        assert_eq!(1, table.size());
        let entry = table.lookup(&chars("http")).unwrap();
        assert!(entry.lang_indices.is_none());
    }
}
