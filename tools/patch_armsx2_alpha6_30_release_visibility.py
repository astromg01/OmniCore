#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/third_party/armsx2")
VIS_HEADER = ROOT / "pcsx2/GS/OmniVisibilityTelemetry.h"
NATIVE = ROOT / "platforms/android/app/src/main/cpp/native-lib.cpp"

for path in (VIS_HEADER, NATIVE):
    if not path.is_file():
        raise SystemExit(f"#30 missing source: {path}")

# Visibility v2 did its job: the physical-device samples established the real
# primitive rate/cull ratio and the alpha-heavy workload. Keeping counters in a
# ~0.7-1.0M primitive/s hot path after that point only makes the measurement
# machinery part of the workload. Preserve the ABI and function names, but make
# the hot hooks compile to no-ops so LTO removes their call cost completely.
header = VIS_HEADER.read_text(encoding="utf-8")
replacements = [
    (
        r"inline void RecordFastCull\(bool rejected\)\n\{.*?^\}\n",
        "inline void RecordFastCull(bool) {}\n",
        "RecordFastCull",
    ),
    (
        r"inline void RecordLegacyCull\(bool rejected\)\n\{.*?^\}\n",
        "inline void RecordLegacyCull(bool) {}\n",
        "RecordLegacyCull",
    ),
    (
        r"inline void RecordDrawBatch\(bool fog, bool alpha, std::uint64_t index_count\)\n\{.*?^\}\n",
        "inline void RecordDrawBatch(bool, bool, std::uint64_t) {}\n",
        "RecordDrawBatch",
    ),
]
for pattern, replacement, label in replacements:
    header, count = re.subn(pattern, replacement, header, count=1, flags=re.S | re.M)
    if count != 1:
        raise SystemExit(f"#30 {label} replacement count={count}")
VIS_HEADER.write_text(header, encoding="utf-8")

native = NATIVE.read_text(encoding="utf-8")
old = "ok=1;source=omnicore-gs-visibility-v2;primitiveTests=%llu"
new = "ok=0;source=omnicore-gs-visibility-retired;primitiveTests=%llu"
if old not in native:
    raise SystemExit("#30 Visibility v2 JNI source marker not found")
native = native.replace(old, new, 1)
NATIVE.write_text(native, encoding="utf-8")

print("OMNICORE_PCSX2_ALPHA6_30_VISIBILITY_RELEASE_OK hot_hooks_noop=1 jni_abi_preserved=1 telemetry_retired=1")
