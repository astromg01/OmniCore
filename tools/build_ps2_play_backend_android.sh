#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the Android NDK}"

PLAY_SRC="${1:-build/third_party/play}"
OUT_ROOT="${2:-build/ps2-play}"
PIN="04bde0df87ee7c0e2f0151b51bb2cc22c88541da"
PLAY_ABIS="${PLAY_ABIS:-arm64-v8a armeabi-v7a}"
PLAY_BUILD_JOBS="${PLAY_BUILD_JOBS:-2}"

if [[ "$(git -C "$PLAY_SRC" rev-parse HEAD)" != "$PIN" ]]; then
  echo "Unexpected Play! source revision" >&2
  exit 1
fi

for ABI in $PLAY_ABIS; do
  BUILD_DIR="$OUT_ROOT/$ABI"
  JNI_DIR="app/src/main/jniLibs/$ABI"
  rm -rf "$BUILD_DIR"
  mkdir -p "$BUILD_DIR" "$JNI_DIR"

  echo "=== Configuring Play! for $ABI ==="
  cmake -S "$PLAY_SRC" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DANDROID_ARM_NEON=TRUE \
    -DANDROID_CPP_FEATURES="exceptions rtti" \
    -DBUILD_PLAY=ON \
    -DBUILD_TESTS=OFF \
    -DBUILD_PSFPLAYER=OFF \
    -DBUILD_LIBRETRO_CORE=OFF \
    -DENABLE_AMAZON_S3=OFF \
    -DCMAKE_CXX_FLAGS_RELEASE="-O2 -DNDEBUG -frtti -fexceptions" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"

  echo "=== Building Play! for $ABI with -j$PLAY_BUILD_JOBS ==="
  cmake --build "$BUILD_DIR" --target Play -- -j"$PLAY_BUILD_JOBS" -v
  LIB="$(find "$BUILD_DIR" -type f -name 'libPlay.so' -print -quit)"
  if [[ -z "$LIB" || ! -f "$LIB" ]]; then
    echo "Play! Android shared library not produced for $ABI" >&2
    exit 1
  fi
  install -m 0644 "$LIB" "$JNI_DIR/libPlay.so"
  test -s "$JNI_DIR/libPlay.so"
done

git -C "$PLAY_SRC" diff --quiet
printf 'Play! Android backend built for: %s\n' "$PLAY_ABIS"
