# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.19%20Alpha%2020-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.19-n64-alpha20)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 device-test | **0.10.19 Alpha 20** | TransitionAudioShield, PrecisionGovernor v2.1, RacingComfort, CadencePolish, StarUI Smooth and Achievements v2 |

Nintendo 64 runs in the isolated Android process `com.omnicore.emulator:n64`. PS1 remains a separate validated backend and Alpha 20 CI rejects accidental PS1/PCSX-owned source changes.

## OmniCore 0.10.19 — N64 Alpha 20

Alpha 20 is a focused real-device polish release. Gameplay performance from Alpha 19 is preserved while the new work targets **audio breakup during game/menu transitions** and **more comfortable steering in Mario Kart 64**.

### TransitionAudioShield

Real-device telemetry showed that Zelda and Mario Kart could maintain strong gameplay while still accumulating many short AAudio underruns around startup screens, menus and transitions.

Alpha 20 treats those underruns as short **episodes** instead of solving them with one permanently large audio buffer:

- each newly observed audio-underrun episode is detected immediately after the next emulation slice;
- startup gets a modest bounded PCM reserve;
- menu, action and sudden micro-spike paths can temporarily arm the audio shield;
- while armed, the audio ring refills faster for a short period;
- AAudio burst adaptation is temporarily allowed to rise during the protected window;
- after the transition settles, normal low-latency behavior returns automatically;
- the system does **not** permanently inflate latency and does not change game speed.

This is designed to reduce audible menu/startup cuts without sacrificing the responsive gameplay that is already working well.

### RacingComfort — Game Intelligence

Alpha 20 expands the N64 Game Intelligence layer with a game-specific analog profile for **Mario Kart 64**.

The racing profile provides:

- a wider fine-steering zone around the center;
- slightly softer response for small left/right corrections;
- near-neutral effective default sensitivity;
- full analog travel still available at the outer edge;
- no change to explicit Smart Analog mode selection.

The generic **ComfortAnalog** curve from Alpha 19 remains unchanged for Zelda and other normal analog titles. The existing Kirby digital-movement profile also remains intact.

### PrecisionGovernor v2.1 + MicroBurstShield

The successful performance foundation remains active:

- fast + slow frame-time EWMAs;
- pressure-debt integration;
- confidence-based CPU / GPU / MIX classification;
- minimum dwell time and gradual recovery;
- CruiseGuard for long-session thermal/resource stability;
- bounded ADPF workload hints;
- MicroBurstShield for short collision/hit/effect spikes;
- NonBlockingTelemetry so diagnostic sampling never waits on the emulation thread;
- native jitter, p95, presentation and audio telemetry;
- passive shader-cache warming limited to approximately **2 MiB**;
- no hidden SmartPrecompile frames and no forced `glFinish()` boot pass.

### CadencePolish + presentation

The Android gameplay surface follows the cadence reported by the emulator as a fixed-rate source without frame generation or speed hacks. Seamless refresh-rate changes are requested where Android supports them.

DirectPresenter remains preferred with automatic RenderBridge fallback.

### StarUI Smooth

The cosmic StarUI remains display synchronized with draw-only animation. Decorative motion is reduced/static on low-RAM devices or while Battery Saver is active, and StarUI is never rendered over gameplay.

### Achievements v2 — Constelação OmniCore

The achievement system remains **offline and account-free**, including:

- Geral, Nintendo 64, Desempenho, Controles and Memória categories;
- Comum, Rara, Épica and Lendária rarities;
- points and progress;
- N64 sessions, unique games and cumulative playtime;
- stable-window and clean-audio milestones;
- DirectPresenter and performance-panel milestones;
- touch-control customization;
- save/load-state milestones;
- queued unlock banners;
- one low-priority worker in the N64 process;
- cross-process file locking and atomic state replacement.

## Protected N64 baseline

Alpha 20 preserves the real-device decisions that are already working:

- Intelligent **1.5×** internal resolution: `960×720` at 4:3 / `960×540` widescreen;
- framebuffer emulation protected;
- Dynarec-first CPU path;
- HLE RSP default;
- renderer threading not inferred only from CPU core count;
- DirectPresenter + RenderBridge fallback;
- adaptive native AAudio;
- persistent shader storage + passive 2 MiB WarmCache;
- Smart Analog / Kirby compatibility profile;
- RacingComfort / Mario Kart 64 profile;
- editable touch-control positions and sizing;
- optional D-pad visibility;
- USB/Bluetooth controller input;
- save RAM and save states.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 20 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

### Experimental N64

**[OmniCore v0.10.19 N64 Alpha 20](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.19-n64-alpha20)**

**[Direct APK — OmniCore-v0.10.19-n64-alpha20-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.19-n64-alpha20/OmniCore-v0.10.19-n64-alpha20-debug.apk)**

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

PCSX-ReARMed pin: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

Mupen64Plus-Next pin: `f275caf4b2bfa1e6d1c51636746ea793f3d80320`

Corresponding source archives are published beside applicable development APKs.

## Alpha 20 validation

GitHub Actions run **32184342295** completed successfully and validated:

- Alpha 20 architecture and PS1/N64 isolation guards;
- Kotlin + native N64 compilation;
- isolated PCSX-ReARMed and Mupen64Plus-Next builds;
- signed **0.10.19** APK creation;
- stable DEV signer verification;
- native ELF/APK **16 KB alignment**;
- N64 Alpha 20 prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next and related GPL-covered components retain the rights and obligations granted by those licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** and source archives published with applicable builds.

## Immediate development priorities

1. Compare Zelda and Mario Kart menu/startup underrun growth against Alpha 19.
2. Verify RacingComfort during fine steering, corners and full-lock turns in Mario Kart 64.
3. Preserve current gameplay performance while removing remaining transition micro-hitches.
4. Continue measurement-driven micro-refinements without sacrificing 1.5× or framebuffer compatibility.
5. Expand Game Intelligence only from confirmed compatibility data.
6. Preserve PS1 isolation while future backends are added.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
