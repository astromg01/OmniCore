#!/usr/bin/env bash
set -Eeuo pipefail

# Alpha 6 #17 wrapper: keep the proven official ARMSX2 import and then refine
# the generated runtime policy. The base script is pinned as a separate blob so
# this layer stays small, auditable and easy to remove once the transform is
# folded into the permanent backend.
BASE="$(cd "$(dirname "$0")" && pwd)/build_ps2_pcsx2_backend_android_base.sh"
chmod +x "$BASE"
"$BASE" "$@"

BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
test -s "$BACKEND"

python3 - "$BACKEND" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")

# #16 speculated that every unknown 8-core phone could afford a separate GS
# back thread. On big.LITTLE parts this can steal the exact fast cores EE/VU need.
# Start conservative; only a game which has already been measured as render/GS
# bound gets Pipelined GS on its next boot.
old_pipeline = 'val useGsPipeline = pipelineCapable && learnedProfile != PERF_PROFILE_COMPUTE'
new_pipeline = 'val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_RENDER'
if old_pipeline not in text:
    raise SystemExit('Alpha 6 #17 GS pipeline policy anchor not found')
text = text.replace(old_pipeline, new_pipeline, 1)

# v1 divided process CPU time by every logical core. PS2 emulation is dominated
# by a handful of hot EE/VU/GS threads, so two saturated threads on an 8-core SoC
# looked like only ~25% load and were incorrectly labeled render-bound. Measure
# CPU in equivalent fully-busy cores first, keeping normalized total load only as
# a secondary signal/log value.
old_metric = '''                val processCoreLoad = (cpuMs / wallMs / cores.toFloat()).coerceIn(0f, 1.25f)'''
new_metric = '''                val cpuEquivalentCores = (cpuMs / wallMs).coerceIn(0f, cores.toFloat() + 0.5f)
                val processCoreLoad = (cpuEquivalentCores / cores.toFloat()).coerceIn(0f, 1.25f)'''
if old_metric not in text:
    raise SystemExit('Alpha 6 #17 CPU pressure metric anchor not found')
text = text.replace(old_metric, new_metric, 1)

old_comment = '''                // Decay makes this a recent-workload classifier instead of a
                // permanent verdict. Low FPS with broad process CPU saturation
                // is treated as compute/entity pressure; low FPS without it is
                // treated as GS/render/depth pressure. This never changes VM/JIT
                // settings live -- the result is only a next-boot hint.'''
new_comment = '''                // Culling-aware pressure classifier: PCSX2 cannot safely delete
                // game entities (AI/physics must still run), so classify the cost
                // they create instead. Low FPS while >= ~1.3 CPU cores are busy
                // points at EE/VU/entity/physics pressure; low FPS without that
                // compute pressure points at GS/visibility/depth pressure. The
                // result is next-boot policy only: no live JIT/cycle mutation.'''
if old_comment not in text:
    raise SystemExit('Alpha 6 #17 pressure classifier comment anchor not found')
text = text.replace(old_comment, new_comment, 1)

old_classify = '''                    if (processCoreLoad >= 0.34f) {
                        computePressure += severity * (1.0f + processCoreLoad)
                    } else {
                        renderPressure += severity * (1.20f - processCoreLoad).coerceAtLeast(0.35f)
                    }'''
new_classify = '''                    if (cpuEquivalentCores >= 1.30f) {
                        val hotThreadWeight = (cpuEquivalentCores / 2.0f).coerceIn(0.65f, 1.75f)
                        computePressure += severity * (1.0f + hotThreadWeight)
                    } else {
                        renderPressure += severity * (1.20f - processCoreLoad).coerceAtLeast(0.35f)
                    }'''
if old_classify not in text:
    raise SystemExit('Alpha 6 #17 pressure branch anchor not found')
text = text.replace(old_classify, new_classify, 1)

old_log = '''                            "cpu=${String.format("%.2f", processCoreLoad)} render=${String.format("%.2f", renderPressure)} " +
                            "compute=${String.format("%.2f", computePressure)} thermal=$thermal adpf=$adpfEnabled"'''
new_log = '''                            "cpuCores=${String.format("%.2f", cpuEquivalentCores)} cpuTotal=${String.format("%.2f", processCoreLoad)} " +
                            "render=${String.format("%.2f", renderPressure)} compute=${String.format("%.2f", computePressure)} " +
                            "thermal=$thermal adpf=$adpfEnabled"'''
if old_log not in text:
    raise SystemExit('Alpha 6 #17 profiler log anchor not found')
text = text.replace(old_log, new_log, 1)

p.write_text(text, encoding="utf-8")
PY

# Regression guards: #17 must never revive the #13 live-cycle governor, and an
# UNKNOWN game must not get speculative Pipelined GS on big.LITTLE hardware.
grep -Fq 'learnedProfile == PERF_PROFILE_RENDER' "$BACKEND"
grep -Fq 'cpuEquivalentCores >= 1.30f' "$BACKEND"
grep -Fq 'CoalesceRenderPasses' "$BACKEND"
grep -Fq 'SkipDuplicateFrames' "$BACKEND"
grep -Fq 'runCatching { NativeApp.renderPreloading(2) }' "$BACKEND"
grep -Fq 'EnableVUProgramCache' "$BACKEND"
if grep -Fq 'NativeApp.speedhackEecyclerate(' "$BACKEND"; then
  echo 'Live EE cycle-rate mutation reintroduced into PS2 backend' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecycleskip(' "$BACKEND"; then
  echo 'Live EE cycle-skip mutation reintroduced into PS2 backend' >&2
  exit 1
fi

echo 'OMNICORE_PCSX2_ALPHA6_17_POLICY_OK conservative_gs=1 cpu_equivalent_cores=1 culling_aware_pressure=1'
