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

# Android 15+ can run with 16 KB memory pages. Patch the final LOCAL_LDFLAGS
# assignment itself: adding flags earlier is unsafe because upstream later uses
# ':=' and would overwrite them (notably leaving armeabi-v7a at 4 KB).
python3 - "$ANDROID_MK" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
module = "LOCAL_MODULE           := mupen64plus_next_libretro"
old_ldflags = "LOCAL_LDFLAGS          := -Wl,-version-script=$(LIBRETRO_DIR)/link.T"
new_ldflags = (
    "LOCAL_LDFLAGS          := -Wl,-version-script=$(LIBRETRO_DIR)/link.T "
    "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)
if module not in text:
    raise SystemExit("Unable to locate renamed Mupen module in Android.mk")
if old_ldflags not in text and new_ldflags not in text:
    raise SystemExit("Unable to locate Mupen LOCAL_LDFLAGS assignment in Android.mk")
text = text.replace(old_ldflags, new_ldflags, 1)
path.write_text(text)
PY

grep -Fq 'max-page-size=16384' "$ANDROID_MK"
grep -Fq 'common-page-size=16384' "$ANDROID_MK"

echo "Mupen64Plus-Next pinned at $PIN with 16 KB ELF alignment"
