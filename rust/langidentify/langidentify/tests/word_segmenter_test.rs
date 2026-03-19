use langidentify::word_segmenter::{WordSegmenter, MAX_CJ_WORD_LEN, MAX_WORD_LEN};
use langidentify::{Language, Model};
use std::sync::Arc;

struct Word {
    text: String,
}

fn latin_model() -> Arc<Model> {
    let languages = Language::from_comma_separated("en,fr").unwrap();
    Arc::new(
        Model::load_embedded(
            langidentify_models_lite::resolve,
            &languages,
            -10.0,
            -10.0,
            0.0,
        )
        .unwrap(),
    )
}

fn cj_model() -> Arc<Model> {
    let languages = Language::from_comma_separated("en,ja").unwrap();
    Arc::new(
        Model::load_embedded(
            langidentify_models_lite::resolve,
            &languages,
            -10.0,
            -10.0,
            0.0,
        )
        .unwrap(),
    )
}

fn segment_words(text: &str) -> Vec<Word> {
    let model = latin_model();
    let mut seg = WordSegmenter::new();
    let mut words = Vec::new();
    seg.segment(text, &model, |buf, len, _aidx, _is_cj| {
        words.push(Word {
            text: buf[..len].iter().collect(),
        });
    });
    words
}

fn segment_with_model(text: &str, model: &Model) -> (Vec<Word>, Vec<Word>) {
    let mut seg = WordSegmenter::new();
    let mut words = Vec::new();
    let mut cj_runs = Vec::new();
    seg.segment(text, model, |buf, len, _aidx, is_cj| {
        let w = Word {
            text: buf[..len].iter().collect(),
        };
        if is_cj {
            cj_runs.push(w);
        } else {
            words.push(w);
        }
    });
    (words, cj_runs)
}

// ========================================================================
// Basic word splitting
// ========================================================================

#[test]
fn empty_string() {
    let words = segment_words("");
    assert_eq!(0, words.len());
}

#[test]
fn single_word() {
    let words = segment_words("hello");
    assert_eq!(1, words.len());
    assert_eq!("hello", words[0].text);
}

#[test]
fn multiple_words() {
    let words = segment_words("hello world foo");
    assert_eq!(3, words.len());
    assert_eq!("hello", words[0].text);
    assert_eq!("world", words[1].text);
    assert_eq!("foo", words[2].text);
}

#[test]
fn leading_and_trailing_spaces() {
    let words = segment_words("  hello  ");
    assert_eq!(1, words.len());
    assert_eq!("hello", words[0].text);
}

#[test]
fn multiple_separators() {
    let words = segment_words("hello---world");
    assert_eq!(2, words.len());
    assert_eq!("hello", words[0].text);
    assert_eq!("world", words[1].text);
}

// ========================================================================
// Lowercasing
// ========================================================================

#[test]
fn lowercases_words() {
    let words = segment_words("Hello WORLD FoO");
    assert_eq!("hello", words[0].text);
    assert_eq!("world", words[1].text);
    assert_eq!("foo", words[2].text);
}

// ========================================================================
// Apostrophe handling
// ========================================================================

#[test]
fn apostrophe_in_word() {
    let words = segment_words("don't");
    assert_eq!(1, words.len());
    assert_eq!("don't", words[0].text);
}

#[test]
fn smart_apostrophe() {
    // U+2019 right single quotation mark
    let words = segment_words("don\u{2019}t");
    assert_eq!(1, words.len());
    assert_eq!("don't", words[0].text);
    // U+0092 private use (Windows-1252 apostrophe)
    let words = segment_words("don\u{0092}t");
    assert_eq!(1, words.len());
    assert_eq!("don't", words[0].text);
}

#[test]
fn trailing_apostrophe_stripped() {
    let words = segment_words("dogs' ");
    assert_eq!(1, words.len());
    assert_eq!("dogs", words[0].text);
}

#[test]
fn only_apostrophe_after_letter_dropped() {
    let words = segment_words("x' y");
    assert_eq!(2, words.len());
    assert_eq!("x", words[0].text);
    assert_eq!("y", words[1].text);
}

#[test]
fn double_apostrophe_not_added() {
    let words = segment_words("don''t");
    assert_eq!(2, words.len());
    assert_eq!("don", words[0].text);
    assert_eq!("t", words[1].text);
}

// ========================================================================
// Digit filtering (Latin only)
// ========================================================================

#[test]
fn digit_poisons_latin_word() {
    let words = segment_words("abc123def");
    assert_eq!(0, words.len());
}

#[test]
fn digit_between_latin_words() {
    let words = segment_words("hello 42world");
    assert_eq!(1, words.len());
    assert_eq!("hello", words[0].text);
}

#[test]
fn pure_digits_no_emission() {
    let words = segment_words("12345");
    assert_eq!(0, words.len());
}

// ========================================================================
// Alphabet boundaries
// ========================================================================

#[test]
fn alphabet_boundary_splits_word() {
    let model = cj_model();
    let (words, _cj) = segment_with_model("hello\u{4e00}", &model);
    // "hello" is Latin, \u{4e00} is HAN — should be separate
    assert!(words.len() >= 1);
    assert_eq!("hello", words[0].text);
}

#[test]
fn unknown_alphabet_chars_ignored() {
    // Cyrillic А not in latin model
    let words = segment_words("hello\u{0410}world");
    assert_eq!(2, words.len());
    assert_eq!("hello", words[0].text);
    assert_eq!("world", words[1].text);
}

// ========================================================================
// CJ consumer routing
// ========================================================================

#[test]
fn cj_unified_routed_as_cj() {
    let model = cj_model();
    let (words, cj_runs) = segment_with_model("hello \u{4e00}\u{4e8c}\u{4e09}", &model);

    assert_eq!(1, words.len());
    assert_eq!("hello", words[0].text);

    assert_eq!(1, cj_runs.len());
    assert_eq!("\u{4e00}\u{4e8c}\u{4e09}", cj_runs[0].text);
}

#[test]
fn ja_kana_routed_as_cj() {
    let model = cj_model();
    let (words, cj_runs) = segment_with_model("hello \u{3042}\u{3044}\u{3046}", &model);

    assert_eq!(1, words.len());
    assert_eq!("hello", words[0].text);

    assert_eq!(1, cj_runs.len());
    assert_eq!("\u{3042}\u{3044}\u{3046}", cj_runs[0].text);
}

#[test]
fn mixed_kana_and_han_split_separately() {
    let model = cj_model();
    let (_words, cj_runs) = segment_with_model("\u{3042}\u{3044}\u{4e00}\u{4e8c}", &model);

    assert_eq!(2, cj_runs.len());
    assert_eq!("\u{3042}\u{3044}", cj_runs[0].text);
    assert_eq!("\u{4e00}\u{4e8c}", cj_runs[1].text);
}

#[test]
fn cj_run_with_latin() {
    let model = cj_model();
    let (words, cj_runs) = segment_with_model("abc \u{4e00}\u{4e8c}\u{4e09} xyz", &model);

    assert_eq!(2, words.len()); // "abc", "xyz"
    assert_eq!(1, cj_runs.len());
    assert_eq!("\u{4e00}\u{4e8c}\u{4e09}", cj_runs[0].text);
}

// ========================================================================
// Reuse across calls
// ========================================================================

#[test]
fn segmenter_reusable_between_calls() {
    let model = latin_model();
    let mut seg = WordSegmenter::new();
    let mut all_words: Vec<String> = Vec::new();

    seg.segment("hello", &model, |buf, len, _aidx, _is_cj| {
        all_words.push(buf[..len].iter().collect());
    });
    assert_eq!(1, all_words.len());
    assert_eq!("hello", all_words[0]);

    seg.segment("world foo", &model, |buf, len, _aidx, _is_cj| {
        all_words.push(buf[..len].iter().collect());
    });
    assert_eq!(3, all_words.len());
    assert_eq!("world", all_words[1]);
    assert_eq!("foo", all_words[2]);
}

#[test]
fn extract_cj_runs_and_long_words() {
    let model = cj_model();
    let mut seg = WordSegmenter::new();
    let mut words: Vec<String> = Vec::new();
    let mut cj_runs: Vec<String> = Vec::new();

    let blob_latin = "abcdefghij";
    let blob_cj = "那只敏捷的棕色狐狸跳过了那只懒惰的狗";

    let mut text = String::new();
    text.push_str(blob_latin);
    text.push_str(blob_cj);
    // Exceed MAX_WORD_LEN (count in chars, not bytes)
    let blob_latin_chars = blob_latin.chars().count();
    let blob_cj_chars = blob_cj.chars().count();
    let mut i = 0;
    while i < MAX_WORD_LEN + 5 {
        text.push_str(blob_latin);
        i += blob_latin_chars;
    }
    // Exceed MAX_CJ_WORD_LEN
    i = 0;
    while i < MAX_CJ_WORD_LEN + 5 {
        text.push_str(blob_cj);
        i += blob_cj_chars;
    }

    seg.segment(&text, &model, |buf, len, _aidx, is_cj| {
        let s: String = buf[..len].iter().collect();
        if is_cj {
            cj_runs.push(s);
        } else {
            words.push(s);
        }
    });

    assert_eq!(2, words.len());
    assert_eq!(3, cj_runs.len());
    assert_eq!(blob_latin, words[0]);
    assert_eq!(blob_cj, cj_runs[0]);
    assert_eq!(MAX_WORD_LEN, words[1].chars().count());
    assert_eq!(MAX_CJ_WORD_LEN, cj_runs[1].chars().count());
}

// ========================================================================
// Edge cases
// ========================================================================

#[test]
fn only_spaces() {
    assert_eq!(0, segment_words("   ").len());
}

#[test]
fn only_punctuation() {
    assert_eq!(0, segment_words("---...!!!").len());
}

#[test]
fn single_char_words() {
    let words = segment_words("a b c");
    assert_eq!(3, words.len());
    assert_eq!("a", words[0].text);
    assert_eq!("b", words[1].text);
    assert_eq!("c", words[2].text);
}

#[test]
fn digit_does_not_poison_non_latin() {
    let model = cj_model();
    let (words, cj_runs) = segment_with_model("123\u{4e00}\u{4e8c}", &model);
    // CJ word should not be poisoned by digits
    let all_cj: Vec<&Word> = cj_runs.iter().chain(
        words.iter().filter(|w| w.text.contains('\u{4e00}') || w.text.contains('\u{4e8c}'))
    ).collect();
    assert!(!all_cj.is_empty(), "CJ word should be emitted despite adjacent digits");
}
