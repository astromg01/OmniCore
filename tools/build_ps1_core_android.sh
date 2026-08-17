#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/third_party/pcsx_rearmed"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK" || ! -x "$NDK/ndk-build" ]]; then
  echo "ANDROID_NDK_HOME/ANDROID_NDK_ROOT must point to an Android NDK with ndk-build." >&2
  exit 1
fi
if [[ ! -f "$CORE/jni/Android.mk" ]]; then
  echo "PCSX-ReARMed source is missing. Run tools/fetch_ps1_core.sh first." >&2
  exit 1
fi

rm -rf "$ROOT/app/src/main/jniLibs/arm64-v8a" "$ROOT/app/src/main/jniLibs/armeabi-v7a"
mkdir -p "$ROOT/app/src/main/jniLibs"

for ABI in arm64-v8a armeabi-v7a; do
  BUILD_ROOT="$ROOT/build/pcsx-rearmed/$ABI"
  rm -rf "$BUILD_ROOT"
  mkdir -p "$BUILD_ROOT/obj" "$BUILD_ROOT/libs"

  (
    cd "$CORE"
    "$NDK/ndk-build" \
      NDK_PROJECT_PATH="$CORE" \
      APP_BUILD_SCRIPT="$CORE/jni/Android.mk" \
      NDK_APPLICATION_MK="$CORE/jni/Application.mk" \
      APP_ABI="$ABI" \
      APP_PLATFORM=android-26 \
      NDK_OUT="$BUILD_ROOT/obj" \
      NDK_LIBS_OUT="$BUILD_ROOT/libs" \
      USE_LIBRETRO_VFS=0 \
      USE_ASYNC_CDROM=1 \
      USE_ASYNC_GPU=1 \
      USE_ASYNC_SPU=1 \
      NDRC_THREAD=1 \
      -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
  )

  SOURCE="$BUILD_ROOT/libs/$ABI/libpcsx_rearmed_libretro.so"
  TARGET_DIR="$ROOT/app/src/main/jniLibs/$ABI"
  if [[ ! -f "$SOURCE" ]]; then
    echo "Expected core output not found: $SOURCE" >&2
    exit 1
  fi
  mkdir -p "$TARGET_DIR"
  cp "$SOURCE" "$TARGET_DIR/libpcsx_rearmed_libretro.so"
  echo "Packaged PS1 core for $ABI"
done
