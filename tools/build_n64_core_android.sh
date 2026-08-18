#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/third_party/mupen64plus_next"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK" || ! -x "$NDK/ndk-build" ]]; then
  echo "ANDROID_NDK_HOME/ANDROID_NDK_ROOT must point to an Android NDK with ndk-build." >&2
  exit 1
fi
if [[ ! -f "$CORE/libretro/jni/Android.mk" ]]; then
  echo "Mupen64Plus-Next source is missing. Run tools/fetch_n64_core.sh first." >&2
  exit 1
fi

# llvm-readelf in recent NDKs can be a symlink, so do not restrict lookup to -type f.
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if [[ ! -x "$READELF" ]]; then
  READELF="$(find -L "$NDK/toolchains/llvm/prebuilt" -path '*/bin/llvm-readelf' -print -quit 2>/dev/null || true)"
fi
if [[ -z "$READELF" || ! -x "$READELF" ]]; then
  echo "llvm-readelf not found in Android NDK." >&2
  exit 1
fi

verify_16k_elf() {
  local so="$1"
  local line align
  while IFS= read -r line; do
    align="$(awk '{print $NF}' <<< "$line")"
    [[ "$align" =~ ^0x[0-9A-Fa-f]+$ ]] || {
      echo "Invalid LOAD alignment field in $so: $line" >&2
      return 1
    }
    (( align >= 0x4000 )) || {
      echo "N64 core is not 16 KB aligned: $so" >&2
      echo "  $line" >&2
      return 1
    }
  done < <("$READELF" -lW "$so" | grep -E '^[[:space:]]*LOAD[[:space:]]')
}

mkdir -p "$ROOT/app/src/main/jniLibs"

for ABI in arm64-v8a armeabi-v7a; do
  BUILD_ROOT="$ROOT/build/mupen64plus-next/$ABI"
  rm -rf "$BUILD_ROOT"
  mkdir -p "$BUILD_ROOT/obj" "$BUILD_ROOT/libs"

  (
    cd "$CORE"
    "$NDK/ndk-build" \
      NDK_PROJECT_PATH="$CORE/libretro" \
      APP_BUILD_SCRIPT="$CORE/libretro/jni/Android.mk" \
      NDK_APPLICATION_MK="$CORE/libretro/jni/Application.mk" \
      APP_ABI="$ABI" \
      APP_PLATFORM=android-26 \
      NDK_OUT="$BUILD_ROOT/obj" \
      NDK_LIBS_OUT="$BUILD_ROOT/libs" \
      GLES3=1 \
      HAVE_PARALLEL_RSP=0 \
      HAVE_PARALLEL_RDP=0 \
      HAVE_THR_AL=0 \
      LLE=0 \
      -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
  )

  SOURCE="$BUILD_ROOT/libs/$ABI/libmupen64plus_next_libretro.so"
  TARGET_DIR="$ROOT/app/src/main/jniLibs/$ABI"
  if [[ ! -f "$SOURCE" ]]; then
    echo "Expected N64 core output not found: $SOURCE" >&2
    exit 1
  fi

  verify_16k_elf "$SOURCE"

  mkdir -p "$TARGET_DIR"
  rm -f "$TARGET_DIR/libmupen64plus_next_libretro.so"
  cp "$SOURCE" "$TARGET_DIR/libmupen64plus_next_libretro.so"
  echo "Packaged 16 KB-aligned N64 core for $ABI"
done
