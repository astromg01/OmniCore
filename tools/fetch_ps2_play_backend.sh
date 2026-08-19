#!/usr/bin/env bash
set -euo pipefail

PIN="04bde0df87ee7c0e2f0151b51bb2cc22c88541da"
DEST="${1:-build/third_party/play}"
REPO="https://github.com/jpd002/Play-.git"

rm -rf "$DEST"
mkdir -p "$DEST"
git -C "$DEST" init -q
git -C "$DEST" remote add origin "$REPO"
git -C "$DEST" fetch --quiet --depth 1 origin "$PIN"
git -C "$DEST" checkout --quiet --detach FETCH_HEAD
git -C "$DEST" submodule update --init --recursive --depth 1 --jobs 2

test "$(git -C "$DEST" rev-parse HEAD)" = "$PIN"
test -f "$DEST/License.txt"
test -f "$DEST/Source/ui_android/CMakeLists.txt"
test -f "$DEST/Source/CMakeLists.txt"
test -s "$DEST/GameConfig.xml"
grep -Fq '<GameConfigs>' "$DEST/GameConfig.xml"
grep -Fq '<GameConfig ' "$DEST/GameConfig.xml"
grep -Fq '<IdleLoopBlock' "$DEST/GameConfig.xml"
COUNT="$(grep -c '<GameConfig ' "$DEST/GameConfig.xml")"
test "$COUNT" -eq 47
git -C "$DEST" diff --quiet
printf 'Play! backend + compatibility database fetched at %s (%s profiles)\n' "$PIN" "$COUNT"
