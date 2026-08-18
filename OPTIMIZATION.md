# OmniCore SmartPerf

SmartPerf is deliberately conservative. Its objective is stable frame delivery, low input/audio latency and sustainable performance, not a short benchmark spike followed by thermal throttling.

OmniCore uses **console-specific performance controllers**. PS1 and N64 share general Android principles such as thermal awareness and bounded audio buffering, but they do not share console-specific tuning state.

## Global policies

### Intelligent
Default. Classifies the device conservatively and adapts to measured runtime conditions. CPU core count alone is never treated as proof of high performance.

### Performance
Requests the lowest safe latency while the device is thermally healthy. Under sustained pressure it backs off rather than forcing an aggressive path indefinitely.

### Balanced
Keeps predictable frame pacing and interactive audio without the tightest low-latency behavior.

### Battery
Prefers sustained/power-efficient scheduling hints and a larger baseline audio cushion.

## Device signals

- Android low-RAM classification.
- Total and available system memory.
- Available CPU cores.
- 32/64-bit ABI capability.
- Maximum CPU frequency when exposed safely by the kernel.
- Media Performance Class when declared by Android.
- Runtime thermal status.
- Android power-save state.

Profiles are cached after detection so adaptation does not repeatedly perform unnecessary hardware probing.

# Nintendo 64 performance path

The N64 runtime uses Mupen64Plus-Next + GLideN64 with an Android-native host, EGL/GLES3, AAudio and per-session telemetry.

## SmartPrecompile

Alpha 14 adds a bounded hidden pre-execution stage after GLideN64 context creation.

When the core exposes a safe serialization path, OmniCore:

1. Captures the exact boot state.
2. Temporarily suppresses normal input and visible presentation.
3. Executes a small number of core frames.
4. Lets early Dynarec blocks and GLideN64 shader programs materialize.
5. Flushes queued GPU work.
6. Restores the boot state before the first visible gameplay frame.

This is intentionally **not** a full-game shader compiler. The pass is short and bounded so it can move predictable first-use work away from gameplay without creating an excessive startup stall.

If a safe snapshot path is unavailable, SmartPrecompile skips itself rather than risking game-state corruption.

## Persistent GLideN64 shader cache

GLideN64 shader storage is enabled only when OmniCore confirms that the expected persistent cache directory is writable.

Current cache behavior:

- cache location is derived from the N64 system directory;
- existing shader binaries remain persistent between runs;
- warm-up prioritizes recently modified files;
- startup page-cache prefetch uses a bounded **12 MiB** budget;
- shader-cache readiness is exposed through telemetry;
- SmartPrecompile and cache warm-up are treated as cooperative stages rather than independent optimizations.

The first encounter with a new shader combination can still compile at runtime. Repeated launches benefit only when the device/driver can restore the cached shader representation.

## WarmStart

WarmStart protects the first seconds of an N64 session while Dynarec, GLideN64 and audio state stabilize.

During this phase:

- AAudio keeps a larger safety cushion;
- aggressive low-latency tuning is delayed;
- the requested visual profile is preserved unless a true emergency condition appears;
- SmartPrecompile completion can shorten the minimum warm-up window once stable telemetry is observed.

## ADPF cooperation

When Android Performance Hint APIs are available, the N64 host reports measured emulation work and can issue transient workload hints.

Current events include:

- session startup;
- SmartPrecompile CPU + GPU work;
- renderer/presentation spikes;
- very slow frame spikes;
- sustained frame pressure;
- menu/framebuffer transition pressure.

These hints are transient. OmniCore does not force CPU/GPU frequencies and does not depend on root access.

## RenderShield / BurstShield

The runtime separates general frame pressure from presentation-heavy pressure.

- Frame time is sampled over a rolling window.
- Presentation time is measured separately around the EGL present path.
- A render spike can request GPU-oriented transient headroom.
- A very slow frame can request broader CPU + GPU headroom.
- Repeated pressure can temporarily expand the AAudio cushion.
- Short spikes do not automatically reduce internal resolution or disable framebuffer emulation.

## DirectPresenter

On devices that accept the requested native-buffer geometry, OmniCore renders the N64 frame directly into the default framebuffer at the selected internal resolution and lets Android's compositor perform final display scaling.

This avoids the older OmniCore full-frame GLES blit before `eglSwapBuffers()`.

If the device does not expose the requested surface dimensions, OmniCore automatically falls back to the proven **RenderBridge** frontend framebuffer path.

## Protected N64 visual policy

The current compatibility-sensitive defaults intentionally preserve:

- GLideN64;
- framebuffer emulation where required;
- Dynarec;
- RSP HLE;
- Intelligent **1.5×** rendering (`960×720` 4:3 / `960×540` widescreen);
- widescreen independence from framebuffer policy;
- LOD emulation;
- compatibility-first framebuffer copy behavior.

SmartPerf may react to severe thermal/memory emergencies, but routine stutter is not solved by blindly dropping the protected 1.5× target.

## N64 audio

The N64 host opens AAudio at the device's natural low-latency rate and performs lightweight frontend resampling when the core rate differs.

Runtime behavior includes:

- exclusive mode first, shared-mode fallback;
- bounded buffer-size adaptation;
- internal ring-underrun tracking in addition to platform xruns;
- primed startup before playback begins;
- short fade concealment for tiny starvation gaps;
- temporary buffer expansion during renderer or transition pressure;
- gradual latency reduction after stability is proven.

# PlayStation 1 performance path

The stable PS1 runtime keeps its existing isolated SmartPerf behavior.

Highlights include:

- PCSX-ReARMed ARM/ARM64 dynarec;
- native EGL/OpenGL ES presentation;
- adaptive AAudio;
- background image/disc preparation;
- persistent prepared-content cache;
- conservative thermal adaptation;
- stable DEV update path.

N64-specific renderer, shader-cache and SmartPrecompile state never leaks into the PS1 runtime.

# Shared native fast paths

- ARM64 and ARMv7 native builds.
- Bounded lock-free/atomic audio-ring style paths where appropriate.
- Block copies instead of per-sample modulo work around ring wrap points.
- Heavy content preparation outside the UI thread.
- Seekable Storage Access Framework files can use direct descriptor-backed access where supported.
- Non-seekable providers fall back to bounded buffered copies.
- Native host builds use optimization and section garbage collection.
- Native linker configuration requests 16 KB LOAD alignment.

# Frame pacing

- The emulated refresh rate comes from core AV information.
- Android Surface frame-rate hints are applied when available.
- N64 uses one explicit pacing owner; EGL swap interval remains disabled to avoid double pacing.
- If emulation falls behind significantly, old timing debt is discarded rather than repaid with a burst of back-to-back frames.
- Audio and presentation telemetry feed adaptation instead of relying on static device guesses alone.

# Thermal and memory rules

- Moderate thermal pressure removes unnecessary aggressive behavior.
- Severe thermal pressure takes precedence over short-term latency goals.
- Memory pressure can disable optional expensive behavior only when compatibility policy permits it.
- Audio protection is allowed to react faster than CPU/RDP policy changes.
- Heavier N64 configuration changes are applied only at safe session boundaries rather than mid-frame.

# Build-level optimization

- `compileSdk` / `targetSdk`: 36.
- Android NDK: `28.2.13676358`.
- Native runtime: C++20.
- ABIs: `arm64-v8a`, `armeabi-v7a`.
- Native linker configuration requests **16 KB** LOAD alignment.
- CI verifies every packaged `.so` LOAD segment with `llvm-readelf`.
- CI verifies the APK with `zipalign -P 16`.
- DEV release CI verifies the expected signing certificate.

# Guardrails

1. No root requirement.
2. No hidden Android APIs.
3. No writes to system properties.
4. No forced CPU/GPU clocks.
5. No blind permanent frameskip as a SmartPerf strategy.
6. Unsupported ADPF / Surface hints become safe no-ops.
7. Console-specific tuning stays behind each backend boundary.
8. N64 framebuffer compatibility is not globally disabled to chase benchmark numbers.
9. N64 Intelligent 1.5× is treated as a protected quality target during normal tuning.
10. PS1 remains isolated from experimental N64 performance work.
