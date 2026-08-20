#!/usr/bin/env python3
from pathlib import Path

BACKEND = Path("app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


backend = BACKEND.read_text(encoding="utf-8")

# #30 is deliberately a consolidated performance pass rather than another
# telemetry-only iteration. The #29 physical-device result keeps the GS back
# thread OFF. For a title already proven BALANCED + EFFECTS, use PCSX2's real
# Asynchronous HWDownloadMode (enum 5): readbacks still happen, but the EE does
# not synchronously wait for the GPU download. Unknown/new titles stay on the
# normal readback mode until they have existing evidence.
backend = replace_once(
    backend,
    "        val useGsLockstep = false\n"
    "        val gsBackMode = 0\n",
    "        val useGsLockstep = false\n"
    "        val gsBackMode = 0\n"
    "        val useAsyncReadbacks = learnedProfile == PERF_PROFILE_BALANCED &&\n"
    "            visibilityClass == PERF_VIS_EFFECTS\n",
    "#30 evidence-scoped async readbacks",
)

backend = replace_once(
    backend,
    '        NativeApp.setSetting("EmuCore/GS", "CoalesceRenderPasses", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "SkipDuplicateFrames", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "GSBackThreadMode", "int", gsBackMode.toString())\n',
    '        NativeApp.setSetting("EmuCore/GS", "CoalesceRenderPasses", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "SkipDuplicateFrames", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "HWDownloadMode", "int", if (useAsyncReadbacks) "5" else "0")\n'
    '        NativeApp.setSetting("EmuCore/GS", "GSBackThreadMode", "int", gsBackMode.toString())\n',
    "#30 PCSX2 async HW download mode",
)

# The profiler was useful to discover the bottleneck and worker TIDs, but there
# is no reason to keep JNI/procfs sampling alive for an entire play session. Let
# it run long enough to prime scheduler assist and refresh the learned profile,
# then exit. 24 * 900 ms ~= 21.6 seconds.
backend = replace_once(
    backend,
    "            while (!governorStop && running) {\n",
    "            while (!governorStop && running && samples < 24) {\n",
    "#30 finite warm-up profiler",
)

backend = backend.replace('"A6#29 preboot profile=', '"A6#30 preboot profile=')
backend = backend.replace('"A6#29 native=$nativeMetricSeen profile=', '"A6#30 native=$nativeMetricSeen profile=')
backend = backend.replace(
    '"visualSafeBalanced=$visualSafeBalanced gsLockstep=$useGsLockstep " +',
    '"visualSafeBalanced=$visualSafeBalanced gsLockstep=$useGsLockstep asyncReadbacks=$useAsyncReadbacks " +',
)

BACKEND.write_text(backend, encoding="utf-8")
print("OMNICORE_PCSX2_ALPHA6_30_RUNTIME_PATCH_OK async_readbacks=effects_balanced profiler_warmup_only=1 gs_back_off=1")
