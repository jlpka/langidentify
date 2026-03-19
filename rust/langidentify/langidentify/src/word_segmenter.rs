// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

use crate::alphabet::Alphabet;
use crate::model::Model;

/// Maximum length for alphabetic words; longer words are truncated.
pub const MAX_WORD_LEN: usize = 64;

/// Maximum length for CJ runs; longer runs are split into chunks of this size.
pub const MAX_CJ_WORD_LEN: usize = 256;

const HAS_DIGIT: u32 = 1;
const HAS_APOSTROPHE: u32 = 2;

/// Segments text into words by alphabet, handling lowercasing, apostrophe splitting,
/// digit filtering, and CJ run detection.
pub struct WordSegmenter {
    pub word_buf: [char; MAX_CJ_WORD_LEN],
    word_len: usize,
    word_start: i32,
    word_alpha_idx: i32,
    special_cases: u32,
}

impl WordSegmenter {
    pub fn new() -> Self {
        WordSegmenter {
            word_buf: ['\0'; MAX_CJ_WORD_LEN],
            word_len: 0,
            word_start: -1,
            word_alpha_idx: -1,
            special_cases: 0,
        }
    }

    /// Resets segmenter state for a new segment() call.
    fn init(&mut self) {
        self.word_len = 0;
        self.word_start = -1;
        self.word_alpha_idx = -1;
        self.special_cases = 0;
    }

    /// Segments text, calling `on_word(word_buf, word_len, alpha_idx, is_cj)` for each word.
    pub fn segment<F>(&mut self, text: &str, model: &Model, mut on_word: F)
    where
        F: FnMut(&[char], usize, usize, bool),
    {
        self.init();
        for (i, c) in text.chars().enumerate() {
            self.handle_char(c, i, model, &mut on_word);
        }
        if self.word_start >= 0 {
            if let Some((wlen, aidx, is_cj)) = self.emit_word(model) {
                on_word(&self.word_buf, wlen, aidx, is_cj);
            }
        }
    }

    /// Segments a char slice.
    pub fn segment_chars<F>(&mut self, text: &[char], model: &Model, mut on_word: F)
    where
        F: FnMut(&[char], usize, usize, bool),
    {
        self.init();
        for (i, &c) in text.iter().enumerate() {
            self.handle_char(c, i, model, &mut on_word);
        }
        if self.word_start >= 0 {
            if let Some((wlen, aidx, is_cj)) = self.emit_word(model) {
                on_word(&self.word_buf, wlen, aidx, is_cj);
            }
        }
    }

    /// Process a character, calling on_word when a word boundary is reached.
    fn handle_char<F>(
        &mut self,
        c: char,
        pos: usize,
        model: &Model,
        on_word: &mut F,
    ) where
        F: FnMut(&[char], usize, usize, bool),
    {
        let alpha = Alphabet::get_alphabet(c);
        let alpha_idx = model.alphabet_index(alpha);

        if let Some(aidx) = alpha_idx {
            if self.word_alpha_idx >= 0
                && aidx as i32 != self.word_alpha_idx
                && self.word_start >= 0
            {
                if let Some((wlen, wa, is_cj)) = self.emit_word(model) {
                    on_word(&self.word_buf, wlen, wa, is_cj);
                }
            }
            if self.word_len < MAX_WORD_LEN {
                self.word_buf[self.word_len] = c.to_lowercase().next().unwrap_or(c);
                self.word_len += 1;
            } else if model.is_cj_alphabet(self.word_alpha_idx as usize) {
                // CJ special case: higher limit + emit partial word
                if self.word_len == MAX_CJ_WORD_LEN {
                    if let Some((wlen, wa, is_cj)) = self.emit_word(model) {
                        on_word(&self.word_buf, wlen, wa, is_cj);
                    }
                }
                if self.word_len < MAX_CJ_WORD_LEN {
                    self.word_buf[self.word_len] = c;
                    self.word_len += 1;
                }
            }
            if self.word_start < 0 {
                self.word_start = pos as i32;
            }
            self.word_alpha_idx = aidx as i32;
        } else if c >= '0' && c <= '9' {
            self.special_cases |= HAS_DIGIT;
        } else if self.word_start >= 0
            && (c == '\'' || c == '\u{2019}' || c == '\u{0092}')
            && self.word_len > 0
            && self.word_buf[self.word_len - 1] != '\''
        {
            self.special_cases |= HAS_APOSTROPHE;
            if self.word_len < MAX_WORD_LEN {
                self.word_buf[self.word_len] = '\'';
                self.word_len += 1;
            }
        } else {
            if self.word_start >= 0 {
                if let Some((wlen, wa, is_cj)) = self.emit_word(model) {
                    on_word(&self.word_buf, wlen, wa, is_cj);
                }
            }
            self.special_cases = 0;
        }
    }

    /// Emits the current word. Returns Some((word_len, alpha_idx, is_cj)) if word is valid.
    fn emit_word(&mut self, model: &Model) -> Option<(usize, usize, bool)> {
        let alpha_idx = self.word_alpha_idx as usize;

        // Special-case filtering
        if self.special_cases != 0 {
            if (self.special_cases & HAS_DIGIT) != 0
                && model.alphabets()[alpha_idx] == Alphabet::Latin
            {
                self.word_len = 0;
                self.word_start = -1;
                self.special_cases = 0;
                return None;
            }
            if (self.special_cases & HAS_APOSTROPHE) != 0
                && self.word_len > 0
                && self.word_buf[self.word_len - 1] == '\''
            {
                self.word_len -= 1;
                if self.word_len == 0 {
                    self.word_start = -1;
                    self.special_cases = 0;
                    return None;
                }
            }
        }

        let is_cj = model.is_cj_alphabet(alpha_idx);
        let wlen = self.word_len;

        self.word_len = 0;
        self.word_start = -1;
        self.special_cases = 0;

        Some((wlen, alpha_idx, is_cj))
    }
}
