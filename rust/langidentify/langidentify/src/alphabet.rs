// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

/// Writing systems used to classify characters during language detection.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Alphabet {
    Unknown,
    Latin,
    Han,
    JaKana,
    Hangul,
    Arabic,
    Armenian,
    Bengali,
    Cyrillic,
    Devanagari,
    Ethiopic,
    Georgian,
    Greek,
    Gujarati,
    Gurmukhi,
    Hebrew,
    Kannada,
    Khmer,
    Lao,
    Malayalam,
    Myanmar,
    Sinhala,
    Tamil,
    Telugu,
    Thai,
}

/// Total number of known alphabet variants (excluding Unknown).
pub const ALPHABET_COUNT: usize = 24;

/// All known alphabet variants in declaration order (excluding Unknown).
pub const ALL_ALPHABETS: [Alphabet; ALPHABET_COUNT] = [
    Alphabet::Latin,
    Alphabet::Han,
    Alphabet::JaKana,
    Alphabet::Hangul,
    Alphabet::Arabic,
    Alphabet::Armenian,
    Alphabet::Bengali,
    Alphabet::Cyrillic,
    Alphabet::Devanagari,
    Alphabet::Ethiopic,
    Alphabet::Georgian,
    Alphabet::Greek,
    Alphabet::Gujarati,
    Alphabet::Gurmukhi,
    Alphabet::Hebrew,
    Alphabet::Kannada,
    Alphabet::Khmer,
    Alphabet::Lao,
    Alphabet::Malayalam,
    Alphabet::Myanmar,
    Alphabet::Sinhala,
    Alphabet::Tamil,
    Alphabet::Telugu,
    Alphabet::Thai,
];

impl Alphabet {
    /// Returns the alphabet for the given character, or `Unknown` if not recognized.
    pub fn get_alphabet(ch: char) -> Alphabet {
        let cp = ch as u32;
        if cp < 0x80 {
            return if ch.is_ascii_alphabetic() {
                Alphabet::Latin
            } else {
                Alphabet::Unknown
            };
        }
        match cp {
            // Latin extended blocks
            0x00C0..=0x02AF
            | 0x1D00..=0x1DBF
            | 0x1E00..=0x1EFF
            | 0x2C60..=0x2C7F
            | 0xA720..=0xA7FF
            | 0xAB30..=0xAB6F
            | 0xFF21..=0xFF3A
            | 0xFF41..=0xFF5A => Alphabet::Latin,
            // Greek
            0x0370..=0x03FF | 0x1F00..=0x1FFF => Alphabet::Greek,
            // Cyrillic
            0x0400..=0x04FF | 0x0500..=0x052F | 0x2DE0..=0x2DFF | 0xA640..=0xA69F => {
                Alphabet::Cyrillic
            }
            // Armenian
            0x0530..=0x058F | 0xFB13..=0xFB17 => Alphabet::Armenian,
            // Hebrew
            0x0590..=0x05FF | 0xFB1D..=0xFB4F => Alphabet::Hebrew,
            // Arabic
            0x0600..=0x06FF
            | 0x0750..=0x077F
            | 0x08A0..=0x08FF
            | 0xFB50..=0xFDFF
            | 0xFE70..=0xFEFF => Alphabet::Arabic,
            // Devanagari
            0x0900..=0x097F | 0xA8E0..=0xA8FF => Alphabet::Devanagari,
            // Bengali
            0x0980..=0x09FF => Alphabet::Bengali,
            // Gurmukhi
            0x0A00..=0x0A7F => Alphabet::Gurmukhi,
            // Gujarati
            0x0A80..=0x0AFF => Alphabet::Gujarati,
            // Tamil
            0x0B80..=0x0BFF => Alphabet::Tamil,
            // Telugu
            0x0C00..=0x0C7F => Alphabet::Telugu,
            // Kannada
            0x0C80..=0x0CFF => Alphabet::Kannada,
            // Malayalam
            0x0D00..=0x0D7F => Alphabet::Malayalam,
            // Sinhala
            0x0D80..=0x0DFF => Alphabet::Sinhala,
            // Thai
            0x0E00..=0x0E7F => Alphabet::Thai,
            // Lao
            0x0E80..=0x0EFF => Alphabet::Lao,
            // Myanmar
            0x1000..=0x109F | 0xAA60..=0xAA7F => Alphabet::Myanmar,
            // Georgian
            0x10A0..=0x10FF | 0x1C90..=0x1CBF | 0x2D00..=0x2D2F => Alphabet::Georgian,
            // Ethiopic
            0x1200..=0x137F | 0x1380..=0x139F | 0x2D80..=0x2DDF | 0xAB00..=0xAB2F => {
                Alphabet::Ethiopic
            }
            // Khmer
            0x1780..=0x17FF | 0x19E0..=0x19FF => Alphabet::Khmer,
            // Hangul
            0x1100..=0x11FF
            | 0x3130..=0x318F
            | 0xA960..=0xA97F
            | 0xAC00..=0xD7AF
            | 0xD7B0..=0xD7FF => Alphabet::Hangul,
            // Hiragana
            0x3040..=0x309F => Alphabet::JaKana,
            // Katakana
            0x30A0..=0x30FF | 0x31F0..=0x31FF | 0xFF65..=0xFF9F => Alphabet::JaKana,
            // CJK Unified Ideographs (Han)
            0x2E80..=0x2FFF
            | 0x3005..=0x3005
            | 0x3007..=0x3007
            | 0x3021..=0x3029
            | 0x3038..=0x303B
            | 0x3400..=0x4DBF
            | 0x4E00..=0x9FFF
            | 0xF900..=0xFAFF
            | 0x20000..=0x2A6DF
            | 0x2A700..=0x2B73F
            | 0x2B740..=0x2B81F
            | 0x2B820..=0x2CEAF
            | 0x2CEB0..=0x2EBEF
            | 0x30000..=0x3134F => Alphabet::Han,

            _ => Alphabet::Unknown,
        }
    }

    /// Detection weight for this alphabet. CJK characters carry more linguistic signal
    /// per character than Latin letters.
    pub fn weight(&self) -> f64 {
        match self {
            Alphabet::Han => 3.0,
            Alphabet::Hangul | Alphabet::JaKana => 2.0,
            Alphabet::Arabic => 1.25,
            _ => 1.0,
        }
    }

    /// Parses an alphabet name (case-insensitive). Returns `Unknown` if unrecognized.
    pub fn from_string(s: &str) -> Alphabet {
        match s.to_ascii_lowercase().as_str() {
            "latin" => Alphabet::Latin,
            "han" => Alphabet::Han,
            "ja_kana" | "jakana" => Alphabet::JaKana,
            "hangul" => Alphabet::Hangul,
            "arabic" => Alphabet::Arabic,
            "armenian" => Alphabet::Armenian,
            "bengali" => Alphabet::Bengali,
            "cyrillic" => Alphabet::Cyrillic,
            "devanagari" => Alphabet::Devanagari,
            "ethiopic" => Alphabet::Ethiopic,
            "georgian" => Alphabet::Georgian,
            "greek" => Alphabet::Greek,
            "gujarati" => Alphabet::Gujarati,
            "gurmukhi" => Alphabet::Gurmukhi,
            "hebrew" => Alphabet::Hebrew,
            "kannada" => Alphabet::Kannada,
            "khmer" => Alphabet::Khmer,
            "lao" => Alphabet::Lao,
            "malayalam" => Alphabet::Malayalam,
            "myanmar" => Alphabet::Myanmar,
            "sinhala" => Alphabet::Sinhala,
            "tamil" => Alphabet::Tamil,
            "telugu" => Alphabet::Telugu,
            "thai" => Alphabet::Thai,
            _ => Alphabet::Unknown,
        }
    }
}
