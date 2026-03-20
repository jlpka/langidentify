// Usage: cargo run --release
// (from the rust/example/ directory)

use langidentify::{Detector, Language, Model};
use std::sync::Arc;

fn main() {
    let languages = Language::from_comma_separated("en,fr,de,es,it,pt,nl").unwrap();
    let model = Arc::new(Model::load_lite(&languages).unwrap());
    let mut detector = Detector::new(model);

    let phrases = [
        "The quick brown fox jumps over the lazy dog",
        "Bonjour le monde",
        "Was ist das?",
        "Hola, como estas?",
        "Buongiorno a tutti",
        "Onde fica a estacao?",
        "De kat zit op de mat",
    ];

    for phrase in &phrases {
        let lang = detector.detect(phrase);
        println!("{:>10}  {}", lang.iso_code(), phrase);
    }
}
