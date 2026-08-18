# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.18%20Alpha%2019-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.18-n64-alpha19)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 device-test | **0.10.18 Alpha 19** | PrecisionGovernor v2.1 micro-refinement, CadencePolish, StarUI Smooth and Achievements v2 |

Nintendo 64 runs in the isolated Android process `com.omnicore.emulator:n64`. PS1 remains a separate validated backend and Alpha 19 CI rejects accidental PS1/PCSX-owned source changes.

## OmniCore 0.10.18 — N64 Alpha 19

Alpha 19 focuses on **micro-hitches after longer sessions, short collision/impact stalls and analog comfort**. It refines the successful Alpha 18 foundation instead of replacing it.

### NonBlockingTelemetry

Diagnostic telemetry must never be more important than a gameplay frame.

Native frame/presentation sampling now uses a **try-lock**. If the hub is copying telemetry at exactly the same instant, OmniCore skips that diagnostic sample instead of blocking the emulation/presentation thread. Stable frontend polling is also relaxed to **1200 ms** after boot.

### MicroBurstShield

Short collision, hit and first-effect bursts can be visible without being large enough to justify a full PrecisionGovernor mode change.

Alpha 19 adds two bounded transient paths:

- common N64 action inputs **A / B / Z** can request a tiny predictive CPU+GPU workload hint immediately before the core consumes the action;
- sudden frame hitches above the local moving baseline are detected at a lower transient threshold than the old catastrophic-only path.

The existing ADPF notifier rate-limits these requests. MicroBurstShield does **not** change resolution, framebuffer settings, emulation speed or audio buffering.

### CruiseGuard — PrecisionGovernor v2.1

Long sessions can develop small thermal/resource oscillations even when the main FPS remains stable. CruiseGuard lets an already-controlled governor mode become slightly less aggressive after several seconds of measured stable pressure.

If frame pressure, pressure debt or jitter rises again, full headroom is restored immediately. This is a thermal/stability refinement, **not** a fidelity downgrade.

The PrecisionGovernor foundation remains:

- fast + slow frame-time EWMAs;
- pressure-debt integration;
- confidence-based CPU / GPU / MIX classification;
- minimum dwell time and gradual recovery;
- jitter tracking;
- bounded ADPF hints;
- AAudio adaptation based on real underrun/ring evidence;
- passive shader-cache warming limited to approximately **2 MiB**;
- no hidden SmartPrecompile and no forced `glFinish()` boot pass.

### ComfortAnalog

Precision Analog now keeps its radial deadzone but uses a more comfortable response curve:

- extra precision is concentrated only near the center;
- the middle of the stick is almost linear, reducing the “heavy” feeling;
- the outer ~1.5% saturates to full travel so maximum movement is easier to reach;
- user sensitivity still applies normally;
- Smart Analog and the Kirby digital-movement profile remain intact.

### CadencePolish + presentation

Alpha 18's fixed-source Android presentation hints remain. The gameplay surface follows the emulator's reported cadence without frame generation or speed hacks, using seamless refresh-rate changes where Android supports them.

DirectPresenter remains preferred with automatic RenderBridge fallback.

### StarUI Smooth

The cosmic StarUI remains display synchronized with draw-only animation. Decorative stars are not rendered over gameplay, and reduced/static motion is used on low-RAM devices or while Battery Saver is active.

### Achievements v2 — Constelação OmniCore

The local achievement system remains **offline and account-free**, with:

- Geral, Nintendo 64, Desempenho, Controles and Memória categories;
- Comum, Rara, Épica and Lendária rarities;
- points and progress;
- N64 sessions, unique games and cumulative playtime;
- stable-window and clean-audio milestones;
- DirectPresenter, performance-panel and control milestones;
- save/load-state milestones;
- queued unlock banners;
- one low-priority achievement worker in the N64 process;
- cross-process file locking and atomic state replacement.

## Protected N64 baseline

Alpha 19 preserves the real-device decisions that are already working:

- Intelligent **1.5×** internal resolution: `960×720` at 4:3 / `960×540` widescreen;
- framebuffer emulation protected;
- Dynarec-first CPU path;
- HLE RSP default;
- renderer threading not inferred only from CPU core count;
- DirectPresenter + RenderBridge fallback;
- adaptive native AAudio;
- persistent shader storage + passive 2 MiB WarmCache;
- Smart Analog / Kirby compatibility profile;
- editable touch-control positions and sizing;
- optional D-pad visibility;
- USB/Bluetooth controller input;
- save RAM and save states.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 19 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

### Experimental N64

**[OmniCore v0.10.18 N64 Alpha 19](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.18-n64-alpha19)**

**[Direct APK — OmniCore-v0.10.18-n64-alpha19-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.18-n64-alpha19/OmniCore-v0.10.18-n64-alpha19-debug.apk)**

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

## Alpha 19 validation

GitHub Actions run **32180241510** completed successfully and validated:

- Alpha 19 architecture and PS1/N64 isolation guards;
- Kotlin + native N64 compilation;
- isolated PCSX-ReARMed and Mupen64Plus-Next builds;
- signed **0.10.18** APK creation;
- stable DEV signer verification;
- native ELF/APK **16 KB alignment**;
- N64 Alpha 19 prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next and related GPL-covered components retain the rights and obligations granted by those licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** and source archives published with applicable builds.

## Immediate development priorities

1. Validate long-session micro-hitches after 10–20 minutes of N64 gameplay.
2. Compare collision/hit microstalls with Alpha 18.
3. Fine-tune ComfortAnalog from real-device walking, aiming and full-speed movement.
4. Continue measurement-driven micro-refinements without sacrificing 1.5× or framebuffer compatibility.
5. Expand Game Intelligence only from confirmed compatibility data.
6. Preserve PS1 isolation while future backends are added.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
