#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/mupen64plus_next"
REPO="https://github.com/libretro/mupen64plus-libretro-nx.git"
PIN="f275caf4b2bfa1e6d1c51636746ea793f3d80320"

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
git clone --filter=blob:none --no-tags "$REPO" "$DEST"
git -C "$DEST" checkout --detach "$PIN"

# Unique module/SONAME: every console core must coexist inside the APK.
sed -i -E 's/^LOCAL_MODULE[[:space:]]*:= retro$/LOCAL_MODULE           := mupen64plus_next_libretro/' "$DEST/libretro/jni/Android.mk"

echo "Mupen64Plus-Next pinned at $PIN"
