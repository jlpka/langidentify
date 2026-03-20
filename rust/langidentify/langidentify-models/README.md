# langidentify model data

These crates contain embedded model data for the
[langidentify](https://crates.io/crates/langidentify) language detection library.

You do not need to depend on these crates directly. Enable the `lite` feature on `langidentify`
and the model data is pulled in automatically:

```toml
[dependencies]
langidentify = { version = "0.1", features = ["lite"] }
```

For the full model (available via git only), see the
[langidentify README](https://github.com/jlpka/langidentify/tree/main/rust/langidentify#adding-the-dependency).
