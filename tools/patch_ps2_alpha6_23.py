#!/usr/bin/env python3
from pathlib import Path

BACKEND = Path("app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt")
ACTIVITY = Path("app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


backend = BACKEND.read_text()

# Alpha 6 #23: the #22 device test proved that the BALANCED profile is no
# longer GS-bound. EE/VU sit around 28-36 ms while the split GS branches sit
# around 15-19 ms. Keeping the extra GS worker only steals CPU from the new
# critical path. The same test also reported menu/cutscene fog flicker after
# true two-object pipelining was enabled. Therefore BALANCED becomes the
# visual-safe mixed profile: keep all safe GS optimizations, but do not force
# Vulkan solely for the pipeline and do not start the GS back worker.
backend = replace_once(
    backend,
    "            (earlyLearnedProfile == PERF_PROFILE_GS || earlyLearnedProfile == PERF_PROFILE_BALANCED) && hasVulkan\n",
    "            earlyLearnedProfile == PERF_PROFILE_GS && hasVulkan\n",
    "#23 balanced no forced Vulkan",
)
backend = replace_once(
    backend,
    "        val useGsPipeline = pipelineCapable &&\n"
    "            (learnedProfile == PERF_PROFILE_GS || learnedProfile == PERF_PROFILE_BALANCED)\n",
    "        val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_GS\n"
    "        val visualSafeBalanced = learnedProfile == PERF_PROFILE_BALANCED\n",
    "#23 balanced pipeline off",
)
backend = replace_once(
    backend,
    "            PERF_PROFILE_BALANCED -> 2\n",
    "            PERF_PROFILE_BALANCED -> 1\n",
    "#23 balanced queue depth",
)

# Keep BALANCED sticky for this learning generation. Without this guard the
# first no-pipeline run can naturally make GS look dominant again and promote
# the title back to GS on the next boot, re-enabling the exact visual path we
# are A/B testing. A later generation can split this into a per-title visual
# compatibility flag once the artifact is confirmed fixed.
backend = replace_once(
    backend,
    "                    val next = if (best.second >= 0.78f && best.second > second.second * 1.10f) {\n"
    "                        best.first\n"
    "                    } else PERF_PROFILE_UNKNOWN\n",
    "                    val next = if (learned == PERF_PROFILE_BALANCED) {\n"
    "                        PERF_PROFILE_BALANCED\n"
    "                    } else if (best.second >= 0.78f && best.second > second.second * 1.10f) {\n"
    "                        best.first\n"
    "                    } else PERF_PROFILE_UNKNOWN\n",
    "#23 sticky balanced visual-safe profile",
)

# Make the preboot diagnostic explicit so a device log can distinguish a true
# GS pipeline run from the visual-safe BALANCED fallback without guessing from
# FPS alone.
backend = backend.replace(
    '"visibility=${ps2VisibilityName(visibilityClass)} gsPipeline=$useGsPipeline " +',
    '"visibility=${ps2VisibilityName(visibilityClass)} gsPipeline=$useGsPipeline " +\n'
    '                "visualSafeBalanced=$visualSafeBalanced " +',
)
backend = backend.replace('"A6#22 preboot profile=', '"A6#23 preboot profile=')
backend = backend.replace('"A6#22 native=$nativeMetricSeen profile=', '"A6#23 native=$nativeMetricSeen profile=')

BACKEND.write_text(backend)

activity = ACTIVITY.read_text()
# The HUD now exposes whether a real GS back worker is observed. This is
# measurement-only and lets device testing confirm that BALANCED actually took
# the visual-safe single-GS path.
activity = replace_once(
    activity,
    '        append("PS2 PERF • ${t.bottleneck} • VIS ${t.visibilityPressure}\\n")\n',
    '        val pipe = if (t.gsBackUsagePercent >= 0f || t.gsBackMs >= 0f) "ON" else "OFF"\n'
    '        append("PS2 PERF • ${t.bottleneck} • PIPE $pipe • VIS ${t.visibilityPressure}\\n")\n',
    "#23 HUD pipeline state",
)
ACTIVITY.write_text(activity)

print("OMNICORE_PCSX2_ALPHA6_23_PATCH_OK balanced_pipeline_off=1 queue1=1 sticky_visual_safe=1 ee_vu_priority=1 hud_pipe=1")
