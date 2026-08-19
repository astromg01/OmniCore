#!/usr/bin/env bash
set -Eeuo pipefail

SRC="${1:-build/third_party/armsx2}"
WORK="${2:-build/ps2-pcsx2}"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"
TAG="nightly-20260819"
ASSET="ARMSX2-nightly-20260819-7f0ae7a6c6-Android-arm64.apk"
URL="https://github.com/ARMSX2/ARMSX2/releases/download/${TAG}/${ASSET}"
DIAG="/tmp/ps2-alpha6-build-diagnostic.txt"
LOG="${RUNNER_TEMP:-/tmp}/alpha6-pcsx2-import.log"

on_error() {
  local rc=$?
  {
    echo 'failure_stage=pcsx2-armsx2-official-nightly-import'
    echo "exit_code=$rc"
    echo "source_pin=$PIN"
    echo "binary_tag=$TAG"
    echo "binary_asset=$ASSET"
    if [[ -s "$LOG" ]]; then
      echo '--- last 180 import lines ---'
      tail -n 180 "$LOG"
    fi
  } > "$DIAG"
  exit "$rc"
}
trap on_error ERR
exec > >(tee -a "$LOG") 2>&1

if [[ "$(git -C "$SRC" rev-parse HEAD)" != "$PIN" ]]; then
  echo "Unexpected ARMSX2 source revision" >&2
  exit 1
fi
test -s "$SRC/COPYING.GPLv3"

rm -rf "$WORK"
mkdir -p "$WORK"
APK="$WORK/$ASSET"
STAGE="$WORK/stage"
LIST="$WORK/upstream-apk-listing.txt"

# The exact pinned revision has an official ARMSX2 nightly Android asset built by
# upstream's own dual-core (4K + 16K) pipeline. Importing that already-validated
# native payload avoids depending on the snapshot's non-blocking Android-from-
# source reconciliation job while keeping source and binary on the same commit.
echo "Downloading official ARMSX2 nightly: $URL"
curl --fail --location --retry 4 --retry-all-errors --connect-timeout 30 \
  --output "$APK" "$URL"
test -s "$APK"
unzip -tq "$APK" >/dev/null
sha256sum "$APK" | tee "$WORK/upstream-apk.sha256"
unzip -l "$APK" > "$LIST"

grep -Fq 'lib/arm64-v8a/libemucore_4k.so' "$LIST"
grep -Fq 'lib/arm64-v8a/libemucore_16k.so' "$LIST"
grep -Fq 'assets/resources/' "$LIST"

rm -rf "$STAGE"
mkdir -p "$STAGE"
unzip -q "$APK" 'lib/arm64-v8a/*.so' 'assets/resources/*' -d "$STAGE"

for core in libemucore_4k.so libemucore_16k.so; do
  test -s "$STAGE/lib/arm64-v8a/$core"
  size="$(stat -c%s "$STAGE/lib/arm64-v8a/$core")"
  if (( size <= 10000000 )); then
    echo "$core is unexpectedly small ($size bytes)" >&2
    exit 1
  fi
done

JNI_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
rm -f app/src/main/jniLibs/arm64-v8a/libPlay.so app/src/main/jniLibs/armeabi-v7a/libPlay.so
cp -f "$STAGE/lib/arm64-v8a/libemucore_4k.so" "$JNI_DIR/"
cp -f "$STAGE/lib/arm64-v8a/libemucore_16k.so" "$JNI_DIR/"

# Copy only non-system DT_NEEDED dependencies that are actually packaged by the
# official APK. This avoids blindly vendoring unrelated AndroidX native payloads.
READELF="${ANDROID_NDK_HOME:-${OMNI_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$READELF"
for core in "$STAGE/lib/arm64-v8a/libemucore_4k.so" "$STAGE/lib/arm64-v8a/libemucore_16k.so"; do
  while IFS= read -r dep; do
    if [[ -s "$STAGE/lib/arm64-v8a/$dep" ]]; then
      cp -f "$STAGE/lib/arm64-v8a/$dep" "$JNI_DIR/"
    fi
  done < <("$READELF" -d "$core" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done

RESOURCE_SRC="$STAGE/assets/resources"
RESOURCE_DST="app/src/main/assets/pcsx2/resources"
test -d "$RESOURCE_SRC"
rm -rf app/src/main/assets/pcsx2
mkdir -p "$RESOURCE_DST"
cp -a "$RESOURCE_SRC/." "$RESOURCE_DST/"
test -n "$(find "$RESOURCE_DST" -type f -print -quit)"

LICENSE_DIR="app/src/main/assets/licenses"
mkdir -p "$LICENSE_DIR"
install -m 0644 "$SRC/COPYING.GPLv3" "$LICENSE_DIR/GPL-3.0-PCSX2-ARMSX2.txt"
test -s "$LICENSE_DIR/GPL-3.0-PCSX2-ARMSX2.txt"

rm -f "$DIAG"
printf 'OMNICORE_PCSX2_IMPORT_OK pin=%s tag=%s native_libs=%s resources=%s sha256=%s\n' \
  "$PIN" "$TAG" \
  "$(find "$JNI_DIR" -maxdepth 1 -type f -name '*.so' | wc -l)" \
  "$(find "$RESOURCE_DST" -type f | wc -l)" \
  "$(cut -d' ' -f1 "$WORK/upstream-apk.sha256")"
