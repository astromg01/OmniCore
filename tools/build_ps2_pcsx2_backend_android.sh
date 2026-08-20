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
      echo '--- last 220 import lines ---'
      tail -n 220 "$LOG"
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
# upstream's own dual-core (4K + 16K) pipeline. Upstream forms that universal APK
# from a complete 4K APK and replaces only the emucore entries, so OmniCore must
# preserve the complete ARM64 native runtime payload too. DT_NEEDED alone cannot
# discover helpers loaded later with dlopen (ANGLE/runtime hooks).
echo "Downloading official ARMSX2 nightly: $URL"
curl --fail --location --retry 4 --retry-all-errors --connect-timeout 30 \
  --output "$APK" "$URL"
test -s "$APK"
unzip -tq "$APK" >/dev/null
sha256sum "$APK" | tee "$WORK/upstream-apk.sha256"
unzip -l "$APK" > "$LIST"

grep -Fq 'lib/arm64-v8a/libemucore_4k.so' "$LIST"
grep -Fq 'lib/arm64-v8a/libemucore_16k.so' "$LIST"
grep -Fq 'lib/arm64-v8a/libEGL_angle.so' "$LIST"
grep -Fq 'lib/arm64-v8a/libGLESv2_angle.so' "$LIST"
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

READELF="${ANDROID_NDK_HOME:-${OMNI_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$READELF"

# Android/NDK platform libraries are supplied by the OS and must never be
# vendored. libc++_shared.so is intentionally NOT here: it belongs to the app
# payload and must match the official ARMSX2 build.
is_system_soname() {
  case "$1" in
    libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libz.so|libstdc++.so|libEGL.so|libGLESv1_CM.so|libGLESv2.so|libGLESv3.so|libvulkan.so|libOpenSLES.so|libaaudio.so|libamidi.so|libjnigraphics.so|libnativewindow.so|libmediandk.so|libcamera2ndk.so|libbinder_ndk.so|libneuralnetworks.so|libsync.so)
      return 0 ;;
    *)
      return 1 ;;
  esac
}

# OmniCore already receives this AndroidX native library from its Gradle
# dependency graph. Copying the upstream copy into src/main/jniLibs would create
# a duplicate packaging input; the final APK still contains the dependency copy.
is_gradle_packaged_soname() {
  case "$1" in
    libandroidx.graphics.path.so) return 0 ;;
    *) return 1 ;;
  esac
}

# These are OmniCore-owned runtimes built earlier in the workflow. A future
# upstream asset must never silently overwrite one of them.
is_omnicore_owned_soname() {
  case "$1" in
    libomnicore_runtime.so|libomnicore_n64_runtime.so|libomnicore_ps2_runtime.so|libpcsx_rearmed_libretro.so|libmupen64plus_next_libretro.so)
      return 0 ;;
    *) return 1 ;;
  esac
}

upstream_native="$WORK/upstream-native-payload.txt"
packaged_native="$WORK/packaged-armsx2-native-payload.txt"
find "$STAGE/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | sort -u > "$upstream_native"
test -s "$upstream_native"
: > "$packaged_native"

# Mirror the complete native payload from the official nightly. This is the key
# difference from attempts 6/7, which copied only the recursive DT_NEEDED graph
# and therefore omitted dynamically loaded helpers present in upstream's base APK.
while IFS= read -r lib; do
  [[ -z "$lib" ]] && continue
  if is_omnicore_owned_soname "$lib"; then
    echo "Refusing ARMSX2/OmniCore native library collision: $lib" >&2
    exit 1
  fi
  if is_gradle_packaged_soname "$lib"; then
    echo "Keeping Gradle-provided native dependency instead of duplicate upstream copy: $lib"
    continue
  fi
  cp -f "$STAGE/lib/arm64-v8a/$lib" "$JNI_DIR/$lib"
  echo "$lib" >> "$packaged_native"
done < "$upstream_native"
sort -u -o "$packaged_native" "$packaged_native"

for required in libemucore_4k.so libemucore_16k.so libc++_shared.so liblibrashader_capi.so libEGL_angle.so libGLESv2_angle.so; do
  test -s "$JNI_DIR/$required"
done

# Keep recursive linker validation as a guard, but not as the payload-selection
# mechanism. Dynamic dlopen helpers are now present even when they do not appear
# in an emucore DT_NEEDED table.
while IFS= read -r lib; do
  [[ -z "$lib" ]] && continue
  src_lib="$JNI_DIR/$lib"
  while IFS= read -r dep; do
    [[ -z "$dep" ]] && continue
    if is_system_soname "$dep" || is_gradle_packaged_soname "$dep"; then
      continue
    fi
    if [[ ! -s "$JNI_DIR/$dep" ]]; then
      echo "Final ARMSX2 native payload unresolved: $lib -> $dep" >&2
      exit 1
    fi
  done < <("$READELF" -d "$src_lib" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done < "$packaged_native"

# SDL's native Android glue resolves org.libsdl.app.* classes from JNI while
# libemucore is being loaded. Attempt #8 proved that importing only the native
# payload is insufficient: the device hit ClassNotFoundException for SDLActivity
# before PCSX2 could initialize. Import the complete, pinned SDL Java bridge from
# the exact same ARMSX2 revision so Java/native ABI stays matched.
SDL_JAVA_SRC="$SRC/platforms/android/app/src/main/java/org/libsdl/app"
SDL_JAVA_DST="app/src/main/java/org/libsdl/app"
test -s "$SDL_JAVA_SRC/SDLActivity.java"
test -s "$SDL_JAVA_SRC/SDL.java"
test -s "$SDL_JAVA_SRC/SDLAudioManager.java"
test -s "$SDL_JAVA_SRC/SDLControllerManager.java"
rm -rf "$SDL_JAVA_DST"
mkdir -p "$SDL_JAVA_DST"
cp -a "$SDL_JAVA_SRC/." "$SDL_JAVA_DST/"
SDL_JAVA_COUNT="$(find "$SDL_JAVA_DST" -maxdepth 1 -type f -name '*.java' | wc -l | tr -d ' ')"
if (( SDL_JAVA_COUNT < 8 )); then
  echo "Pinned SDL Android Java bridge looks incomplete ($SDL_JAVA_COUNT files)" >&2
  exit 1
fi

grep -Fq 'public class SDLActivity extends Activity' "$SDL_JAVA_DST/SDLActivity.java"
grep -Fq 'static public void setupJNI()' "$SDL_JAVA_DST/SDL.java"

# Keep a useful real-device linker diagnostic, but do not rewrite newer
# diagnostics added by subsequent Alpha 6 runtime work. This step must be
# idempotent so later attempts can change the message without breaking imports.
BACKEND="app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt"
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")
old = 'NativeApp.hasNoNativeBinary -> "Pinned PCSX2 emucore is not packaged for this page size."'
new = 'NativeApp.hasNoNativeBinary -> "A6#9 ${NativeApp.nativeLoadDiagnostic()} ABI=${Build.SUPPORTED_ABIS.joinToString("/")}"'
if old in text:
    text = text.replace(old, new)
elif 'NativeApp.nativeLoadDiagnostic()' not in text:
    raise SystemExit("PCSX2 backend loader diagnostic anchor not found")
p.write_text(text, encoding="utf-8")
PY
grep -Fq 'NativeApp.nativeLoadDiagnostic()' "$BACKEND"

# Alpha 6 device-test regression guard. Attempt #13 changed EE cycle rate live
# whenever FPS dipped. ARMSX2 applies that through VMManager::ApplySettings(),
# which reconfigures CPU/JIT state; workload bursts therefore caused the governor
# itself to add hitches. Keep EE rate/skip at PCSX2 defaults for the whole session.
# The same device test also exposed Partial texture preloading, while the pinned
# core's own default is Full. Force Full so texture residency work happens up
# front instead of during entity/effect-heavy gameplay. VU program caching is
# enabled for repeat-run microprogram compilation stutter reduction on ARM64.
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")

old_preload = 'runCatching { NativeApp.renderPreloading(1) }'
if old_preload in text:
    text = text.replace(old_preload, 'runCatching { NativeApp.renderPreloading(2) }', 1)
elif 'runCatching { NativeApp.renderPreloading(2) }' not in text:
    raise SystemExit('PS2 Full texture-preloading anchor not found')

fastmem = '        NativeApp.setSetting("EmuCore/CPU/Recompiler", "EnableFastmem", "bool", "true")'
vu_cache = '        NativeApp.setSetting("EmuCore/CPU/Recompiler", "EnableVUProgramCache", "bool", "true")'
if vu_cache not in text:
    if fastmem not in text:
        raise SystemExit('PS2 recompiler settings anchor not found')
    text = text.replace(fastmem, fastmem + '\n' + vu_cache, 1)

# The old governor must not be started. It can remain in source only until the
# next transform replaces it with a non-JIT pressure profiler.
text = text.replace(
    '            startAdaptiveGovernor()\n            PS2Backend.BootResult.Started(id, activeRenderer)',
    '            PS2Backend.BootResult.Started(id, activeRenderer)',
    1,
)

# Avoid a redundant post-boot cycle-skip write. ARMSX2 routes it through a full
# ApplySettings() even when writing zero, so doing it after the VM starts creates
# a needless JIT/settings transition. Pre-boot settings already pin it to zero.
text = text.replace(
    '        runCatching { NativeApp.speedhackEecycleskip(0) }\n        runCatching { NativeApp.setInstantVU1(true) }',
    '        runCatching { NativeApp.setInstantVU1(true) }',
    1,
)

p.write_text(text, encoding="utf-8")
PY

# Alpha 6 #15: depth/geometry + entity-pressure expansion.
#
# Device testing shows #14 is a stable CPU/JIT baseline, but deep scenes (large
# visible world/geometry) and dense entity bursts can still drop hard. Do NOT
# return to dynamic EE cycle hacks: those invalidate/reconfigure the JIT and were
# the source of #13's hitch regression. Instead:
#   1) coalesce consecutive render passes, which the pinned core explicitly
#      implements for tiled mobile GPUs to reduce tile load/store bandwidth;
#   2) enable the GS front/back Pipelined mode on sufficiently multi-core phones,
#      but learn per-game pressure and disable it on the next boot when the title
#      proves compute/entity-bound (so the extra GS thread cannot starve EE/VU);
#   3) keep a background pressure profiler which samples FPS + process CPU load +
#      thermal state only. It NEVER mutates EE cycle rate/skip or rebuilds JIT.
#      Its only live action is thermal-safe ADPF gating; its render/compute choice
#      is persisted and consumed pre-boot on the next launch.
python3 - "$BACKEND" <<'PY'
from pathlib import Path
import re
import sys

p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")

# Feed the image path into pre-boot policy selection and arm the safe profiler
# only after the VM is fully active.
text = text.replace(
    '            applyPreBootConfig(request.config)\n',
    '            applyPreBootConfig(request.config, request.imagePath)\n',
    1,
)
text = text.replace(
    '            running = true\n            applyPostBootConfig(request.config)\n            PS2Backend.BootResult.Started(id, activeRenderer)',
    '            running = true\n            applyPostBootConfig(request.config)\n            startPressureProfiler(request.imagePath)\n            PS2Backend.BootResult.Started(id, activeRenderer)',
    1,
)

text = text.replace(
    '    private fun applyPreBootConfig(config: PS2Backend.RuntimeConfig) {',
    '    private fun applyPreBootConfig(config: PS2Backend.RuntimeConfig, imagePath: String) {',
    1,
)

queue_anchor = '''        NativeApp.setSetting("EmuCore/GS", "VsyncQueueSize", "int", queueAhead.coerceIn(1, 3).toString())'''
scene_block = '''        NativeApp.setSetting("EmuCore/GS", "VsyncQueueSize", "int", queueAhead.coerceIn(1, 3).toString())

        // Mobile tiled-GPU depth/geometry path. Coalescing keeps consecutive
        // draws targeting the same RT in one render pass, avoiding repeated tile
        // load/store traffic. Pipelined GS splits GIF parsing/vertex building
        // from draw/texture-cache/device work. It is enabled only when the phone
        // has enough cores and the learned per-game profile has not identified
        // compute/entity pressure.
        val perfPrefs = appContext.getSharedPreferences(PERF_PREFS, Context.MODE_PRIVATE)
        val perfKey = perfProfileKey(imagePath)
        val learnedProfile = perfPrefs.getInt(perfKey, PERF_PROFILE_UNKNOWN)
        val am = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val power = appContext.getSystemService(PowerManager::class.java)
        val pipelineCapable = Runtime.getRuntime().availableProcessors() >= 8 &&
            memory.totalMem >= 3L * 1024L * 1024L * 1024L && power?.isPowerSaveMode != true
        val useGsPipeline = pipelineCapable && learnedProfile != PERF_PROFILE_COMPUTE
        NativeApp.setSetting("EmuCore/GS", "CoalesceRenderPasses", "bool", "true")
        NativeApp.setSetting("EmuCore/GS", "SkipDuplicateFrames", "bool", "true")
        NativeApp.setSetting("EmuCore/GS", "GSBackThreadMode", "int", if (useGsPipeline) "3" else "0")
        Log.i("OmniCorePS2Perf", "preboot profile=$learnedProfile gsPipeline=$useGsPipeline cores=${Runtime.getRuntime().availableProcessors()}")'''
if 'CoalesceRenderPasses' not in text:
    if queue_anchor not in text:
        raise SystemExit('PS2 VsyncQueueSize anchor not found for scene-pressure block')
    text = text.replace(queue_anchor, scene_block, 1)

# Replace the old EE-cycle governor wholesale. Keeping it around, even dead,
# makes it too easy for a later refactor to re-arm the harmful path.
pattern = re.compile(
    r'\n    private fun startAdaptiveGovernor\(\) \{.*?\n    private fun publishSurface\(surface: Surface\) \{',
    re.S,
)
replacement = r'''
    private fun startPressureProfiler(imagePath: String) {
        governorStop = false
        governorThread?.interrupt()
        val profileKey = perfProfileKey(imagePath)
        governorThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val prefs = appContext.getSharedPreferences(PERF_PREFS, Context.MODE_PRIVATE)
            val power = appContext.getSystemService(PowerManager::class.java)
            var lastWallNs = System.nanoTime()
            var lastCpuMs = Process.getElapsedCpuTime()
            var renderPressure = 0f
            var computePressure = 0f
            var samples = 0
            var learned = prefs.getInt(profileKey, PERF_PROFILE_UNKNOWN)
            var adpfEnabled = Build.VERSION.SDK_INT >= 33 && power?.isPowerSaveMode != true

            while (!governorStop && running) {
                try { Thread.sleep(PRESSURE_SAMPLE_MS) } catch (_: InterruptedException) {
                    if (governorStop || !running) break
                }
                if (governorStop || !running) break
                if (paused) {
                    lastWallNs = System.nanoTime()
                    lastCpuMs = Process.getElapsedCpuTime()
                    continue
                }

                val fps = runCatching { NativeApp.getFPS() }.getOrDefault(-1f)
                val nominal = runCatching { NativeApp.getNominalFrameRate() }.getOrDefault(0f)
                val nowWallNs = System.nanoTime()
                val nowCpuMs = Process.getElapsedCpuTime()
                val wallMs = ((nowWallNs - lastWallNs) / 1_000_000f).coerceAtLeast(1f)
                val cpuMs = (nowCpuMs - lastCpuMs).coerceAtLeast(0L).toFloat()
                lastWallNs = nowWallNs
                lastCpuMs = nowCpuMs
                if (fps <= 1f) continue

                val target = if (nominal > 20f) nominal else 60f
                val ratio = (fps / target).coerceIn(0f, 1.15f)
                val processCoreLoad = (cpuMs / wallMs / cores.toFloat()).coerceIn(0f, 1.25f)

                // Decay makes this a recent-workload classifier instead of a
                // permanent verdict. Low FPS with broad process CPU saturation
                // is treated as compute/entity pressure; low FPS without it is
                // treated as GS/render/depth pressure. This never changes VM/JIT
                // settings live -- the result is only a next-boot hint.
                renderPressure *= 0.88f
                computePressure *= 0.88f
                if (ratio < 0.90f) {
                    val severity = ((0.90f - ratio) / 0.90f).coerceIn(0f, 1f)
                    if (processCoreLoad >= 0.34f) {
                        computePressure += severity * (1.0f + processCoreLoad)
                    } else {
                        renderPressure += severity * (1.20f - processCoreLoad).coerceAtLeast(0.35f)
                    }
                }
                samples++

                val thermal = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                        .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
                } else PowerManager.THERMAL_STATUS_NONE
                val shouldUseAdpf = Build.VERSION.SDK_INT >= 33 && power?.isPowerSaveMode != true &&
                    thermal < PowerManager.THERMAL_STATUS_SEVERE
                if (shouldUseAdpf != adpfEnabled) {
                    runCatching { NativeApp.setAdpfEnabled(shouldUseAdpf) }
                    adpfEnabled = shouldUseAdpf
                }

                if (samples % 8 == 0) {
                    val next = when {
                        renderPressure >= 0.80f && renderPressure > computePressure * 1.22f -> PERF_PROFILE_RENDER
                        computePressure >= 0.80f && computePressure > renderPressure * 1.22f -> PERF_PROFILE_COMPUTE
                        else -> PERF_PROFILE_UNKNOWN
                    }
                    if (next != PERF_PROFILE_UNKNOWN && next != learned) {
                        prefs.edit().putInt(profileKey, next).apply()
                        learned = next
                    }
                    Log.i(
                        "OmniCorePS2Perf",
                        "pressure profile=$learned fps=${String.format("%.1f", fps)}/${String.format("%.1f", target)} " +
                            "cpu=${String.format("%.2f", processCoreLoad)} render=${String.format("%.2f", renderPressure)} " +
                            "compute=${String.format("%.2f", computePressure)} thermal=$thermal adpf=$adpfEnabled"
                    )
                }
            }
        }, "OmniCore-PS2-PressureProfiler").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun perfProfileKey(imagePath: String): String =
        "game_${imagePath.hashCode().toUInt().toString(16)}"

    private fun publishSurface(surface: Surface) {'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit('Old PS2 adaptive governor block not found/replaced')

text = text.replace('        private const val GOVERNOR_SAMPLE_MS = 850L\n',
                    '        private const val PRESSURE_SAMPLE_MS = 900L\n', 1)
companion_anchor = '        private const val ANALOG_DEADZONE = 0.001f\n'
perf_constants = '''        private const val PERF_PREFS = "omnicore_ps2_perf_learning_v1"
        private const val PERF_PROFILE_COMPUTE = -1
        private const val PERF_PROFILE_UNKNOWN = 0
        private const val PERF_PROFILE_RENDER = 1
        private const val ANALOG_DEADZONE = 0.001f
'''
if 'PERF_PROFILE_RENDER' not in text:
    if companion_anchor not in text:
        raise SystemExit('PS2 companion constant anchor not found')
    text = text.replace(companion_anchor, perf_constants, 1)

# Retire stale state from the old governor.
text = text.replace('    @Volatile private var adaptiveLevel = 0\n', '')
text = text.replace('            adaptiveLevel = 0\n', '')
text = text.replace('        adaptiveLevel = 0\n', '')
text = text.replace('import kotlin.math.min\n', '')

p.write_text(text, encoding="utf-8")
PY

grep -Fq 'runCatching { NativeApp.renderPreloading(2) }' "$BACKEND"
grep -Fq 'EnableVUProgramCache' "$BACKEND"
grep -Fq 'CoalesceRenderPasses' "$BACKEND"
grep -Fq 'GSBackThreadMode' "$BACKEND"
grep -Fq 'startPressureProfiler(request.imagePath)' "$BACKEND"
grep -Fq 'PERF_PROFILE_RENDER' "$BACKEND"
if grep -Fq 'startAdaptiveGovernor' "$BACKEND"; then
  echo 'Old adaptive EE-cycle governor still exists' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecyclerate(' "$BACKEND"; then
  echo 'Live EE cycle-rate mutation reintroduced into PS2 backend' >&2
  exit 1
fi
if grep -Fq 'NativeApp.speedhackEecycleskip(' "$BACKEND"; then
  echo 'Live EE cycle-skip mutation reintroduced into PS2 backend' >&2
  exit 1
fi

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
printf 'OMNICORE_PCSX2_IMPORT_OK pin=%s tag=%s upstream_native=%s packaged_native=%s sdl_java=%s resources=%s sha256=%s\n' \
  "$PIN" "$TAG" \
  "$(wc -l < "$upstream_native" | tr -d ' ')" \
  "$(wc -l < "$packaged_native" | tr -d ' ')" \
  "$SDL_JAVA_COUNT" \
  "$(find "$RESOURCE_DST" -type f | wc -l)" \
  "$(cut -d' ' -f1 "$WORK/upstream-apk.sha256")"
echo 'Official ARMSX2 ARM64 native payload:'
cat "$upstream_native"
echo 'Copied ARMSX2 ARM64 native payload:'
cat "$packaged_native"
echo "Copied pinned SDL Android Java bridge: $SDL_JAVA_COUNT files"
