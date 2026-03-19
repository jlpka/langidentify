use langidentify::accent_remover::AccentRemover;

fn remover() -> AccentRemover {
    AccentRemover::new()
}

#[test]
fn ascii_passes_through() {
    assert_eq!("hello world", remover().remove("hello world").as_ref());
}

#[test]
fn empty_string() {
    assert_eq!("", remover().remove("").as_ref());
}

#[test]
fn basic_accents() {
    let r = remover();
    assert_eq!("cafe", r.remove("café").as_ref());
    assert_eq!("naive", r.remove("naïve").as_ref());
    assert_eq!("uber", r.remove("über").as_ref());
}

#[test]
fn ligatures() {
    let r = remover();
    assert_eq!("ae", r.remove("æ").as_ref());
    assert_eq!("oe", r.remove("œ").as_ref());
    assert_eq!("ss", r.remove("ß").as_ref());
}

#[test]
fn typographic_ligatures() {
    let r = remover();
    assert_eq!("ff", r.remove("\u{FB00}").as_ref());
    assert_eq!("fi", r.remove("\u{FB01}").as_ref());
    assert_eq!("fl", r.remove("\u{FB02}").as_ref());
    assert_eq!("ffi", r.remove("\u{FB03}").as_ref());
}

#[test]
fn mixed_content() {
    let r = remover();
    assert_eq!("Francais", r.remove("Français").as_ref());
    assert_eq!("Strasse", r.remove("Straße").as_ref());
    assert_eq!("resume", r.remove("résumé").as_ref());
}

#[test]
fn unmapped_non_ascii_passes_through() {
    let r = remover();
    let cjk = "漢字";
    assert_eq!(cjk, r.remove(cjk).as_ref());
}

#[test]
fn returns_borrowed_for_all_ascii() {
    let r = remover();
    let ascii = "plain ascii text";
    let result = r.remove(ascii);
    // Cow::Borrowed means no allocation occurred
    assert!(matches!(result, std::borrow::Cow::Borrowed(_)));
}
