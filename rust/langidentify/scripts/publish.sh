#!/usr/bin/env bash
#
# publish.sh - Publish langidentify crates to crates.io.
#
# Usage:
#   cd rust/langidentify
#   bash scripts/publish.sh           # dry run (default)
#   bash scripts/publish.sh --execute # actually publish
#
# Prerequisites:
#   1. Run `bash scripts/copy_model_data.sh` first to populate data/ directories.
#   2. Ensure you are logged in: `cargo login`
#
# Publish order (respects dependency chain):
#   1. langidentify-models-lite-a
#   2. langidentify-models-lite-b
#   3. langidentify-models-lite
#   4. langidentify
#
# The full model crate is NOT published (too large for crates.io).
# The FFI crate is NOT published (marked publish = false).
#
# Before publishing `langidentify`, this script temporarily removes the
# `full` feature and `langidentify-models-full` dependency (which has no
# version and would fail the crates.io upload). They are restored after
# publishing.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CARGO_TOML="$WORKSPACE_DIR/langidentify/Cargo.toml"

DRY_RUN=true
if [[ "${1:-}" == "--execute" ]]; then
    DRY_RUN=false
fi

PUBLISH_FLAGS=()
if $DRY_RUN; then
    PUBLISH_FLAGS+=("--dry-run")
    echo "=== DRY RUN (pass --execute to actually publish) ==="
else
    echo "=== PUBLISHING TO CRATES.IO ==="
fi
echo ""

cd "$WORKSPACE_DIR"

# Step 1-2: Publish model data crates.
for crate in langidentify-models-lite-a langidentify-models-lite-b; do
    echo "--- Publishing $crate ---"
    cargo publish -p "$crate" ${PUBLISH_FLAGS[@]+"${PUBLISH_FLAGS[@]}"}
    if ! $DRY_RUN; then
        echo "Waiting for crates.io to index $crate..."
        sleep 15
    fi
    echo ""
done

# Step 3: Publish lite facade.
echo "--- Publishing langidentify-models-lite ---"
cargo publish -p langidentify-models-lite ${PUBLISH_FLAGS[@]+"${PUBLISH_FLAGS[@]}"}
if ! $DRY_RUN; then
    echo "Waiting for crates.io to index langidentify-models-lite..."
    sleep 15
fi
echo ""

# Step 4: Temporarily remove the full-model dependency (not on crates.io).
# Also remove the full feature from the FFI crate, since the workspace resolver
# would otherwise fail when langidentify's "full" feature is stripped.
echo "--- Preparing langidentify for publish (removing full-model dep) ---"
FFI_TOML="$WORKSPACE_DIR/langidentify-ffi/Cargo.toml"
cp "$CARGO_TOML" "$CARGO_TOML.bak"
cp "$FFI_TOML" "$FFI_TOML.bak"

# Remove the full feature line, its preceding comment block, and the full dependency line.
sed -i.sed \
    -e '/^# The "full" feature is only available/,/^full = \["dep:langidentify-models-full"\]/d' \
    -e '/^langidentify-models-full/d' \
    "$CARGO_TOML"
rm -f "$CARGO_TOML.sed"

# Remove the full feature from the FFI crate.
sed -i.sed \
    -e '/^full = \["langidentify\/full"\]/d' \
    "$FFI_TOML"
rm -f "$FFI_TOML.sed"

echo "--- Publishing langidentify ---"
publish_failed=false
cargo publish -p langidentify ${PUBLISH_FLAGS[@]+"${PUBLISH_FLAGS[@]}"} || publish_failed=true

# Restore original Cargo.toml files.
mv "$CARGO_TOML.bak" "$CARGO_TOML"
mv "$FFI_TOML.bak" "$FFI_TOML"
echo "(Restored original Cargo.toml files)"

if $publish_failed; then
    echo "ERROR: cargo publish failed for langidentify" >&2
    exit 1
fi

echo ""
echo "=== Done ==="
if $DRY_RUN; then
    echo "This was a dry run. Use --execute to publish for real."
fi
