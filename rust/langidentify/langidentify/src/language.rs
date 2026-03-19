// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

use crate::alphabet::Alphabet;
use std::collections::HashMap;
use std::sync::OnceLock;

/// Supported languages for detection.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Language {
    Unknown,
    // Latin alphabet
    // Afrikaans evolved from Dutch and they remain largely mutually intelligible.
    // See: https://en.wikipedia.org/wiki/Comparison_of_Afrikaans_and_Dutch
    // Having both may cause cross-detection (Afrikaans will frequently get detected as Dutch).
    Afrikaans,
    Albanian,
    Azerbaijani,
    Basque,
    Catalan,
    Croatian,
    Czech,
    Danish,
    Dutch,
    English,
    Esperanto,
    Estonian,
    Finnish,
    French,
    Ganda,
    German,
    Hungarian,
    Icelandic,
    Indonesian,
    Italian,
    Irish,
    Latin,
    Latvian,
    Lithuanian,
    Luxembourgish,
    // Malay and Indonesian are closely related standardizations of the same language.
    // See: https://en.wikipedia.org/wiki/Comparison_of_Indonesian_and_Standard_Malay
    // Having both may cause cross-detection (Malay will frequently get detected as Indonesian).
    Malay,
    Maori,
    // Norwegian: We have both no/nb for Norwegian Bokmal dialect, nn for Norwegian Nynorsk dialect.
    // If you just want a single Norwegian cluster, use "no", which is 4x the corpus size.
    // They're close enough that they sometimes cross-detect.
    Norwegian,
    Nynorsk,
    Oromo,
    Polish,
    Portuguese,
    Romanian,
    Shona,
    Slovak,
    Slovenian,
    Somali,
    Sotho,
    Spanish,
    Swahili,
    Swedish,
    Tagalog,
    Tsonga,
    Tswana,
    Turkish,
    Vietnamese,
    Welsh,
    Xhosa,
    Yoruba,
    Zulu,
    // CJK
    ChineseSimplified,
    ChineseTraditional,
    Japanese,
    Korean,
    // Arabic
    Arabic,
    Pashto,
    Persian,
    Urdu,
    // Cyrillic
    Belarusian,
    Bulgarian,
    Macedonian,
    Mongolian,
    Russian,
    Serbian,
    Ukrainian,
    // Ethiopic
    Amharic,
    Tigrinya,
    // Singleton alphabets
    Armenian,
    Bengali,
    Burmese,
    Georgian,
    Greek,
    Gujarati,
    Hebrew,
    Hindi,
    Kannada,
    Khmer,
    Lao,
    Malayalam,
    Punjabi,
    Sinhala,
    Tamil,
    Telugu,
    Thai,
}

struct LangInfo {
    iso_code: &'static str,
    iso_code3: &'static str,
    alt_names: &'static [&'static str],
    alphabets: &'static [Alphabet],
}

const LATIN: &[Alphabet] = &[Alphabet::Latin];
const CYRILLIC: &[Alphabet] = &[Alphabet::Cyrillic];
const ARABIC_ALPHA: &[Alphabet] = &[Alphabet::Arabic];

fn info(lang: Language) -> &'static LangInfo {
    static TABLE: OnceLock<Vec<LangInfo>> = OnceLock::new();
    let table = TABLE.get_or_init(build_info_table);
    &table[lang as usize]
}

fn build_info_table() -> Vec<LangInfo> {
    use Language::*;
    let mut v: Vec<LangInfo> = Vec::with_capacity(LANG_COUNT + 1);
    // Fill with placeholder, then overwrite
    for _ in 0..=LANG_COUNT {
        v.push(LangInfo {
            iso_code: "",
            iso_code3: "",
            alt_names: &[],
            alphabets: &[],
        });
    }
    macro_rules! set {
        ($lang:expr, $iso:expr, $iso3:expr, $alts:expr, $alpha:expr) => {
            v[$lang as usize] = LangInfo {
                iso_code: $iso,
                iso_code3: $iso3,
                alt_names: $alts,
                alphabets: $alpha,
            };
        };
    }
    set!(Unknown, "", "", &[], &[]);
    // Latin
    set!(Afrikaans, "af", "afr", &[], LATIN);
    set!(Albanian, "sq", "sqi", &[], LATIN);
    set!(Azerbaijani, "az", "aze", &[], LATIN);
    set!(Basque, "eu", "eus", &[], LATIN);
    set!(Catalan, "ca", "cat", &[], LATIN);
    set!(Croatian, "hr", "hrv", &[], LATIN);
    set!(Czech, "cs", "ces", &[], LATIN);
    set!(Danish, "da", "dan", &[], LATIN);
    set!(Dutch, "nl", "nld", &[], LATIN);
    set!(English, "en", "eng", &[], LATIN);
    set!(Esperanto, "eo", "epo", &[], LATIN);
    set!(Estonian, "et", "est", &[], LATIN);
    set!(Finnish, "fi", "fin", &[], LATIN);
    set!(French, "fr", "fra", &[], LATIN);
    set!(Ganda, "lg", "lug", &[], LATIN);
    set!(German, "de", "deu", &[], LATIN);
    set!(Hungarian, "hu", "hun", &[], LATIN);
    set!(Icelandic, "is", "isl", &[], LATIN);
    set!(Indonesian, "id", "ind", &[], LATIN);
    set!(Italian, "it", "ita", &[], LATIN);
    set!(Irish, "ga", "gle", &["gaelic"], LATIN);
    set!(Latin, "la", "lat", &[], LATIN);
    set!(Latvian, "lv", "lav", &[], LATIN);
    set!(Lithuanian, "lt", "lit", &[], LATIN);
    set!(Luxembourgish, "lb", "ltz", &[], LATIN);
    set!(Malay, "ms", "msa", &["malaysian"], LATIN);
    set!(Maori, "mi", "mri", &[], LATIN);
    set!(Norwegian, "no", "nor", &["bokmal", "nb"], LATIN);
    set!(Nynorsk, "nn", "nno", &[], LATIN);
    set!(Oromo, "om", "orm", &[], LATIN);
    set!(Polish, "pl", "pol", &[], LATIN);
    set!(Portuguese, "pt", "por", &[], LATIN);
    set!(Romanian, "ro", "ron", &[], LATIN);
    set!(Shona, "sn", "sna", &[], LATIN);
    set!(Slovak, "sk", "slk", &[], LATIN);
    set!(Slovenian, "sl", "slv", &["slovene"], LATIN);
    set!(Somali, "so", "som", &[], LATIN);
    set!(Sotho, "st", "sot", &[], LATIN);
    set!(Spanish, "es", "spa", &[], LATIN);
    set!(Swahili, "sw", "swa", &[], LATIN);
    set!(Swedish, "sv", "swe", &[], LATIN);
    set!(Tagalog, "tl", "tgl", &[], LATIN);
    set!(Tsonga, "ts", "tso", &[], LATIN);
    set!(Tswana, "tn", "tsn", &[], LATIN);
    set!(Turkish, "tr", "tur", &[], LATIN);
    set!(Vietnamese, "vi", "vie", &[], LATIN);
    set!(Welsh, "cy", "cym", &[], LATIN);
    set!(Xhosa, "xh", "xho", &[], LATIN);
    set!(Yoruba, "yo", "yor", &[], LATIN);
    set!(Zulu, "zu", "zul", &[], LATIN);
    // CJK
    set!(
        ChineseSimplified,
        "zh-hans",
        "zho-hans",
        &["chinese", "zh", "zh-cn", "zh-hans-cn", "zh-hans-sg"],
        &[Alphabet::Han]
    );
    set!(
        ChineseTraditional,
        "zh-hant",
        "zho-hant",
        &["zh-hant-hk", "zh-hk", "zh-hant-tw", "zh-tw"],
        &[Alphabet::Han]
    );
    set!(
        Japanese,
        "ja",
        "jpn",
        &["jp"],
        &[Alphabet::Han, Alphabet::JaKana]
    );
    set!(Korean, "ko", "kor", &[], &[Alphabet::Hangul]);
    // Arabic
    set!(Arabic, "ar", "ara", &[], ARABIC_ALPHA);
    set!(Pashto, "ps", "pus", &[], ARABIC_ALPHA);
    set!(Persian, "fa", "fas", &["farsi"], ARABIC_ALPHA);
    set!(Urdu, "ur", "urd", &[], ARABIC_ALPHA);
    // Cyrillic
    set!(Belarusian, "be", "bel", &[], CYRILLIC);
    set!(Bulgarian, "bg", "bul", &[], CYRILLIC);
    set!(Macedonian, "mk", "mkd", &[], CYRILLIC);
    set!(Mongolian, "mn", "mon", &[], CYRILLIC);
    set!(Russian, "ru", "rus", &[], CYRILLIC);
    set!(Serbian, "sr", "srp", &[], CYRILLIC);
    set!(Ukrainian, "uk", "ukr", &[], CYRILLIC);
    // Ethiopic
    set!(Amharic, "am", "amh", &[], &[Alphabet::Ethiopic]);
    set!(Tigrinya, "ti", "tig", &[], &[Alphabet::Ethiopic]);
    // Singleton alphabets
    set!(Armenian, "hy", "hye", &[], &[Alphabet::Armenian]);
    set!(Bengali, "bn", "ben", &[], &[Alphabet::Bengali]);
    set!(Burmese, "my", "mya", &["myanmar"], &[Alphabet::Myanmar]);
    set!(Georgian, "ka", "kat", &[], &[Alphabet::Georgian]);
    set!(Greek, "el", "ell", &[], &[Alphabet::Greek]);
    set!(Gujarati, "gu", "guj", &[], &[Alphabet::Gujarati]);
    set!(Hebrew, "he", "heb", &[], &[Alphabet::Hebrew]);
    set!(Hindi, "hi", "hin", &[], &[Alphabet::Devanagari]);
    set!(Kannada, "kn", "kan", &[], &[Alphabet::Kannada]);
    set!(Khmer, "km", "khm", &[], &[Alphabet::Khmer]);
    set!(Lao, "lo", "lao", &[], &[Alphabet::Lao]);
    set!(Malayalam, "ml", "mal", &[], &[Alphabet::Malayalam]);
    set!(Punjabi, "pa", "pan", &[], &[Alphabet::Gurmukhi]);
    set!(Sinhala, "si", "sin", &[], &[Alphabet::Sinhala]);
    set!(Tamil, "ta", "tam", &[], &[Alphabet::Tamil]);
    set!(Telugu, "te", "tel", &[], &[Alphabet::Telugu]);
    set!(Thai, "th", "tha", &[], &[Alphabet::Thai]);
    v
}

/// Number of language variants (excluding Unknown).
const LANG_COUNT: usize = 84;

/// All language variants in declaration order (excluding Unknown).
pub const ALL_LANGUAGES: [Language; LANG_COUNT] = {
    use Language::*;
    [
        Afrikaans,
        Albanian,
        Azerbaijani,
        Basque,
        Catalan,
        Croatian,
        Czech,
        Danish,
        Dutch,
        English,
        Esperanto,
        Estonian,
        Finnish,
        French,
        Ganda,
        German,
        Hungarian,
        Icelandic,
        Indonesian,
        Italian,
        Irish,
        Latin,
        Latvian,
        Lithuanian,
        Luxembourgish,
        Malay,
        Maori,
        Norwegian,
        Nynorsk,
        Oromo,
        Polish,
        Portuguese,
        Romanian,
        Shona,
        Slovak,
        Slovenian,
        Somali,
        Sotho,
        Spanish,
        Swahili,
        Swedish,
        Tagalog,
        Tsonga,
        Tswana,
        Turkish,
        Vietnamese,
        Welsh,
        Xhosa,
        Yoruba,
        Zulu,
        ChineseSimplified,
        ChineseTraditional,
        Japanese,
        Korean,
        Arabic,
        Pashto,
        Persian,
        Urdu,
        Belarusian,
        Bulgarian,
        Macedonian,
        Mongolian,
        Russian,
        Serbian,
        Ukrainian,
        Amharic,
        Tigrinya,
        Armenian,
        Bengali,
        Burmese,
        Georgian,
        Greek,
        Gujarati,
        Hebrew,
        Hindi,
        Kannada,
        Khmer,
        Lao,
        Malayalam,
        Punjabi,
        Sinhala,
        Tamil,
        Telugu,
        Thai,
    ]
};

impl Language {
    /// Returns the ISO 639-1 code (e.g. "en", "fr"), or empty string for `Unknown`.
    pub fn iso_code(&self) -> &'static str {
        info(*self).iso_code
    }

    /// Returns the ISO 639-3 code (e.g. "eng", "fra"), or empty string for `Unknown`.
    pub fn iso_code3(&self) -> &'static str {
        info(*self).iso_code3
    }

    /// Returns the set of alphabets (writing systems) used by this language.
    pub fn alphabets(&self) -> &'static [Alphabet] {
        info(*self).alphabets
    }

    /// Returns true if this language uses the given alphabet.
    pub fn uses_alphabet(&self, alpha: Alphabet) -> bool {
        self.alphabets().contains(&alpha)
    }

    /// Returns true if this is `ChineseSimplified` or `ChineseTraditional`.
    pub fn is_chinese(&self) -> bool {
        matches!(
            self,
            Language::ChineseSimplified | Language::ChineseTraditional
        )
    }

    /// Parses a language name. Accepts the enum name, ISO 639-1/639-3 codes, and aliases.
    /// Case-insensitive. Returns `Unknown` if unrecognized.
    pub fn from_string(s: &str) -> Language {
        by_name()
            .get(s.to_ascii_lowercase().as_str())
            .copied()
            .unwrap_or(Language::Unknown)
    }

    /// Parses a comma-separated string of language codes into a list.
    /// Supports group aliases like "cjk", "efigs", "europe_west_common", etc.
    pub fn from_comma_separated(s: &str) -> Result<Vec<Language>, String> {
        if s.is_empty() {
            return Ok(Vec::new());
        }
        let mut result = Vec::new();
        for code in s.split(',') {
            let code = code.trim().to_ascii_lowercase();
            let lang = Language::from_string(&code);
            if lang != Language::Unknown {
                result.push(lang);
            } else if let Some(group) = group_alias(&code) {
                result.extend_from_slice(group);
            } else {
                return Err(format!("Could not parse language: {}", code));
            }
        }
        Ok(result)
    }
}

fn by_name() -> &'static HashMap<String, Language> {
    static MAP: OnceLock<HashMap<String, Language>> = OnceLock::new();
    MAP.get_or_init(|| {
        let mut map = HashMap::new();
        for &lang in &ALL_LANGUAGES {
            // Enum name (lowercase)
            map.insert(format!("{:?}", lang).to_ascii_lowercase(), lang);
            let inf = info(lang);
            if !inf.iso_code.is_empty() {
                map.insert(inf.iso_code.to_string(), lang);
            }
            if !inf.iso_code3.is_empty() {
                map.insert(inf.iso_code3.to_string(), lang);
            }
            for &alt in inf.alt_names {
                map.insert(alt.to_string(), lang);
            }
        }
        map
    })
}

// ========================================================================
// Group aliases
// ========================================================================

use Language::*;

pub const EFIGS: &[Language] = &[English, French, Italian, German, Spanish];
pub const EFIGSNP: &[Language] = &[English, French, Italian, German, Spanish, Dutch, Portuguese];
/// Nordic languages — does not differentiate Norwegian Bokmal from Nynorsk.
pub const NORDIC_COMMON: &[Language] = &[Danish, Swedish, Norwegian, Finnish];

pub const CJ: &[Language] = &[ChineseSimplified, ChineseTraditional, Japanese];
pub const CJK: &[Language] = &[ChineseSimplified, ChineseTraditional, Japanese, Korean];

pub const EUROPE_WEST_COMMON: &[Language] = &[
    English, French, Italian, German, Spanish, Dutch, Portuguese, Danish, Swedish, Norwegian,
    Finnish,
];
pub const EUROPE_LATIN: &[Language] = &[
    Albanian,
    Basque,
    Catalan,
    Croatian,
    Czech,
    Danish,
    Dutch,
    English,
    Estonian,
    Finnish,
    French,
    German,
    Hungarian,
    Icelandic,
    Italian,
    Irish,
    Latvian,
    Lithuanian,
    Luxembourgish,
    Norwegian,
    Nynorsk,
    Polish,
    Portuguese,
    Romanian,
    Slovak,
    Slovenian,
    Spanish,
    Swedish,
    Turkish,
    Welsh,
];
pub const EUROPE_EAST_LATIN: &[Language] = &[
    Albanian, Croatian, Czech, Estonian, Hungarian, Latvian, Lithuanian, Polish, Romanian, Slovak,
    Slovenian,
];
pub const EUROPE_CYRILLIC: &[Language] = &[
    Belarusian, Bulgarian, Macedonian, Russian, Serbian, Ukrainian,
];

fn group_alias(name: &str) -> Option<&'static [Language]> {
    match name {
        "all" => Some(&ALL_LANGUAGES),
        "cj" => Some(CJ),
        "cjk" => Some(CJK),
        "efigs" => Some(EFIGS),
        "efigsnp" => Some(EFIGSNP),
        "nordic" => Some(NORDIC_COMMON),
        "europe_west_common" => Some(EUROPE_WEST_COMMON),
        "europe_latin" => Some(EUROPE_LATIN),
        "europe_east_latin" => Some(EUROPE_EAST_LATIN),
        "europe_cyrillic" => Some(EUROPE_CYRILLIC),
        "latin_alphabet" => {
            static LATIN_LANGS: OnceLock<Vec<Language>> = OnceLock::new();
            Some(LATIN_LANGS.get_or_init(|| languages_for_alphabet(Alphabet::Latin)))
        }
        "cyrillic_alphabet" => {
            static CYRILLIC_LANGS: OnceLock<Vec<Language>> = OnceLock::new();
            Some(CYRILLIC_LANGS.get_or_init(|| languages_for_alphabet(Alphabet::Cyrillic)))
        }
        "arabic_alphabet" => {
            static ARABIC_LANGS: OnceLock<Vec<Language>> = OnceLock::new();
            Some(ARABIC_LANGS.get_or_init(|| languages_for_alphabet(Alphabet::Arabic)))
        }
        "unique_alphabet" => {
            static UNIQUE_LANGS: OnceLock<Vec<Language>> = OnceLock::new();
            Some(UNIQUE_LANGS.get_or_init(unique_alphabet_languages))
        }
        _ => None,
    }
}

fn languages_for_alphabet(alpha: Alphabet) -> Vec<Language> {
    ALL_LANGUAGES
        .iter()
        .filter(|l| l.uses_alphabet(alpha))
        .copied()
        .collect()
}

fn unique_alphabet_languages() -> Vec<Language> {
    // Count how many languages use each alphabet
    let mut alpha_count: HashMap<Alphabet, usize> = HashMap::new();
    for &lang in &ALL_LANGUAGES {
        for &a in lang.alphabets() {
            *alpha_count.entry(a).or_insert(0) += 1;
        }
    }
    ALL_LANGUAGES
        .iter()
        .filter(|lang| {
            let alphas = lang.alphabets();
            alphas.len() == 1 && alpha_count.get(&alphas[0]).copied().unwrap_or(0) == 1
        })
        .copied()
        .collect()
}
