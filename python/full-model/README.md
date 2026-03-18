# LangIdentify Full Model

Full model data for the [LangIdentify](https://pypi.org/project/langidentify/)
language detection library. This package contains only model data files — no
code.

## Installation

```bash
pip install "langidentify[full]"
```

This installs both `langidentify` and this package. Once installed,
`Model.load()` will automatically prefer the full model.

## Lite vs. full model

Both models are trained from the same Wikipedia data but cropped at different
probability floors:

| | Lite | Full |
|---|---|---|
| Log-probability floor | -12 | -15 |
| Disk size (all languages) | ~17 MB | ~89 MB |
| Best for | Most use cases | Maximum accuracy when memory is not a concern |

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The bundled models contain statistical parameters derived from Wikipedia text.
The models do not contain or reproduce Wikipedia text.
