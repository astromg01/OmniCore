#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/third_party/armsx2")
GS_STATE = ROOT / "pcsx2/GS/GSState.cpp"
VIS_HEADER = ROOT / "pcsx2/GS/OmniVisibilityTelemetry.h"
NATIVE = ROOT / "platforms/android/app/src/main/cpp/native-lib.cpp"

for path in (GS_STATE, VIS_HEADER, NATIVE):
    if not path.is_file():
        raise SystemExit(f"#29 missing source: {path}")

# Visibility v1 counted both the scalar fast test and the legacy/vector fallback
# in the same denominator. A primitive which passed the scalar stage could
# therefore contribute two "tests", making HUD CULL% and prim/s unsuitable for
# deciding an optimization. V2 counts each primitive exactly once and separately
# tracks where rejection happened. No rendering decision is changed here.
VIS_HEADER.write_text(r'''#pragma once

#include <atomic>
#include <cstdint>

namespace OmniVisibilityTelemetry
{
struct Snapshot
{
    std::uint64_t primitive_tests;
    std::uint64_t culled;
    std::uint64_t fast_culled;
    std::uint64_t legacy_tests;
    std::uint64_t legacy_culled;
    std::uint64_t draw_batches;
    std::uint64_t fog_draw_batches;
    std::uint64_t alpha_draw_batches;
    std::uint64_t indices;
    std::uint64_t fog_indices;
    std::uint64_t alpha_indices;
};

inline std::atomic<std::uint64_t> g_primitive_tests{0};
inline std::atomic<std::uint64_t> g_culled{0};
inline std::atomic<std::uint64_t> g_fast_culled{0};
inline std::atomic<std::uint64_t> g_legacy_tests{0};
inline std::atomic<std::uint64_t> g_legacy_culled{0};
inline std::atomic<std::uint64_t> g_draw_batches{0};
inline std::atomic<std::uint64_t> g_fog_draw_batches{0};
inline std::atomic<std::uint64_t> g_alpha_draw_batches{0};
inline std::atomic<std::uint64_t> g_indices{0};
inline std::atomic<std::uint64_t> g_fog_indices{0};
inline std::atomic<std::uint64_t> g_alpha_indices{0};

inline thread_local std::uint64_t tl_primitive_tests = 0;
inline thread_local std::uint64_t tl_culled = 0;
inline thread_local std::uint64_t tl_fast_culled = 0;
inline thread_local std::uint64_t tl_legacy_tests = 0;
inline thread_local std::uint64_t tl_legacy_culled = 0;

inline void PublishCullLocal()
{
    if (tl_primitive_tests == 0 && tl_legacy_tests == 0)
        return;
    g_primitive_tests.fetch_add(tl_primitive_tests, std::memory_order_relaxed);
    g_culled.fetch_add(tl_culled, std::memory_order_relaxed);
    g_fast_culled.fetch_add(tl_fast_culled, std::memory_order_relaxed);
    g_legacy_tests.fetch_add(tl_legacy_tests, std::memory_order_relaxed);
    g_legacy_culled.fetch_add(tl_legacy_culled, std::memory_order_relaxed);
    tl_primitive_tests = 0;
    tl_culled = 0;
    tl_fast_culled = 0;
    tl_legacy_tests = 0;
    tl_legacy_culled = 0;
}

inline void RecordFastCull(bool rejected)
{
    // This is the entry point for one primitive. Count it once regardless of
    // whether a fallback test is needed afterwards.
    ++tl_primitive_tests;
    if (rejected)
    {
        ++tl_culled;
        ++tl_fast_culled;
    }
    if ((tl_primitive_tests & 0x1ffu) == 0)
        PublishCullLocal();
}

inline void RecordLegacyCull(bool rejected)
{
    ++tl_legacy_tests;
    if (rejected)
    {
        ++tl_culled;
        ++tl_legacy_culled;
    }
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
        g_primitive_tests.load(std::memory_order_relaxed),
        g_culled.load(std::memory_order_relaxed),
        g_fast_culled.load(std::memory_order_relaxed),
        g_legacy_tests.load(std::memory_order_relaxed),
        g_legacy_culled.load(std::memory_order_relaxed),
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

state = GS_STATE.read_text(encoding="utf-8")
fast_old = "OmniVisibilityTelemetry::RecordCull(fast_skip != 0);"
legacy_old = "OmniVisibilityTelemetry::RecordCull(visibility_skip != 0);"
if fast_old not in state or legacy_old not in state:
    raise SystemExit("#29 expected Visibility v1 cull call sites were not found")
state = state.replace(fast_old, "OmniVisibilityTelemetry::RecordFastCull(fast_skip != 0);", 1)
state = state.replace(legacy_old, "OmniVisibilityTelemetry::RecordLegacyCull(visibility_skip != 0);", 1)
GS_STATE.write_text(state, encoding="utf-8")

native = NATIVE.read_text(encoding="utf-8")
pattern = re.compile(
    r'extern "C"\nJNIEXPORT jstring JNICALL\n'
    r'Java_kr_co_iefriends_pcsx2_NativeApp_getOmniVisibilitySnapshot\(JNIEnv\* env, jclass\)\n'
    r'\{.*?\n\}',
    re.S,
)
replacement = r'''extern "C"
JNIEXPORT jstring JNICALL
Java_kr_co_iefriends_pcsx2_NativeApp_getOmniVisibilitySnapshot(JNIEnv* env, jclass)
{
    const OmniVisibilityTelemetry::Snapshot s = OmniVisibilityTelemetry::Read();
    char buffer[640]{};
    snprintf(buffer, sizeof(buffer),
        "ok=1;source=omnicore-gs-visibility-v2;primitiveTests=%llu;culled=%llu;fastCulled=%llu;legacyTests=%llu;legacyCulled=%llu;drawBatches=%llu;fogDraws=%llu;alphaDraws=%llu;indices=%llu;fogIndices=%llu;alphaIndices=%llu",
        static_cast<unsigned long long>(s.primitive_tests),
        static_cast<unsigned long long>(s.culled),
        static_cast<unsigned long long>(s.fast_culled),
        static_cast<unsigned long long>(s.legacy_tests),
        static_cast<unsigned long long>(s.legacy_culled),
        static_cast<unsigned long long>(s.draw_batches),
        static_cast<unsigned long long>(s.fog_draw_batches),
        static_cast<unsigned long long>(s.alpha_draw_batches),
        static_cast<unsigned long long>(s.indices),
        static_cast<unsigned long long>(s.fog_indices),
        static_cast<unsigned long long>(s.alpha_indices));
    return env->NewStringUTF(buffer);
}'''
native, count = pattern.subn(replacement, native, count=1)
if count != 1:
    raise SystemExit(f"#29 visibility JNI replacement count={count}")
NATIVE.write_text(native, encoding="utf-8")

print("OMNICORE_PCSX2_ALPHA6_29_UPSTREAM_PATCH_OK visibility_v2=1 true_primitive_denominator=1 render_decisions_unchanged=1")
