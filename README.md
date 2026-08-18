# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame pacing, clean touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.15%20Alpha%2016-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.15-n64-alpha16)
[![PS1](https://img.shields.io/badge/PS1-device%20validated-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![N64](https://img.shields.io/badge/N64-real--device%20testing-F4C95D)](https://github.com/libretro/mupen64plus-libretro-nx)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40astromg01-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**, not a frontend built around a single console. Each supported system owns its own core integration, runtime policy, settings, storage, performance logic and console-specific input behavior while sharing one Android library experience.

The current stable backend is **PlayStation 1**. **Nintendo 64** is the second integrated backend and is in active physical-device Alpha development. PSP, Wii / GameCube, PlayStation 2 and Nintendo Switch remain roadmap targets.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls validated on Android hardware |
| N64 device-test | **0.10.15 Alpha 16** | PrecisionGovernor v2, GLideN64/Mupen64Plus-Next, game-aware Smart Analog and real-device performance tuning |

The N64 channel is experimental. CI validates architecture, compilation, packaging, signing, native core coexistence and 16 KB compatibility. Real gameplay quality, Android driver behavior and remaining game-specific performance issues are validated on physical Android devices.

## OmniCore 0.10.15 — N64 Alpha 16

Alpha 16 keeps the successful Alpha 15 optimization philosophy and refines it instead of replacing it. The main target is **more stable frame delivery with fewer false performance reactions**.

### PrecisionGovernor v2

PrecisionGovernor v2 separates short-lived spikes from sustained bottlenecks and classifies pressure as **CPU**, **GPU/presentation**, **mixed** or **stable**.

The current controller uses:

- fast and slow frame-time EWMAs;
- present-time tracking;
- accumulated **pressure debt** instead of reacting to one bad frame;
- candidate streaks before switching bottleneck classification;
- minimum dwell time in the current governor state;
- gradual recovery after sustained stability;
- bounded ADPF CPU/GPU hints rather than permanent maximum-performance behavior;
- separate audio control based on real AAudio/ring-buffer evidence;
- confidence telemetry so a weak guess is distinguishable from a sustained diagnosis;
- smoothed frame-jitter telemetry for stability analysis.

The runtime performance panel exposes the current state in forms such as:

`P-GOV2 GPU 87% • jitter 1.42 ms`

This lets real-device testing focus on the actual bottleneck instead of using FPS alone.

### Passive WarmCache

The aggressive hidden SmartPrecompile experiment introduced in Alpha 14 is **not used anymore**.

OmniCore does not run hidden emulation frames, does not restore a boot savestate to simulate precompilation and does not force `glFinish()` before gameplay.

Shader-cache warming is now passive and strictly bounded to approximately **2 MiB**, including the kernel prefetch request. Persistent GLideN64 shader storage remains available when the device/driver can reuse cached binaries.

### Stable performance rules

The current N64 optimization policy deliberately protects visual and compatibility settings:

- Intelligent **1.5×** internal resolution remains preserved (`960×720` at 4:3 / `960×540` widescreen);
- framebuffer emulation is not disabled automatically;
- renderer threading is not enabled merely because a device exposes many CPU cores;
- Dynarec remains the preferred CPU path;
- HLE RSP remains the default compatibility/performance balance;
- short renderer spikes do not automatically increase audio buffering;
- WarmStart is bounded and exits after stability is demonstrated;
- no root, forced clocks, hidden vendor APIs or persistent system-property tweaks are required.

### DirectPresenter + RenderBridge

On compatible Android devices OmniCore can render directly to a native buffer matching the selected internal N64 resolution, allowing Android's compositor to perform final display scaling without the older extra full-frame frontend blit.

If the device rejects the requested buffer geometry, OmniCore automatically falls back to the proven **RenderBridge** path.

### Smart Analog + Game Intelligence

The N64 input system includes ROM-aware compatibility logic for games whose movement is digital rather than analog.

Current modes:

- **Inteligente / AUTO** — normal analog behavior with compatibility-aware analog-to-D-pad projection where needed;
- **Somente analógico** — never synthesizes D-pad input;
- **Analógico → D-pad** — explicitly converts the left analog into digital directions.

The native analog path also provides radial deadzone/sensitivity shaping, directional hysteresis and diagonal D-pad projection. Physical/touch D-pad input remains separated from synthesized Smart Analog input.

**Kirby** is the first explicit digital-movement profile, allowing the left analog to control titles that otherwise respond only to N64 D-pad directions while AUTO is selected.

## N64 compatibility foundation

The current N64 runtime preserves:

- Mupen64Plus-Next/libretro pinned to `f275caf4b2bfa1e6d1c51636746ea793f3d80320`;
- GLideN64 over OpenGL ES 3;
- isolated Android N64 process;
- DirectPresenter with RenderBridge fallback;
- adaptive native AAudio;
- persistent shader storage;
- save RAM and save states;
- editable touch-control positions and per-control sizing;
- optional D-pad visibility;
- Android USB/Bluetooth controller support;
- 4:3 and widescreen presentation;
- frame-time, presentation, audio, governor-confidence and jitter telemetry.

## Multi-system isolation

Nintendo 64 runs in the dedicated Android process:

`com.omnicore.emulator:n64`

PS1 and N64 keep separate runtime code, settings, storage and console-specific performance policies. N64 development must not silently alter the validated PS1 backend.

## PlayStation 1 — stable DEV 0.9.4

The PS1 backend has real-device validated gameplay, video, audio and controls using the pinned PCSX-ReARMed core.

Highlights include ARM64 + ARMv7 support, native C++/JNI runtime, EGL/OpenGL ES presentation, adaptive AAudio, multitouch controls, Android controller input, memory-card/save-state support, optional user-supplied BIOS and prepared-disc caching.

No PlayStation BIOS is bundled.

## Supported N64 content preparation

N64 recognition is signature-first rather than extension-only. OmniCore recognizes native `.z64`, `.v64` and `.n64` byte orders and can prepare supported ROMs from direct files, ZIP and GZIP containers. Prepared content is normalized into an N64-only cache without modifying the user's source file.

`7z` is not advertised until a validated extraction backend is bundled.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 16 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1 build

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

**[Direct APK — OmniCore-v0.9.4-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.4-dev/OmniCore-v0.9.4-debug.apk)**

### Experimental N64 build

**[OmniCore v0.10.15 N64 Alpha 16](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.15-n64-alpha16)**

**[Direct APK — OmniCore-v0.10.15-n64-alpha16-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.15-n64-alpha16/OmniCore-v0.10.15-n64-alpha16-debug.apk)**

Modern DEV/N64 test builds use the stable OmniCore DEV signing identity so compatible installations can update in place.

## Android / build baseline

- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- Android NDK: `28.2.13676358`
- Native runtime: C++20
- ABIs: `arm64-v8a`, `armeabi-v7a`
- Native ELF/APK **16 KB page-size compatibility** verified in CI
- Stable DEV signing certificate verified in release CI

## Reproducible cores

PCSX-ReARMed pin:

`da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

Mupen64Plus-Next pin:

`f275caf4b2bfa1e6d1c51636746ea793f3d80320`

Corresponding source archives are published beside applicable development APKs.

## Alpha 16 validation

GitHub Actions run **32168612788** validated the Alpha 16 release path, including Kotlin/native compilation, isolated PS1/N64 core builds, signed APK creation, stable DEV signer verification, native 16 KB alignment and prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 @astromg01.** The original OmniCore project identity, original documentation, branding and original project code are protected by copyright except where an applicable source/component license grants additional rights.

Public visibility of this repository does not by itself waive copyright ownership. See **[COPYRIGHT.md](COPYRIGHT.md)** for the project ownership notice.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next and related GPL-covered components retain the rights and obligations granted by those licenses; this OmniCore ownership notice does not replace or restrict them. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** and the source archives published with applicable builds.

## Immediate development priorities

1. Continue refining PrecisionGovernor v2 from real-device confidence, jitter, p95 and presentation telemetry.
2. Reduce remaining N64 stutter without sacrificing the protected 1.5× target or framebuffer compatibility.
3. Expand Game Intelligence only from confirmed compatibility data.
4. Continue regression testing of Smart Analog, save states, widescreen, touch editing and physical controllers.
5. Preserve PS1 isolation while the N64 runtime evolves.
6. Continue expanding the multi-system library and future console backends.

---

**OmniCore — original project authorship and rights: @astromg01. Repository maintained at `mauricio-gamedev/OmniCore`.**
