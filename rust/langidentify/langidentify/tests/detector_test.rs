use langidentify::{Detector, Language, Model};
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
