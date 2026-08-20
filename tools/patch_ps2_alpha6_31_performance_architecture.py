#!/usr/bin/env python3
from pathlib import Path
import re

BACKEND = Path("app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt")
SMART = Path("app/src/main/java/com/omnicore/emulator/performance/PS2SmartPerf.kt")
ACTIVITY = Path("app/src/main/java/com/omnicore/emulator/emulation/PS2EmulationActivity.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"#31 {label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# SmartPerf becomes a boot-time tier selector. It no longer owns a live tuning
# loop; SAFE/OPTIMAL/FAST are passed into the static architecture once.
# ---------------------------------------------------------------------------
smart = SMART.read_text(encoding="utf-8")
smart = replace_once(
    smart,
    "            allowAsyncTextureUpload = asyncTextureUpload,\n"
    "            allowCycleSkipping = false\n",
    "            allowAsyncTextureUpload = asyncTextureUpload,\n"
    "            allowCycleSkipping = false,\n"
    "            performanceTier = when (mode) {\n"
    "                Mode.ECO -> PS2Backend.PerformanceTier.SAFE\n"
    "                Mode.BALANCED -> PS2Backend.PerformanceTier.OPTIMAL\n"
    "                Mode.PERFORMANCE -> PS2Backend.PerformanceTier.FAST\n"
    "            }\n",
    "SmartPerf tier bridge",
)
SMART.write_text(smart, encoding="utf-8")


# ---------------------------------------------------------------------------
# Runtime: resolve device + GameDB-style policy before the VM starts. No live
# renderer/timing/readback mutation is allowed afterwards.
# ---------------------------------------------------------------------------
backend = BACKEND.read_text(encoding="utf-8")
backend = replace_once(
    backend,
    "            applyPreBootConfig(request.config, request.imagePath)\n",
    "            applyPreBootConfig(request.config, request.imagePath, request.gameKey)\n",
    "boot policy identity",
)
backend = replace_once(
    backend,
    "    private fun applyPreBootConfig(config: PS2Backend.RuntimeConfig, imagePath: String) {\n",
    "    private fun applyPreBootConfig(\n"
    "        config: PS2Backend.RuntimeConfig,\n"
    "        imagePath: String,\n"
    "        gameKey: String\n"
    "    ) {\n",
    "preboot signature",
)

# #21's learned-GS renderer steering is superseded by the static architecture.
old_renderer_policy = '''        val forceLearnedGsVulkan = config.renderer == PS2Backend.Renderer.AUTO &&
            earlyLearnedProfile == PERF_PROFILE_GS && hasVulkan
        activeRenderer = if (forceLearnedGsVulkan) {
            NativeApp.renderVulkan()
            PS2Backend.Renderer.VULKAN
        } else when (config.renderer) {
'''
new_renderer_policy = '''        val architecture = PS2PerformanceArchitecture.resolve(appContext, gameKey, config)
        // #31: persisted profiler labels are diagnostics only. Renderer choice is
        // now Device Profile + GameDB-style policy, fixed before VM boot.
        val forceLearnedGsVulkan = false
        activeRenderer = when (architecture.renderer) {
'''
backend = replace_once(backend, old_renderer_policy, new_renderer_policy, "static renderer policy")

# The old learned-profile queue decision is also removed from the runtime path.
queue_pattern = re.compile(
    r"        val queueAhead = when \(learnedProfile\) \{\n"
    r".*?"
    r"        \}\.coerceIn\(1, 3\)\n",
    re.S,
)
backend, count = queue_pattern.subn(
    "        val queueAhead = architecture.queueAheadFrames\n",
    backend,
    count=1,
)
if count != 1:
    raise SystemExit(f"#31 static queue policy replacement count={count}")

backend = replace_once(
    backend,
    '        NativeApp.setSetting("EmuCore/GS", "CoalesceRenderPasses", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "SkipDuplicateFrames", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "GSBackThreadMode", "int", gsBackMode.toString())\n',
    '        NativeApp.setSetting("EmuCore/GS", "CoalesceRenderPasses", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "SkipDuplicateFrames", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/GS", "HWDownloadMode", "int", architecture.readbacks.nativeValue.toString())\n'
    '        NativeApp.setSetting("EmuCore/GS", "GSBackThreadMode", "int", gsBackMode.toString())\n',
    "readback policy",
)
backend = replace_once(
    backend,
    '        NativeApp.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", "true")\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", (cores >= 6).toString())\n',
    '        NativeApp.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", architecture.waitLoopHack.toString())\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", architecture.intcStatHack.toString())\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", architecture.vuFlagHack.toString())\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", architecture.instantVu1.toString())\n'
    '        NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", architecture.mtvu.toString())\n',
    "static speedhack bundle",
)

# The old pressure profiler is not part of the performance architecture. It was
# useful for diagnosis, but a Nether-style/GameDB-style session should not keep
# learning and scheduling policy in the middle of gameplay.
backend = replace_once(
    backend,
    "            applyPostBootConfig(request.config)\n"
    "            startPressureProfiler(request.imagePath)\n",
    "            applyPostBootConfig(request.config)\n"
    "            Log.i(\"OmniCorePS2Perf\", \"A6#31 static policy ${architecture.reason}\")\n",
    "retire live profiler",
)
backend = backend.replace('"A6#29 preboot profile=', '"A6#31 preboot profile=')
BACKEND.write_text(backend, encoding="utf-8")


# ---------------------------------------------------------------------------
# UI: include the title in the game identity so the policy DB can match games
# even when an ISO filename is generic. Telemetry is opt-in: no permanent 900ms
# sampling loop while the HUD is hidden.
# ---------------------------------------------------------------------------
activity = ACTIVITY.read_text(encoding="utf-8")
activity = replace_once(
    activity,
    '    private fun gameIdentity(): String = "${currentGame.fileName.lowercase()}|${currentGame.uri}"\n',
    '    private fun gameIdentity(): String = "${currentGame.title.lowercase()}|${currentGame.fileName.lowercase()}|${currentGame.uri}"\n',
    "title-aware GameDB identity",
)
activity = replace_once(
    activity,
    "            if (!destroyed && started) perfHandler.postDelayed(this, PERF_SAMPLE_MS)\n",
    "            if (!destroyed && started && perfHudVisible) perfHandler.postDelayed(this, PERF_SAMPLE_MS)\n",
    "opt-in telemetry reschedule",
)
activity = replace_once(
    activity,
    "                        perfHandler.removeCallbacks(perfSampler)\n"
    "                        perfHandler.postDelayed(perfSampler, PERF_SAMPLE_MS)\n",
    "                        perfHandler.removeCallbacks(perfSampler)\n",
    "no background telemetry after boot",
)
activity = replace_once(
    activity,
    "                        } else if (started) {\n"
    "                            statusView.visibility = View.GONE\n"
    "                        }\n",
    "                        } else if (started) {\n"
    "                            perfHandler.removeCallbacks(perfSampler)\n"
    "                            statusView.visibility = View.GONE\n"
    "                        }\n",
    "stop telemetry with HUD",
)
ACTIVITY.write_text(activity, encoding="utf-8")

print("OMNICORE_PCSX2_ALPHA6_31_ARCH_OK static_session_policy=1 device_profile=1 game_profile=1 readback_policy=1 profiler_off=1 hud_opt_in=1 gs_back_off_preserved=1")
