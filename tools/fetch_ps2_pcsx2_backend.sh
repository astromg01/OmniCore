#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-build/third_party/armsx2}"
REPO="https://github.com/ARMSX2/ARMSX2.git"
PIN="7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3"

if [[ -d "$OUT/.git" ]]; then
  git -C "$OUT" remote set-url origin "$REPO"
  git -C "$OUT" fetch --depth=1 origin "$PIN"
  git -C "$OUT" checkout --detach --force "$PIN"
else
  rm -rf "$OUT"
  mkdir -p "$(dirname "$OUT")"
  git clone --filter=blob:none --no-checkout "$REPO" "$OUT"
  git -C "$OUT" fetch --depth=1 origin "$PIN"
  git -C "$OUT" checkout --detach "$PIN"
fi

ACTUAL="$(git -C "$OUT" rev-parse HEAD)"
if [[ "$ACTUAL" != "$PIN" ]]; then
  echo "Unexpected ARMSX2 revision: $ACTUAL" >&2
  exit 1
fi

test -s "$OUT/platforms/android/app/src/main/cpp/native-lib.cpp"
test -s "$OUT/platforms/android/app/src/main/java/kr/co/iefriends/pcsx2/NativeApp.java"
test -s "$OUT/COPYING.GPLv3"

echo "OMNICORE_PCSX2_FETCH_OK $ACTUAL"
