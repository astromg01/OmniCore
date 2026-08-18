# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.20%20Alpha%2021-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.20-n64-alpha21)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 device-test | **0.10.20 Alpha 21** | ElasticAudioBridge, TransitionAudioShield, PrecisionGovernor v2.1, RacingComfort, CadencePolish, StarUI Smooth and Achievements v2 |

Nintendo 64 runs in the isolated Android process `com.omnicore.emulator:n64`. PS1 remains a separate validated backend and N64 CI rejects accidental PS1/PCSX-owned source changes.

## OmniCore 0.10.20 — N64 Alpha 21

Alpha 21 is a focused audio-continuity release based directly on physical-device testing of Zelda and Mario Kart 64. Alpha 20 reduced source-starvation events but audible cuts still remained during title screens, menus and transitions, so Alpha 21 changes the final real-time audio boundary instead of simply increasing latency.

### ElasticAudioBridge

The AAudio callback no longer converts every short producer gap into zeroes or a fade toward silence.

For **shallow starvation**, ElasticAudioBridge consumes a bounded PCM slice and expands it in-place with linear interpolation to cover the current callback while retaining a small reserve in the ring. For a **deeper but brief gap**, the callback uses a fixed recent-output tail as a continuity patch instead of abruptly cutting to silence.

The callback remains designed for real-time use:

- no dynamic allocation;
- no mutex acquisition;
- no file I/O;
- no blocking operation;
- fixed preallocated history storage only.

Long or persistent starvation is still treated as a real failure rather than being hidden indefinitely.

### `underruns` vs `rescues`

Alpha 21 changes the meaning of the audio telemetry so testing is more useful:

- **`underruns`** = hard starvation events that escaped the continuity layer and may be audible;
- **`rescues`** = short source-starvation events absorbed by ElasticAudioBridge.

A high rescue count is not automatically bad if audio remains clean. The main real-device target is **very low hard-underrun growth with inaudible or unobtrusive rescues**.

Raw ring starvation is still visible internally to the native AAudio adaptation, so TransitionAudioShield can react even when ElasticAudioBridge successfully conceals the event.

### TransitionAudioShield

The Alpha 20 transition reserve remains active around startup, menus, action bursts and detected short spikes. It temporarily rebuilds PCM headroom and can raise AAudio burst protection without permanently turning the emulator into a high-latency audio path.

### RacingComfort + ComfortAnalog

Mario Kart 64 keeps its Game Intelligence **RacingComfort** profile with a wider fine-steering center and softer small corrections while preserving full analog travel.

Zelda and normal analog titles keep the generic **ComfortAnalog** response from Alpha 19. Kirby keeps its digital-movement Smart Analog compatibility profile.

### Performance foundation preserved

Alpha 21 retains the performance path that has tested well on physical hardware:

- **PrecisionGovernor v2.1** with fast/slow frame EWMAs, pressure debt and confidence-based CPU/GPU/MIX classification;
- **CruiseGuard** for long-session stability;
- **MicroBurstShield** for collision/hit/effect spikes;
- **NonBlockingTelemetry** so diagnostic sampling does not wait on gameplay;
- **CadencePolish** fixed-source Android presentation hints;
- **DirectPresenter** with RenderBridge fallback;
- passive shader-cache warming limited to approximately **2 MiB**;
- native AAudio adaptation;
- no hidden SmartPrecompile frames;
- no forced `glFinish()` boot pass.

## Protected N64 baseline

The current N64 channel preserves:

- Intelligent **1.5×** internal resolution: `960×720` at 4:3 / `960×540` widescreen;
- framebuffer emulation protected;
- Dynarec-first CPU path;
- HLE RSP default;
- renderer threading not inferred only from CPU core count;
- Smart Analog / Kirby profile;
- RacingComfort / Mario Kart 64 profile;
- editable touch-control positions and sizing;
- optional D-pad visibility;
- USB/Bluetooth controller input;
- save RAM and save states;
- StarUI Smooth;
- offline **Achievements v2 / Constelação OmniCore**.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 21 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

### Experimental N64

**[OmniCore v0.10.20 N64 Alpha 21](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.20-n64-alpha21)**

**[Direct APK — OmniCore-v0.10.20-n64-alpha21-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.20-n64-alpha21/OmniCore-v0.10.20-n64-alpha21-debug.apk)**

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

## Alpha 21 validation

GitHub Actions run **32187240365** completed successfully and validated the full Alpha 21 path, including Kotlin/native compilation, isolated PCSX-ReARMed and Mupen64Plus-Next builds, signed **0.10.20** APK creation, stable DEV signer verification, native **16 KB alignment** and prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next and related GPL-covered components retain the rights and obligations granted by those licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** and source archives published with applicable builds.

## Immediate development priorities

1. Compare audible Zelda/Mario Kart menu transitions with Alpha 20.
2. Track **hard underruns and rescues separately** instead of comparing the new hard-underrun counter directly with old raw starvation totals.
3. If rescues are high but hard underruns stay low and audio is clean, refine concealment only as needed.
4. If hard underruns also remain high, investigate deeper producer/render-thread decoupling rather than simply increasing buffer size.
5. Preserve current gameplay performance, 1.5× resolution, framebuffer compatibility and PS1 isolation.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
