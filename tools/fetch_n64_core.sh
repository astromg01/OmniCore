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

ANDROID_MK="$DEST/libretro/jni/Android.mk"

# Unique module/SONAME: every console core must coexist inside the APK.
sed -i -E 's/^LOCAL_MODULE[[:space:]]*:= retro$/LOCAL_MODULE           := mupen64plus_next_libretro/' "$ANDROID_MK"

# Android 15+ can run with 16 KB memory pages. The pinned upstream ndk-build
# project predates that requirement, so make the Mupen shared object explicitly
# flexible-page-size compatible instead of weakening OmniCore's release gate.
python3 - "$ANDROID_MK" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
needle = "LOCAL_MODULE           := mupen64plus_next_libretro\n"
flag = "LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384\n"
if needle not in text:
    raise SystemExit("Unable to locate renamed Mupen module in Android.mk")
if flag not in text:
    text = text.replace(needle, needle + flag, 1)
path.write_text(text)
PY

echo "Mupen64Plus-Next pinned at $PIN with 16 KB ELF alignment"
