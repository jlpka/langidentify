use std::env;
use std::fs;
use std::path::Path;

/// Languages in part A (European Latin-script languages).
/// Everything NOT in this list goes in part B.
const PART_A_LANGS: &[&str] = &[
    "ca", "cs", "cy", "da", "de", "en", "eo", "es", "et", "eu", "fi", "fr", "ga", "hr", "hu",
    "is", "it", "la", "lb", "lt", "lv", "nl", "nn", "no", "pl", "pt", "ro", "sk", "sl", "sq",
    "sv",
];

fn is_part_b_file(name: &str) -> bool {
    // Skipwords and CJ data are in part A.
    if name == "skipwords.txt" || name.starts_with("cj-") {
        return false;
    }
    if let Some(lang) = name
        .strip_prefix("ngrams-")
        .or_else(|| name.strip_prefix("topwords-"))
    {
        let lang = lang.trim_end_matches(".txt.gz");
        return !PART_A_LANGS.contains(&lang);
    }
    false
}

fn main() {
    let manifest_dir = env::var("CARGO_MANIFEST_DIR").unwrap();

    // Look for data in the local data/ directory first (used by crates.io packages),
    // then fall back to the Java project's model directory (for development).
    let local_data = Path::new(&manifest_dir).join("data");
    let java_data = Path::new(&manifest_dir)
        .join("../../../../models-lite/src/main/resources/com/jlpka/langidentify/models/lite");

    let model_dir = if local_data.is_dir() {
        local_data.canonicalize().unwrap()
    } else if let Ok(p) = java_data.canonicalize() {
        p
    } else {
        eprintln!("cargo:warning=Model data not found in data/ or Java project directory");
        eprintln!("cargo:warning=Building with empty model data");
        let out_dir = env::var("OUT_DIR").unwrap();
        let dest_path = Path::new(&out_dir).join("models.rs");
        fs::write(
            &dest_path,
            "pub fn resolve(_name: &str) -> Option<&'static [u8]> { None }\n\
             pub fn exists(_name: &str) -> bool { false }\n",
        )
        .unwrap();
        return;
    };

    let out_dir = env::var("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("models.rs");

    let mut code = String::new();
    code.push_str("pub fn resolve(name: &str) -> Option<&'static [u8]> {\n");
    code.push_str("    match name {\n");

    let mut entries: Vec<String> = Vec::new();
    for entry in fs::read_dir(&model_dir).unwrap() {
        let entry = entry.unwrap();
        let file_name = entry.file_name().to_string_lossy().to_string();
        if (file_name.ends_with(".txt.gz") || file_name == "skipwords.txt")
            && is_part_b_file(&file_name)
        {
            let abs_path = entry.path().canonicalize().unwrap();
            entries.push(format!(
                "        \"{}\" => Some(include_bytes!(\"{}\")),",
                file_name,
                abs_path.display()
            ));
        }
    }
    entries.sort();
    for entry in &entries {
        code.push_str(entry);
        code.push('\n');
    }

    code.push_str("        _ => None,\n");
    code.push_str("    }\n");
    code.push_str("}\n\n");
    code.push_str("pub fn exists(name: &str) -> bool {\n");
    code.push_str("    resolve(name).is_some()\n");
    code.push_str("}\n");

    fs::write(&dest_path, code).unwrap();

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=data");
}
