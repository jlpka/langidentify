use langidentify::{Alphabet, Detector, Language, Model};
use std::sync::Arc;

fn load_test_model() -> Arc<Model> {
    let languages = Language::from_comma_separated("en,fr,de,es,el,ru,cjk").unwrap();
    Arc::new(
        Model::load_embedded(
            langidentify_models_lite::resolve,
            &languages,
            -12.0,
            -12.0,
            0.0,
        )
        .unwrap(),
    )
}

#[test]
fn detect_basic_latin() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    assert_eq!(
        Language::English,
        detector.detect("The quick brown fox jumps over the lazy dog")
    );
    assert_eq!(
        Language::English,
        detector.detect(
            "Language detection is an interesting problem in natural language processing \
             that involves determining which language a given piece of text is written in"
        )
    );
    assert_eq!(
        Language::French,
        detector.detect("Le petit chat est assis sur le tapis dans la cuisine")
    );
    assert_eq!(
        Language::German,
        detector.detect("Der schnelle braune Fuchs springt ueber den faulen Hund")
    );
    assert_eq!(
        Language::Spanish,
        detector.detect("El gato esta sentado en la alfombra de la cocina")
    );
}

#[test]
fn detect_by_alphabet() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    assert_eq!(Language::Greek, detector.detect("Γεια σου κόσμε"));
    assert_eq!(
        Language::Greek,
        detector.detect("Η ελληνική γλώσσα είναι μία από τις αρχαιότερες γλώσσες")
    );
    assert_eq!(Language::Russian, detector.detect("привет"));
}

#[test]
fn detect_cjk() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    assert_eq!(
        Language::ChineseSimplified,
        detector.detect("今天天气很好，我们去公园散步")
    );
    assert_eq!(
        Language::ChineseTraditional,
        detector.detect("今天天氣很好，我們去公園散步")
    );
    assert_eq!(Language::Japanese, detector.detect("ひらがなとカタカナと"));
    assert_eq!(
        Language::Japanese,
        detector.detect("日本語は日本で使われている言語です。ひらがなとカタカナと漢字を使います")
    );
    assert_eq!(Language::Korean, detector.detect("안녕하세요"));
}

#[test]
fn detect_edge_cases() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    assert_eq!(Language::Unknown, detector.detect(""));
    assert_eq!(Language::Unknown, detector.detect("... --- !!!"));
    assert_eq!(0, detector.results().scores.num_chars());
    assert_eq!(0, detector.results().scores.num_words);
    assert_eq!(Language::Unknown, detector.detect("12345 67890"));
}

#[test]
fn detect_topwords_influence() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    assert_eq!(Language::English, detector.detect("Was it Jimmy?"));
    assert_eq!(Language::German, detector.detect("Was ist Jimmy?"));
}

#[test]
fn detect_with_boosts() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());

    let fr_boost = model.build_boost_single(Language::French, 0.08);
    let en_boost = model.build_boost_single(Language::English, 0.13);

    for word in &["message", "table", "menu", "restaurant"] {
        assert_eq!(
            Language::French,
            detector.detect_with_boosts(word, &fr_boost),
            "Expected French for '{}'",
            word
        );
        assert_eq!(
            Language::English,
            detector.detect_with_boosts(word, &en_boost),
            "Expected English for '{}'",
            word
        );
    }
}

#[test]
fn add_text_api() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    detector.clear_scores();
    detector.add_text("Le petit chat est assis sur le tapis dans la cuisine");
    assert_eq!(Language::French, detector.compute_result());
}

#[test]
fn apostrophe_topwords_split() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    detector.detect("l'homme d'histoire");
    assert_eq!(Language::French, detector.results().result);
    assert!(detector.results().scores.tw_hits_per_lang[detector.model().lang_index(Language::French).unwrap()] > 0);
    assert_eq!(4, detector.results().scores.tw_num_lookups);
}

#[test]
fn language_from_string() {
    assert_eq!(Language::English, Language::from_string("en"));
    assert_eq!(Language::English, Language::from_string("eng"));
    assert_eq!(Language::English, Language::from_string("english"));
    assert_eq!(Language::French, Language::from_string("fr"));
    assert_eq!(Language::Persian, Language::from_string("farsi"));
    assert_eq!(Language::ChineseSimplified, Language::from_string("zh"));
    assert_eq!(Language::ChineseSimplified, Language::from_string("chinese"));
    assert_eq!(Language::Japanese, Language::from_string("jp"));
    assert_eq!(Language::Unknown, Language::from_string("xxx"));
}

#[test]
fn from_comma_separated_group_aliases() {
    let cjk = Language::from_comma_separated("cjk").unwrap();
    assert_eq!(4, cjk.len());
    assert!(cjk.contains(&Language::ChineseSimplified));
    assert!(cjk.contains(&Language::Japanese));
    assert!(cjk.contains(&Language::Korean));
}

#[test]
fn skipwords() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    assert_eq!(
        Language::Unknown,
        detector.detect("http://www/aaa.html nbsp pdf php html htm Https jpg")
    );
    assert_eq!(0, detector.results().scores.num_chars());
}

// ========================================================================
// Mixed alphabet detection
// ========================================================================

#[test]
fn detect_by_mixed_alphabet() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    assert_eq!(Language::English, detector.detect("He likes to say привет"));
    assert_eq!(5, detector.results().scores.num_words);
    // English is more characters, but Chinese weighted more
    assert_eq!(
        Language::ChineseSimplified,
        detector.detect("我的名字是Jonathan")
    );
    // English has higher weight here.
    assert_eq!(
        Language::English,
        detector.detect("I like the characters 羊驼")
    );
}

// ========================================================================
// Stats populated
// ========================================================================

#[test]
fn stats_populated_after_detect() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());

    detector.detect("This is a simple English sentence for testing purposes");
    let results = detector.results();
    assert_eq!(Language::English, results.result);
    let en_idx = model.lang_index(Language::English).unwrap();
    assert!(
        results.scores.ngram_hits_per_lang[en_idx] > 0,
        "Expected ngram hits for English"
    );
}

// ========================================================================
// Language aliases
// ========================================================================

#[test]
fn language_lookup_aliases() {
    let cases: &[(Language, &[&str])] = &[
        (Language::Persian, &["persian", "farsi", "fa"]),
        (Language::ChineseSimplified, &["chinese", "zh"]),
        (Language::Japanese, &["ja", "jp", "japanese"]),
        (Language::Malay, &["malaysian", "malay", "ms"]),
        (Language::Irish, &["irish", "gaelic", "ga"]),
        (Language::Slovenian, &["slovene", "slovenian", "sl"]),
    ];
    for (expected, aliases) in cases {
        for alias in *aliases {
            assert_eq!(
                *expected,
                Language::from_string(alias),
                "Expected {:?} for \"{}\"",
                expected,
                alias
            );
            assert_eq!(
                *expected,
                Language::from_string(&alias.to_uppercase()),
                "Expected {:?} for \"{}\"",
                expected,
                alias.to_uppercase()
            );
        }
    }
}

#[test]
fn chinese_aliases() {
    for alias in &[
        "zh-hans",
        "zho-hans",
        "chinese",
        "zh",
        "zh-cn",
        "zh-hans-cn",
        "zh-hans-sg",
    ] {
        assert_eq!(
            Language::ChineseSimplified,
            Language::from_string(alias),
            "Expected ChineseSimplified for \"{}\"",
            alias
        );
    }
    for alias in &["zh-hant", "zho-hant", "zh-hant-hk", "zh-hk", "zh-hant-tw", "zh-tw"] {
        assert_eq!(
            Language::ChineseTraditional,
            Language::from_string(alias),
            "Expected ChineseTraditional for \"{}\"",
            alias
        );
    }
}

// ========================================================================
// CJ totalScores populated
// ========================================================================

#[test]
fn cj_total_scores_populated() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());

    detector.detect("今天天气很好，我们去公园散步");
    let r = detector.results();
    assert_eq!(Language::ChineseSimplified, r.result);
    assert!(r.gap > 0.0, "Expected positive gap");

    let zh_simp_idx = model.lang_index(Language::ChineseSimplified).unwrap();
    assert!(
        r.total_scores[zh_simp_idx] != 0.0,
        "Expected non-zero totalScore for zh-hans"
    );
    // The winning language should have the highest score
    if let Some(zh_trad_idx) = model.lang_index(Language::ChineseTraditional) {
        assert!(r.total_scores[zh_simp_idx] >= r.total_scores[zh_trad_idx]);
    }
    if let Some(ja_idx) = model.lang_index(Language::Japanese) {
        assert!(r.total_scores[zh_simp_idx] >= r.total_scores[ja_idx]);
    }
    // Non-CJ languages should be zero
    let en_idx = model.lang_index(Language::English).unwrap();
    assert_eq!(0.0, r.total_scores[en_idx]);
}

#[test]
fn kana_total_scores_populated() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());

    detector.detect("ひらがなとカタカナと");
    let r = detector.results();
    assert_eq!(Language::Japanese, r.result);
    assert!((r.gap - 1.0).abs() < 1e-9);

    let ja_idx = model.lang_index(Language::Japanese).unwrap();
    assert!((r.total_scores[ja_idx] - 1.0).abs() < 1e-9);
    for (i, &score) in r.total_scores.iter().enumerate() {
        if i != ja_idx {
            assert_eq!(0.0, score, "Expected 0 for non-Japanese lang idx {}", i);
        }
    }
}

#[test]
fn mixed_kana_han_total_scores_populated() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());

    detector.detect("日本語は日本で使われている言語です。ひらがなとカタカナと漢字を使います");
    let r = detector.results();
    assert_eq!(Language::Japanese, r.result);
    assert!((r.gap - 1.0).abs() < 1e-9);

    let ja_idx = model.lang_index(Language::Japanese).unwrap();
    assert!((r.total_scores[ja_idx] - 1.0).abs() < 1e-9);
    let en_idx = model.lang_index(Language::English).unwrap();
    assert_eq!(0.0, r.total_scores[en_idx]);
}

// ========================================================================
// All exit paths: result, gap, totalScores, predominantAlphaIdx
// ========================================================================

#[test]
fn results_fully_populated_for_latin_ngrams() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());

    detector.detect("The quick brown fox jumps over the lazy dog");
    let r = detector.results();
    assert_eq!(Language::English, r.result);
    assert!(r.gap > 0.0, "Expected positive gap");
    assert_eq!(
        model.alphabet_index(Alphabet::Latin).unwrap() as i32,
        r.predominant_alpha_idx
    );
    let en_idx = model.lang_index(Language::English).unwrap();
    assert!(
        r.total_scores[en_idx] != 0.0,
        "Expected non-zero totalScore for English"
    );
}

#[test]
fn results_fully_populated_for_unique_alphabet() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());

    detector.detect("Η ελληνική γλώσσα είναι μία από τις αρχαιότερες γλώσσες");
    let r = detector.results();
    assert_eq!(Language::Greek, r.result);
    assert!((r.gap - 1.0).abs() < 1e-9);
    assert_eq!(
        model.alphabet_index(Alphabet::Greek).unwrap() as i32,
        r.predominant_alpha_idx
    );
    let el_idx = model.lang_index(Language::Greek).unwrap();
    assert!((r.total_scores[el_idx] - 1.0).abs() < 1e-9);
    for (i, &score) in r.total_scores.iter().enumerate() {
        if i != el_idx {
            assert_eq!(0.0, score, "Expected 0 for non-Greek lang idx {}", i);
        }
    }
}

#[test]
fn results_fully_populated_for_unknown() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    detector.detect("... --- !!!");
    let r = detector.results();
    assert_eq!(Language::Unknown, r.result);
    assert!((r.gap - 1.0).abs() < 1e-9);
    assert_eq!(-1, r.predominant_alpha_idx);
    for (i, &score) in r.total_scores.iter().enumerate() {
        assert_eq!(0.0, score, "Expected 0 for lang idx {}", i);
    }
}

// ========================================================================
// addText API variants
// ========================================================================

#[test]
fn add_text_accumulates_across_calls() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    detector.clear_scores();
    detector.add_text("Bonjour");
    detector.add_text(" le");
    detector.add_text(" monde");
    let result = detector.compute_result();
    assert_eq!(Language::French, result);
    assert!(detector.results().scores.num_words > 1);
}

#[test]
fn add_text_clear_resets() {
    let model = load_test_model();
    let mut detector = Detector::new(model);

    // Detect French
    detector.clear_scores();
    detector.add_text("Le petit chat est assis sur le tapis dans la cuisine");
    detector.compute_result();
    assert_eq!(Language::French, detector.results().result);

    // Clear and detect German — should not be contaminated
    detector.clear_scores();
    detector.add_text("Der schnelle braune Fuchs springt ueber den faulen Hund");
    detector.compute_result();
    assert_eq!(Language::German, detector.results().result);
    assert!(detector.results().scores.num_words > 0);
}

#[test]
fn add_text_matches_detect() {
    let model = load_test_model();
    let text = "The quick brown fox jumps over the lazy dog";

    // detect() one-shot
    let mut det1 = Detector::new(model.clone());
    let detect_result = det1.detect(text);

    // addText workflow
    let mut det2 = Detector::new(model);
    det2.clear_scores();
    det2.add_text(text);
    det2.compute_result();

    assert_eq!(detect_result, det2.results().result);
    assert_eq!(
        det1.results().scores.num_words,
        det2.results().scores.num_words
    );
    for (a, b) in det1
        .results()
        .total_scores
        .iter()
        .zip(det2.results().total_scores.iter())
    {
        assert!((a - b).abs() < 1e-12);
    }
}

#[test]
fn add_text_with_boosts() {
    let model = load_test_model();
    let mut detector = Detector::new(model.clone());
    let fr_boost = model.build_boost_single(Language::French, 0.08);

    detector.clear_scores();
    detector.add_text("message");
    let result = detector.compute_result_with_boosts(&fr_boost);
    assert_eq!(Language::French, result);
}

// ========================================================================
// More group alias tests
// ========================================================================

#[test]
fn from_comma_separated_group_aliases_expanded() {
    let europe_cyr = Language::from_comma_separated("europe_cyrillic").unwrap();
    assert!(europe_cyr.contains(&Language::Russian));

    let europe_west = Language::from_comma_separated("europe_west_common").unwrap();
    assert!(europe_west.contains(&Language::English));
    assert!(europe_west.contains(&Language::French));

    // Groups can be mixed with individual languages
    let result = Language::from_comma_separated("nordic,ja").unwrap();
    assert!(result.contains(&Language::Japanese));
    assert!(result.contains(&Language::Norwegian));
    assert!(result.contains(&Language::Danish));
    assert!(result.contains(&Language::Swedish));
}
