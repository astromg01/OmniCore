# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.23%20Alpha%2024-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24)
[![PS2 Alpha](https://img.shields.io/badge/PS2%20alpha-0.11.0%20Alpha%201-FF6A5F)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.0-ps2-alpha1)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

<!-- OMNICORE_PUBLIC_STATUS_START -->
## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 maintenance | **0.10.23 Alpha 24** | Gameplay baseline protected; startup-only audio polish remains non-blocking |
| PS2 device-test | **0.11.0 Alpha 1** | First isolated Play! backend build with VM + Surface + content-URI boot path validated in CI |

### Latest milestone — PS2 Alpha 1

**OmniCore 0.11.0 PS2 Alpha 1** is the first public device-test build for the new PlayStation 2 runtime.

The PS2 track currently provides:

- isolated Android process: `com.omnicore.emulator:ps2`;
- pinned Play! backend revision `04bde0df87ee7c0e2f0151b51bb2cc22c88541da`;
- dedicated `PS2Backend` adapter so the OmniCore frontend is not tied directly to one backend implementation;
- real Play! VM creation and lifecycle bridge;
- Android `Surface` handoff to the PS2 renderer;
- content-URI disk boot path;
- pause/resume lifecycle integration;
- ISO9660 PS2 routing using `SYSTEM.CNF` + `BOOT2` instead of extension-only guessing;
- ARM64 + ARMv7 packaging;
- stable OmniCore DEV signer;
- native/APK 16 KB page-size compatibility validation.

The PS2 runtime is still **experimental**. Touch controls, renderer selection, game profiles, save-state UX and deeper SmartPerf tuning are the next active layers.

### SmartPerf policy for PS2

Optimization is part of the PS2 architecture, not a late-stage add-on. The PS2 track is designed around measurable and reversible decisions using EE/VU/GS/audio/frame-delivery telemetry, thermal awareness and game/device profiles.

The current quality policy keeps a **1.0× minimum quality floor** and does not silently enable dynamic resolution or cycle skipping simply to inflate performance numbers.

### Downloads

**PS2 Alpha 1 release**
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.11.0-ps2-alpha1

**Direct PS2 Alpha 1 APK**
https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.11.0-ps2-alpha1/OmniCore-v0.11.0-ps2-alpha1-debug.apk

**N64 Alpha 24 release**
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24

**Stable PS1 DEV**
https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev

### Latest validation

GitHub Actions run **32209664248** completed successfully for PS2 Alpha 1 and validated the protected PS1/N64 rebuilds, pinned Play! dual-ABI build, required PS2 JNI boot symbols, signed OmniCore 0.11.0 APK, stable DEV certificate, 16 KB compatibility and prerelease publication.
<!-- OMNICORE_PUBLIC_STATUS_END -->

## Runtime isolation

- **PS1** remains on its validated PCSX-ReARMed path.
- **N64** runs in `com.omnicore.emulator:n64` and keeps the protected Alpha 24 gameplay baseline.
- **PS2** runs in `com.omnicore.emulator:ps2`, with its own backend, lifecycle and performance path.

PS2 CI rejects accidental changes to protected PS1/N64 runtime-owned source paths.

## Protected N64 baseline

The N64 maintenance channel preserves:

- Intelligent **1.5×** internal resolution: `960×720` at 4:3 / `960×540` widescreen;
- framebuffer emulation protected;
- Dynarec-first CPU path;
- HLE RSP default;
- PrecisionGovernor v2.1;
- CruiseGuard / MicroBurstShield / NonBlockingTelemetry;
- CadencePolish + DirectPresenter;
- AudioBackend Auto + AudioHealthWatch;
- StartupAudioGate + SmoothAudioResampler + SyncSlew + ElasticAudioBridge + TransitionAudioShield;
- Smart Analog / Kirby profile;
- RacingComfort v2 / Mario Kart 64 profile;
- editable touch-control positions and sizing;
- optional D-pad visibility;
- USB/Bluetooth controller input;
- save RAM and save states;
- StarUI Smooth;
- offline Achievements v2 / Constelação OmniCore.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Alpha 24 / maintenance** |
| PlayStation 2 | Play! behind OmniCore `PS2Backend` | **Alpha 1 / active device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Android / build baseline

- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- OmniCore Android NDK: `28.2.13676358`
- Play! Android build NDK: `29.0.14206865`
- Native runtime: C++20
- ABIs: `arm64-v8a`, `armeabi-v7a`
- native ELF/APK **16 KB page-size compatibility** verified in CI
- stable DEV signing certificate verified in release CI

## Reproducible cores

PCSX-ReARMed pin: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

Mupen64Plus-Next pin: `f275caf4b2bfa1e6d1c51636746ea793f3d80320`

Play! PS2 pin: `04bde0df87ee7c0e2f0151b51bb2cc22c88541da`

Corresponding source/license material is kept with applicable builds and repository notices.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next, Play! and their dependencies retain the rights and obligations granted by their respective licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**.

## Immediate development priorities

1. Physical-device boot testing of PS2 Alpha 1 with legally supplied PS2 images.
2. Validate first-frame rendering, audio startup and lifecycle stability on Android hardware.
3. Add PS2 touch/controller input without coupling it to the backend implementation.
4. Expand PS2 SmartPerf using real EE/VU/GS/audio telemetry and device/game profiles.
5. Keep N64 in maintenance mode unless physical-device evidence requires a targeted correction.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
