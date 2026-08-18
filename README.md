# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.17%20Alpha%2018-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.17-n64-alpha18)
[![PS1](https://img.shields.io/badge/PS1-device%20validated-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![N64](https://img.shields.io/badge/N64-real--device%20testing-F4C95D)](https://github.com/libretro/mupen64plus-libretro-nx)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its core integration, runtime policy, settings, storage, input behavior and performance logic while sharing one Android library experience.

The current stable backend is **PlayStation 1**. **Nintendo 64** is the second integrated backend and remains in active physical-device Alpha development. PSP, Wii / GameCube, PlayStation 2 and Nintendo Switch remain roadmap targets.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls validated on Android hardware |
| N64 device-test | **0.10.17 Alpha 18** | PrecisionGovernor v2 preserved, CadencePolish, smooth StarUI and Achievements v2 |

The N64 channel is experimental. CI validates architecture, compilation, packaging, signing, native-core coexistence and 16 KB compatibility. Gameplay quality and Android-driver behavior are validated on physical Android devices.

## OmniCore 0.10.17 — N64 Alpha 18

Alpha 18 is a **micro-polish release**. It keeps the successful PrecisionGovernor v2 foundation intact and targets the remaining difference between “stable FPS” and **smooth perceived delivery**.

### CadencePolish

A game can be running consistently without drops and still feel less fluid if the display compositor does not present its cadence cleanly. Alpha 18 improves the Android presentation hint without generating artificial frames or changing emulation speed.

- Android `Surface` is now identified as a **fixed-rate source** for the N64 gameplay surface;
- runtime frame-rate hints now accept core targets from **20–75 Hz** instead of only 40–75 Hz;
- Android 12+ uses the **seamless-only** frame-rate-change strategy where supported;
- the hint follows the cadence reported by the emulator rather than inventing a higher FPS;
- no frame interpolation, speed hack, forced refresh rate or fidelity reduction is used.

This is intentionally a presentation-layer refinement. **PrecisionGovernor v2 itself is unchanged.**

### PrecisionGovernor v2 remains protected

The native N64 governor/pacing host is hash-protected in Alpha 18 CI. The polish migration fails if it changes `n64_libretro_host.cpp` unexpectedly.

The working foundation remains:

- fast + slow frame-time EWMAs;
- pressure-debt integration;
- confidence-based CPU / GPU / MIX classification;
- minimum state dwell time and gradual recovery;
- smoothed jitter telemetry;
- bounded ADPF hints;
- audio adaptation based on real AAudio/ring-buffer evidence;
- passive shader-cache warming limited to approximately **2 MiB**;
- no hidden SmartPrecompile frames and no forced `glFinish()` boot pass.

### StarUI Smooth

The cosmic StarUI introduced in Alpha 17 was visually successful but its background animation used a **50 ms / ~20 Hz composition tick**, which could make the hub itself look choppy.

Alpha 18 replaces that path with a display-synchronized animation whose phase is consumed inside the `Canvas` draw scope. The star layer redraws smoothly without forcing the whole OmniCore hub to recompose on every animation frame.

StarUI now uses:

- display-synchronized star movement and twinkle;
- **draw-only invalidation** for the animated background;
- lower normal star density than Alpha 17;
- static/reduced stars on Android low-RAM devices or while Battery Saver is active;
- no decorative StarUI rendering over gameplay.

### Achievements v2 — Constelação OmniCore

The local achievement system is substantially expanded while staying **offline, account-free and lightweight**.

Alpha 18 adds:

- **5 categories:** Geral, Nintendo 64, Desempenho, Controles and Memória;
- points for every achievement;
- **Comum, Rara, Épica and Lendária** rarity tiers;
- category filters and total-points progress in the Conquistas screen;
- N64 session-count milestones;
- unique-N64-game milestones;
- cumulative N64 playtime milestones;
- sustained stable-window progress;
- clean-audio session progress;
- DirectPresenter detection;
- repeated performance-panel milestones;
- touch-layout customization milestones;
- save-state and load-state milestones;
- control-hidden / clean-screen milestone;
- queued unlock notifications so simultaneous achievements are shown sequentially instead of replacing one another.

Achievement work in the N64 process is serialized through **one low-priority executor** rather than creating a new thread for every event. Related minute/session counters are updated together to reduce file operations.

State remains stored locally in a compact file with same-process serialization, a **cross-process file lock** for the main and `:n64` processes, and atomic replacement when available. Existing Alpha 17 progress remains readable.

### N64 frontend micro-polish

The status/telemetry frontend poll is relaxed once the session has reached stable `RUN OK`:

- preparing runtime: **220 ms**;
- early confirmed boot: **350 ms**;
- stable gameplay: **900 ms**.

This reduces non-gameplay JNI/UI work after startup while the native performance controller continues operating independently.

### Visual and compatibility protections

Alpha 18 keeps the real-device baseline that already works:

- Intelligent **1.5×** internal resolution (`960×720` at 4:3 / `960×540` widescreen);
- framebuffer emulation is not disabled automatically;
- renderer threading is not inferred only from CPU-core count;
- Dynarec-first CPU path;
- HLE RSP default;
- DirectPresenter with automatic RenderBridge fallback;
- adaptive native AAudio;
- Game-aware Smart Analog, including the Kirby digital-movement profile;
- radial analog precision and directional hysteresis;
- save RAM and save states;
- editable touch-control positions and sizing;
- optional D-pad visibility;
- Android USB/Bluetooth controller support;
- 4:3 and widescreen presentation.

## Multi-system isolation

Nintendo 64 runs in the dedicated Android process:

`com.omnicore.emulator:n64`

PS1 and N64 keep separate runtime code, settings, storage and console-specific performance policies. Alpha 18 CI rejects accidental PS1/PCSX-owned source changes.

## PlayStation 1 — stable DEV 0.9.4

The PS1 backend has real-device validated gameplay, video, audio and controls using the pinned PCSX-ReARMed core. Highlights include ARM64 + ARMv7 support, native C++/JNI runtime, EGL/OpenGL ES presentation, adaptive AAudio, multitouch controls, Android controller input, memory-card/save-state support, optional user-supplied BIOS and prepared-disc caching.

No PlayStation BIOS is bundled.

## Supported N64 content preparation

N64 recognition is signature-first rather than extension-only. OmniCore recognizes native `.z64`, `.v64` and `.n64` byte orders and can prepare supported ROMs from direct files, ZIP and GZIP containers. Prepared content is normalized into an N64-only cache without modifying the user's source file.

`7z` is not advertised until a validated extraction backend is bundled.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 18 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1 build

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

**[Direct APK — OmniCore-v0.9.4-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.4-dev/OmniCore-v0.9.4-debug.apk)**

### Experimental N64 build

**[OmniCore v0.10.17 N64 Alpha 18](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.17-n64-alpha18)**

**[Direct APK — OmniCore-v0.10.17-n64-alpha18-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.17-n64-alpha18/OmniCore-v0.10.17-n64-alpha18-debug.apk)**

Modern DEV/N64 test builds use the stable OmniCore DEV signing identity so compatible installations can update in place.

## Android / build baseline

- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- Android NDK: `28.2.13676358`
- Native runtime: C++20
- ABIs: `arm64-v8a`, `armeabi-v7a`
- native ELF/APK **16 KB page-size compatibility** verified in CI
- stable DEV signing certificate verified in release CI

## Reproducible cores

PCSX-ReARMed pin:

`da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

Mupen64Plus-Next pin:

`f275caf4b2bfa1e6d1c51636746ea793f3d80320`

Corresponding source archives are published beside applicable development APKs.

## Alpha 18 validation

GitHub Actions run **32176380056** validated the complete Alpha 18 R2 release path:

- architecture and PS1/N64 isolation guards;
- Kotlin + native N64 runtime compilation;
- isolated PCSX-ReARMed and Mupen64Plus-Next builds;
- signed **0.10.17** APK creation;
- stable DEV signer verification;
- native ELF/APK **16 KB alignment**;
- N64 Alpha 18 prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public visibility of this repository does not by itself place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)** for the project ownership notice.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next and related GPL-covered components retain all rights and obligations granted by those licenses; the OmniCore ownership notice does not replace or restrict them. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** and the source archives published with applicable builds.

## Immediate development priorities

1. Compare Zelda perceived smoothness/camera delivery between Alpha 17 and Alpha 18 while keeping gameplay speed unchanged.
2. Verify StarUI scroll and star animation smoothness on physical Android hardware.
3. Validate Achievements v2 counters, category filters, queued unlocks and persistence after app restarts.
4. Continue only measurement-driven micro-refinements to frame delivery and input latency.
5. Expand Game Intelligence from confirmed compatibility data rather than broad title hacks.
6. Preserve PS1 isolation while adding future console backends and library features.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
