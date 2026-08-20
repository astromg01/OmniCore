#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/third_party/armsx2")
GS_STATE = ROOT / "pcsx2/GS/GSState.cpp"
VIS_HEADER = ROOT / "pcsx2/GS/OmniVisibilityTelemetry.h"
NATIVE = ROOT / "platforms/android/app/src/main/cpp/native-lib.cpp"

for path in (GS_STATE, NATIVE):
    if not path.is_file():
        raise SystemExit(f"#24 missing upstream source: {path}")

# Visibility v1 is deliberately measurement-first. Per-primitive accounting is
# accumulated thread-locally and published in batches so the profiler does not
# turn into a new GS bottleneck on low-end ARM cores.
VIS_HEADER.write_text(r'''#pragma once

#include <atomic>
#include <cstdint>

namespace OmniVisibilityTelemetry
{
struct Snapshot
{
    std::uint64_t cull_tests;
    std::uint64_t culled;
    std::uint64_t draw_batches;
    std::uint64_t fog_draw_batches;
    std::uint64_t alpha_draw_batches;
    std::uint64_t indices;
    std::uint64_t fog_indices;
    std::uint64_t alpha_indices;
};

inline std::atomic<std::uint64_t> g_cull_tests{0};
inline std::atomic<std::uint64_t> g_culled{0};
inline std::atomic<std::uint64_t> g_draw_batches{0};
inline std::atomic<std::uint64_t> g_fog_draw_batches{0};
inline std::atomic<std::uint64_t> g_alpha_draw_batches{0};
inline std::atomic<std::uint64_t> g_indices{0};
inline std::atomic<std::uint64_t> g_fog_indices{0};
inline std::atomic<std::uint64_t> g_alpha_indices{0};

inline thread_local std::uint64_t tl_cull_tests = 0;
inline thread_local std::uint64_t tl_culled = 0;

inline void PublishCullLocal()
{
    if (tl_cull_tests == 0)
        return;
    g_cull_tests.fetch_add(tl_cull_tests, std::memory_order_relaxed);
    g_culled.fetch_add(tl_culled, std::memory_order_relaxed);
    tl_cull_tests = 0;
    tl_culled = 0;
}

inline void RecordCull(bool rejected)
{
    ++tl_cull_tests;
    tl_culled += rejected ? 1u : 0u;
    // Keep atomics off the primitive hot path. Worst-case telemetry lag is 511
    // tests, well below the 900 ms OmniCore sampling window in real games.
    if ((tl_cull_tests & 0x1ffu) == 0)
        PublishCullLocal();
}

inline void RecordDrawBatch(bool fog, bool alpha, std::uint64_t index_count)
{
    PublishCullLocal();
    g_draw_batches.fetch_add(1, std::memory_order_relaxed);
    g_indices.fetch_add(index_count, std::memory_order_relaxed);
    if (fog)
    {
        g_fog_draw_batches.fetch_add(1, std::memory_order_relaxed);
        g_fog_indices.fetch_add(index_count, std::memory_order_relaxed);
    }
    if (alpha)
    {
        g_alpha_draw_batches.fetch_add(1, std::memory_order_relaxed);
        g_alpha_indices.fetch_add(index_count, std::memory_order_relaxed);
    }
}

inline Snapshot Read()
{
    return {
        g_cull_tests.load(std::memory_order_relaxed),
        g_culled.load(std::memory_order_relaxed),
        g_draw_batches.load(std::memory_order_relaxed),
        g_fog_draw_batches.load(std::memory_order_relaxed),
        g_alpha_draw_batches.load(std::memory_order_relaxed),
        g_indices.load(std::memory_order_relaxed),
        g_fog_indices.load(std::memory_order_relaxed),
        g_alpha_indices.load(std::memory_order_relaxed),
    };
}
} // namespace OmniVisibilityTelemetry
''')

state = GS_STATE.read_text()
if '#include "GS/OmniVisibilityTelemetry.h"' not in state:
    anchor = '#include "GS/GSVertexKick.h"\n'
    if state.count(anchor) != 1:
        raise SystemExit(f"#24 GS include anchor count={state.count(anchor)}")
    state = state.replace(anchor, anchor + '#include "GS/OmniVisibilityTelemetry.h"\n', 1)

# The pinned ARM64 GS has two equivalent cull paths: a scalar-outcode fast path
# and the legacy/vector bbox path. Record the decision after each pure test so
# we measure real primitive visibility without changing the decision itself.
fast_old = 'const u32 fast_skip = GSVertexKernels::CullTestScalar<n, primclass>(e0, e1, e2);\n\n\t\t\tskip |= fast_skip;'
fast_new = 'const u32 fast_skip = GSVertexKernels::CullTestScalar<n, primclass>(e0, e1, e2);\n\n\t\t\tOmniVisibilityTelemetry::RecordCull(fast_skip != 0);\n\t\t\tskip |= fast_skip;'
if fast_new not in state:
    if state.count(fast_old) != 1:
        raise SystemExit(f"#24 scalar cull anchor count={state.count(fast_old)}")
    state = state.replace(fast_old, fast_new, 1)

legacy_old = 'skip |= GSVertexKernels::CullTest<n, primclass>(v0, v1, v2, m_context->scissor.cull, m_nativeres, aa1_expand, bbox);'
legacy_new = ('const u32 visibility_skip = GSVertexKernels::CullTest<n, primclass>(v0, v1, v2, '
              'm_context->scissor.cull, m_nativeres, aa1_expand, bbox);\n'
              '\t\t\tOmniVisibilityTelemetry::RecordCull(visibility_skip != 0);\n'
              '\t\t\tskip |= visibility_skip;')
if legacy_new not in state:
    if state.count(legacy_old) != 1:
        raise SystemExit(f"#24 legacy cull anchor count={state.count(legacy_old)}")
    state = state.replace(legacy_old, legacy_new, 1)

flush_old = 'void GSState::FlushPrim()\n{\n\tif (m_index->tail == 0)\n\t\treturn;\n'
flush_new = (flush_old +
             '\n\t// OmniCore Visibility v1: draw-batch effect pressure. FGE/ABE are\n'
             '\t// observation only; fog/alpha rendering is never disabled here.\n'
             '\tOmniVisibilityTelemetry::RecordDrawBatch(PRIM->FGE != 0, PRIM->ABE != 0,\n'
             '\t\tstatic_cast<std::uint64_t>(m_index->tail));\n')
if 'OmniVisibilityTelemetry::RecordDrawBatch' not in state:
    if state.count(flush_old) != 1:
        raise SystemExit(f"#24 FlushPrim anchor count={state.count(flush_old)}")
    state = state.replace(flush_old, flush_new, 1)

GS_STATE.write_text(state)

native = NATIVE.read_text()
if '#include "GS/OmniVisibilityTelemetry.h"' not in native:
    anchor = '#include "GS/GSPerfMon.h"\n'
    if native.count(anchor) != 1:
        raise SystemExit(f"#24 native include anchor count={native.count(anchor)}")
    native = native.replace(anchor, anchor + '#include "GS/OmniVisibilityTelemetry.h"\n', 1)

jni_marker = 'Java_kr_co_iefriends_pcsx2_NativeApp_getOmniVisibilitySnapshot'
if jni_marker not in native:
    anchor = 'static JNIEnv env_main;\n'
    if native.count(anchor) != 1:
        raise SystemExit(f"#24 native JNI insertion anchor count={native.count(anchor)}")
    export = r'''

// OmniCore Alpha 6 #24 Visibility v1. This is a read-only cumulative snapshot
// of GS primitive culling and fog/alpha draw pressure. It deliberately exposes
// no control that can delete entities, disable fog, change emulation timing, or
// mutate renderer state.
extern "C"
JNIEXPORT jstring JNICALL
Java_kr_co_iefriends_pcsx2_NativeApp_getOmniVisibilitySnapshot(JNIEnv* env, jclass)
{
    const OmniVisibilityTelemetry::Snapshot s = OmniVisibilityTelemetry::Read();
    char buffer[512]{};
    snprintf(buffer, sizeof(buffer),
        "ok=1;source=omnicore-gs-visibility-v1;cullTests=%llu;culled=%llu;drawBatches=%llu;fogDraws=%llu;alphaDraws=%llu;indices=%llu;fogIndices=%llu;alphaIndices=%llu",
        static_cast<unsigned long long>(s.cull_tests),
        static_cast<unsigned long long>(s.culled),
        static_cast<unsigned long long>(s.draw_batches),
        static_cast<unsigned long long>(s.fog_draw_batches),
        static_cast<unsigned long long>(s.alpha_draw_batches),
        static_cast<unsigned long long>(s.indices),
        static_cast<unsigned long long>(s.fog_indices),
        static_cast<unsigned long long>(s.alpha_indices));
    return env->NewStringUTF(buffer);
}
'''
    native = native.replace(anchor, anchor + export, 1)

NATIVE.write_text(native)

print('OMNICORE_PCSX2_ALPHA6_24_UPSTREAM_PATCH_OK visibility_v1=1 cull_paths=2 fog_alpha_pressure=1 jni_snapshot=1')
