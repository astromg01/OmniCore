# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame pacing, clean touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.16%20Alpha%2017-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.16-n64-alpha17)
[![PS1](https://img.shields.io/badge/PS1-device%20validated-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![N64](https://img.shields.io/badge/N64-real--device%20testing-F4C95D)](https://github.com/libretro/mupen64plus-libretro-nx)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**, not a frontend built around one console. Each supported system owns its own core integration, runtime policy, settings, storage, performance logic and console-specific input behavior while sharing one Android library experience.

The current stable backend is **PlayStation 1**. **Nintendo 64** is the second integrated backend and remains in active physical-device Alpha development. PSP, Wii / GameCube, PlayStation 2 and Nintendo Switch remain roadmap targets.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls validated on Android hardware |
| N64 device-test | **0.10.16 Alpha 17** | PrecisionGovernor v2 preserved, StarUI, offline achievements and real-device micro-polish |

The N64 channel is experimental. CI validates architecture, compilation, packaging, signing, native core coexistence and 16 KB compatibility. Real gameplay quality and Android-driver behavior are validated on physical Android devices.

## OmniCore 0.10.16 — N64 Alpha 17

Alpha 17 is the first major **polish release** after PrecisionGovernor v2 reached a strong real-device baseline. The goal is to improve presentation and reduce small frontend costs **without disturbing the native N64 performance foundation that worked well in Alpha 16**.

### PrecisionGovernor v2 remains protected

The native N64 governor / pacing host was intentionally left unchanged in Alpha 17. CI hashes `n64_libretro_host.cpp` before and after the Alpha 17 migration and fails if the polish pass changes it accidentally.

The proven N64 foundation remains:

- fast + slow frame-time EWMAs;
- pressure-debt integration;
- confidence-based CPU / GPU / MIX classification;
- minimum state dwell time and gradual recovery;
- smoothed jitter telemetry;
- bounded ADPF hints;
- audio adaptation based on real AAudio/ring-buffer evidence;
- passive shader-cache warm-up limited to approximately **2 MiB**;
- no hidden SmartPrecompile frames and no forced `glFinish()` boot pass.

### Omni StarUI

The hub now uses a new dark cosmic visual language inspired by OmniCore's original animated N64 boot star.

StarUI includes:

- deep cosmic background with purple, cyan and star-gold accents;
- a star-based OmniCore header identity;
- a lightweight code-drawn star field behind the hub;
- animation capped at approximately **20 fps** instead of rendering decorative motion at game-frame rates;
- reduced/static motion and lower star density on Android low-RAM devices or while Battery Saver is enabled;
- the star field exists in the hub only and is **never rendered over gameplay**.

The visual upgrade is deliberately bounded so UI polish does not become a new emulator bottleneck.

### Local achievements — “Constelação OmniCore”

Alpha 17 introduces an **offline achievement system with no account requirement**.

A new **Conquistas** tab shows rarity, descriptions and progress. Unlocks can display a lightweight animated star banner while using the emulator.

The first achievement set includes:

| Achievement | Goal |
|---|---|
| **Primeira Estrela** | Open OmniCore |
| **Primeiro Cartucho** | Add the first game to the library |
| **Colecionador** | Reach 10 games in the unified library |
| **64 Bits Acordados** | Reach the first confirmed N64 frame |
| **Sessão Dourada** | Accumulate 10 active N64 minutes |
| **Afinador** | Open the N64 performance panel |
| **Do Meu Jeito** | Edit and save the N64 touch layout |
| **Guardião do Tempo** | Queue the first N64 save state |
| **Analógico Esperto** | Trigger Smart Analog → D-pad in real gameplay |
| **Fluxo de Seda** | Sustain a stable low-jitter PrecisionGovernor v2 window |

Achievement state is stored locally in a tiny file protected by a cross-process file lock. This matters because the N64 runtime runs in the isolated `:n64` Android process while the hub runs in the main process. Same-process access is also serialized, and state replacement uses an atomic-move path when available.

Achievement disk work is kept off the emulation and UI threads.

### N64 frontend micro-polish

The N64 status/telemetry poll is now relaxed after stable boot:

- preparing runtime: **220 ms** cadence;
- early confirmed boot: **350 ms** cadence;
- stable `RUN OK`: **750 ms** cadence.

This reduces unnecessary JNI/UI activity during normal gameplay while leaving the existing SmartPerf adaptation window intact.

### Visual and compatibility protections

Alpha 17 continues to protect the settings that are already working on real hardware:

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
- USB/Bluetooth controller support;
- 4:3 and widescreen presentation.

## Multi-system isolation

Nintendo 64 runs in the dedicated Android process:

`com.omnicore.emulator:n64`

PS1 and N64 keep separate runtime code, settings, storage and console-specific performance policies. Alpha 17's CI includes an isolation guard against accidental PS1/PCSX-owned source changes.

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
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 17 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1 build

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

**[Direct APK — OmniCore-v0.9.4-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.4-dev/OmniCore-v0.9.4-debug.apk)**

### Experimental N64 build

**[OmniCore v0.10.16 N64 Alpha 17](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.16-n64-alpha17)**

**[Direct APK — OmniCore-v0.10.16-n64-alpha17-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.16-n64-alpha17/OmniCore-v0.10.16-n64-alpha17-debug.apk)**

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

## Alpha 17 validation

GitHub Actions run **32173594771** validated the Alpha 17 release path, including:

- Alpha 17 architecture and isolation guards;
- Kotlin and native N64 runtime compilation;
- isolated PCSX-ReARMed and Mupen64Plus-Next builds;
- signed **0.10.16** APK creation;
- stable DEV signer verification;
- native ELF/APK **16 KB alignment**;
- N64 Alpha 17 prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public visibility of this repository does not by itself place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)** for the project ownership notice.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next and related GPL-covered components retain all rights and obligations granted by those licenses; the OmniCore ownership notice does not replace or restrict them. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** and the source archives published with applicable builds.

## Immediate development priorities

1. Verify StarUI smoothness and power behavior on physical Android hardware.
2. Validate achievement persistence, unlock banners and cross-process progress during real N64 sessions.
3. Confirm Alpha 17 preserves the real-device gameplay quality reached by PrecisionGovernor v2 in Alpha 16.
4. Continue only measurement-driven micro-refinements to N64 performance and frame delivery.
5. Expand Game Intelligence from confirmed compatibility data rather than broad title hacks.
6. Preserve PS1 isolation while adding future console backends and library features.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
