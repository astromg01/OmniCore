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
LIBRETRO_C="$DEST/libretro/libretro.c"

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

# Upstream copies the complete libretro content buffer before opening the ROM.
# Guard allocation/content failure explicitly so constrained Android devices
# return a normal retro_load_game failure instead of memcpy() through nullptr.
python3 - "$LIBRETRO_C" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
old = '''    game_data = malloc(game->size);
    memcpy(game_data, game->data, game->size);
    game_size = game->size;
'''
new = '''    if (!game->data || game->size == 0 || game->size > UINT32_MAX)
    {
        if (log_cb)
            log_cb(RETRO_LOG_ERROR, CORE_NAME ": invalid or oversized ROM buffer\\n");
        return false;
    }

    game_data = malloc(game->size);
    if (!game_data)
    {
        if (log_cb)
            log_cb(RETRO_LOG_ERROR, CORE_NAME ": failed to allocate ROM buffer\\n");
        return false;
    }
    memcpy(game_data, game->data, game->size);
    game_size = (uint32_t)game->size;
'''
if old not in text:
    raise SystemExit("Unable to locate Mupen ROM copy block")
path.write_text(text.replace(old, new, 1))
PY

grep -Fq 'max-page-size=16384' "$ANDROID_MK"
grep -Fq 'common-page-size=16384' "$ANDROID_MK"
grep -Fq 'failed to allocate ROM buffer' "$LIBRETRO_C"

echo "Mupen64Plus-Next pinned at $PIN with 16 KB ELF alignment and Android ROM-buffer guard"
