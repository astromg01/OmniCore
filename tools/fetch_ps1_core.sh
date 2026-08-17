#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/pcsx_rearmed"
REPO="https://github.com/libretro/pcsx_rearmed.git"
PIN="da2cb8ecd17fd0932ab6d94774c0522beebce6e3"

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
git clone --filter=blob:none --no-tags "$REPO" "$DEST"
git -C "$DEST" checkout --detach "$PIN"

# Give the Android shared object a unique module/SONAME so future libretro
# cores can coexist in the same APK without all being named libretro.so.
sed -i -E 's/^LOCAL_MODULE[[:space:]]*:= retro$/LOCAL_MODULE        := pcsx_rearmed_libretro/' "$DEST/jni/Android.mk"

echo "PCSX-ReARMed pinned at $PIN"
