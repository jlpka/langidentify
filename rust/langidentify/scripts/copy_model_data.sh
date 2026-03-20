#!/usr/bin/env bash
#
# copy_model_data.sh - Copy model data files into the Rust crate data/ directories.
#
# Usage:
#   cd rust/langidentify
#   bash scripts/copy_model_data.sh
#
# This copies model files from the Java project's resource directories into:
#   langidentify-models/lite-a/data/   (European Latin-script languages, skipwords, CJ classifier)
#   langidentify-models/lite-b/data/   (all other languages)
#   langidentify-models/full/data/     (all languages, full probability model)
#
# Run this before `cargo publish` or whenever model data files change.
# The data/ directories are gitignored; crates.io packages include them via Cargo.toml `include`.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$WORKSPACE_DIR/../.." && pwd)"

LITE_SRC="$REPO_ROOT/models-lite/src/main/resources/com/jlpka/langidentify/models/lite"
FULL_SRC="$REPO_ROOT/models-full/src/main/resources/com/jlpka/langidentify/models/full"

LITE_A_DEST="$WORKSPACE_DIR/langidentify-models/lite-a/data"
LITE_B_DEST="$WORKSPACE_DIR/langidentify-models/lite-b/data"
FULL_DEST="$WORKSPACE_DIR/langidentify-models/full/data"

# European Latin-script languages (part A).
# Skipwords and CJ classifier data also go in part A.
PART_A_LANGS=(
    ca cs cy da de en eo es et eu fi fr ga hr hu
    is it la lb lt lv nl nn no pl pt ro sk sl sq sv
)

is_part_a() {
    local name="$1"
    # Skipwords and CJ classifier always go in part A.
    [[ "$name" == "skipwords.txt" ]] && return 0
    [[ "$name" == cj-* ]] && return 0
    # Extract language code from ngrams-XX.txt.gz or topwords-XX.txt.gz
    local lang=""
    if [[ "$name" == ngrams-* ]]; then
        lang="${name#ngrams-}"
        lang="${lang%.txt.gz}"
    elif [[ "$name" == topwords-* ]]; then
        lang="${name#topwords-}"
        lang="${lang%.txt.gz}"
    else
        return 1
    fi
    for a in "${PART_A_LANGS[@]}"; do
        [[ "$lang" == "$a" ]] && return 0
    done
    return 1
}

# Verify source directories exist.
if [[ ! -d "$LITE_SRC" ]]; then
    echo "ERROR: Lite model source not found at $LITE_SRC" >&2
    exit 1
fi
if [[ ! -d "$FULL_SRC" ]]; then
    echo "ERROR: Full model source not found at $FULL_SRC" >&2
    exit 1
fi

# Create destination directories.
mkdir -p "$LITE_A_DEST" "$LITE_B_DEST" "$FULL_DEST"

# Clean existing data.
rm -f "$LITE_A_DEST"/* "$LITE_B_DEST"/* "$FULL_DEST"/*

echo "=== Copying lite model data (split into part A and part B) ==="
a_count=0
b_count=0
for f in "$LITE_SRC"/*.txt.gz "$LITE_SRC"/skipwords.txt; do
    [[ -f "$f" ]] || continue
    name="$(basename "$f")"
    if is_part_a "$name"; then
        cp "$f" "$LITE_A_DEST/"
        ((a_count++))
    else
        cp "$f" "$LITE_B_DEST/"
        ((b_count++))
    fi
done
echo "  Part A (lite-a): $a_count files"
echo "  Part B (lite-b): $b_count files"

echo ""
echo "=== Copying full model data ==="
full_count=0
for f in "$FULL_SRC"/*.txt.gz "$FULL_SRC"/skipwords.txt; do
    [[ -f "$f" ]] || continue
    cp "$f" "$FULL_DEST/"
    ((full_count++))
done
echo "  Full: $full_count files"

echo ""
echo "=== Sizes ==="
echo "  lite-a/data/: $(du -sh "$LITE_A_DEST" | cut -f1)"
echo "  lite-b/data/: $(du -sh "$LITE_B_DEST" | cut -f1)"
echo "  full/data/:   $(du -sh "$FULL_DEST" | cut -f1)"
echo ""
echo "Done. Model data is ready for cargo build / cargo publish."
