# OmniCore SmartPerf 2

SmartPerf is deliberately conservative. Its objective is stable frame delivery, low input/audio latency and sustainable performance, not a short benchmark spike followed by thermal throttling.

## Policies

### Intelligent
Default. Classifies the device conservatively and adapts the runtime to thermal conditions. Eight CPU cores alone are never treated as proof of high performance.

### Performance
Requests the lowest safe latency while the device is thermally healthy. At moderate thermal pressure it automatically backs off to balanced pacing/audio instead of continuing an aggressive final-yield loop.

### Balanced
Keeps interactive audio and predictable frame pacing without the tighter low-latency path.

### Battery
Uses sustained/power-efficient scheduling hints and a larger baseline audio buffer.

## Device signals

- Android low-RAM classification.
- Total system RAM.
- Available CPU cores.
- 32/64-bit ABI capability.
- Maximum CPU frequency, only when the kernel exposes a readable cpufreq value.
- Media Performance Class when declared by Android.
- Runtime thermal status.

The device profile is cached after detection so thermal-policy recalculation does not repeatedly read sysfs.

## Runtime signals

- Measured `retro_run()` work duration is reported to Android Performance Hint / ADPF when the API is available.
- An EWMA of emulation work versus frame budget decides whether the final low-latency pacing path has enough headroom to be worthwhile.
- AAudio xrun count is sampled periodically.
- The internal audio ring also records starvation events, because a callback that safely outputs silence may not necessarily appear as a platform xrun.
- The libretro batch callback reports only the stereo frames actually accepted by the ring, preserving backpressure instead of masking saturation.

## Native fast paths

- PCSX-ReARMed ARM/ARM64 dynarec is enabled by its Android build.
- NEON GPU paths are used by the upstream core for supported ARM ABIs.
- RGB565 video goes directly into an RGB565 `ANativeWindow` buffer with row `memcpy` and no per-pixel conversion.
- Audio ring writes/reads use at most two block copies around the wrap point rather than a modulo operation per sample.
- The ring capacity is bounded so temporary stalls cannot create seconds of queued audio latency.
- Surface buffer allocation and frame-rate hints are requested dynamically when supported.
- Heavy content preparation runs outside the UI thread.
- Non-seekable fallback copies use a 256 KiB transfer buffer to reduce syscall/stream overhead on multi-gigabyte images.
- Seekable Storage Access Framework files use `/proc/self/fd` through a session symlink, avoiding a duplicate large ROM/disc image. Only non-seekable providers fall back to a cache copy.

## Adaptive audio

The requested baseline is 2, 3 or 4 AAudio bursts depending on policy. At runtime:

1. New xrun or internal starvation -> increase the buffer by one burst, up to 8.
2. Stable intervals -> gradually return toward the policy baseline.
3. Exclusive stream failure -> immediately retry shared mode.
4. Thermal policy changes can reopen the stream with the safer configuration.

This favors continuity only when the real session proves it needs more buffering.

## Frame pacing

- The emulated refresh rate comes from the core's AV information.
- The Android `Surface` receives that frame-rate hint when the API exists.
- Normal modes sleep to the next frame deadline.
- Low-latency mode only uses a very short final yield when the measured emulation workload stays comfortably below budget.
- If the runtime falls more than 250 ms behind, the deadline is reset rather than executing a burst of catch-up frames.

## Thermal rules

- Moderate thermal status disables aggressive pacing in Intelligent mode and backs Performance mode down to Balanced policy.
- Severe or worse thermal status always selects the sustained policy, larger audio baseline and power-efficiency preference.
- Thermal protection takes precedence over the user's performance preset because sustained throttling is worse than a controlled reduction.

## Build-level optimization

- OmniCore's native host uses `-O2`, section garbage collection and hidden symbol visibility.
- Release builds can use LTO.
- Native linker configuration requests 16 KB LOAD alignment.
- CI verifies every packaged `.so` LOAD segment with `llvm-readelf` and verifies the APK with `zipalign -P 16`.

## Guardrails

1. No root requirement.
2. No hidden Android APIs.
3. No writes to system properties.
4. No forced CPU/GPU clocks.
5. No blind frameskip or PS1 clock hacks enabled by SmartPerf.
6. Unsupported ADPF/Surface hints are discovered dynamically and become no-ops.
7. Console-specific tuning stays behind each core adapter so one console's assumptions cannot leak into another core.
