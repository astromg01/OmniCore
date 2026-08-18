# OmniCore

> Android-first multi-system emulation hub focused on isolated console runtimes, stable frame delivery, precise touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.23%20Alpha%2024-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Copyright](https://img.shields.io/badge/copyright-%C2%A9%202026%20%40mauricio--gamedev-6C63FF)](COPYRIGHT.md)

OmniCore is a **multi-system Android emulation hub**. Each console owns its runtime, settings, storage, input policy and performance logic while sharing one Android library experience.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls device validated |
| N64 device-test | **0.10.23 Alpha 24** | AudioBackend Auto, AudioHealthWatch, StartupAudioGate, SmoothAudioResampler, PrecisionGovernor v2.1 and RacingComfort v2 |

Nintendo 64 runs in the isolated Android process `com.omnicore.emulator:n64`. PS1 remains a separate validated backend and N64 CI rejects accidental PS1/PCSX-owned source changes.

## OmniCore 0.10.23 — N64 Alpha 24

Alpha 24 is the final broad N64 audio-compatibility expansion before the project opens the PlayStation 2 development track. It keeps the gameplay/performance foundation that tested well on physical hardware and attacks the remaining Android audio-driver variability without sacrificing protected rendering settings.

### AudioBackend Auto

OmniCore no longer assumes that a successfully opened AAudio stream is automatically the best output path for every Android device/driver.

The N64 runtime now uses a compatibility-first backend chain:

1. **AAudio Shared Low-Latency (`AA-SH`)** — preferred modern path;
2. **AAudio Exclusive (`AA-EX`)** — secondary modern path if Shared cannot open;
3. **OpenSL ES (`OpenSL`)** — native compatibility fallback.

The HUD exposes the active backend so physical-device testing can identify the actual Android audio path instead of treating all devices as equivalent.

### AudioHealthWatch

AAudio is judged by runtime health rather than API availability alone. AudioHealthWatch observes:

- hard audible underruns;
- AAudio xruns;
- asynchronous stream errors.

If a running AAudio session proves unhealthy, OmniCore can downgrade **once** to OpenSL ES, reprime the pipeline through StartupAudioGate and continue without repeatedly oscillating between backends.

Both backends share the same frontend continuity stack:

- **StartupAudioGate** — safe initial PCM reserve before output begins;
- **SmoothAudioResampler** — continuous linear interpolation across libretro audio batches;
- **SyncSlew** — gradual pacing correction rather than abrupt speed jumps;
- **ElasticAudioBridge** — bounded concealment of shallow source starvation;
- **TransitionAudioShield** — temporary reserve/headroom around transitions and short spikes.

Telemetry keeps `underruns` as hard failures and `rescues` as microgaps absorbed by the continuity layer.

### RacingComfort v2 — Mario Kart 64

The Mario Kart left-stick profile received a targeted correction. The previous RacingComfort path unintentionally multiplied final analog magnitude by `0.96`, preventing the default profile from reaching true full-scale steering.

RacingComfort v2 now provides:

- a calmer, continuous response around the center;
- progressive steering toward the outside of the stick;
- true full analog travel at the rim;
- suppression of tiny vertical touch noise only while horizontal steering clearly dominates;
- deliberate vertical/diagonal input preserved.

Zelda and normal analog titles retain the generic ComfortAnalog behavior, while Kirby keeps its Smart Analog digital-movement compatibility profile.

### Performance foundation preserved

Alpha 24 retains the performance path that has tested well on physical hardware:

- **PrecisionGovernor v2.1** with CPU/GPU/MIX pressure classification;
- **CruiseGuard** for long-session stability;
- **MicroBurstShield** for collision/hit/effect spikes;
- **NonBlockingTelemetry** so diagnostics do not block gameplay;
- **CadencePolish** fixed-source Android presentation hints;
- **DirectPresenter** with RenderBridge fallback;
- passive shader-cache warming limited to approximately **2 MiB**;
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
- RacingComfort v2 / Mario Kart 64 profile;
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
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 24 final-polish testing** |
| PlayStation 2 | Backend evaluation / Android-first integration | **Next active development track** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

### Experimental N64

**[OmniCore v0.10.23 N64 Alpha 24](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.23-n64-alpha24)**

**[Direct APK — OmniCore-v0.10.23-n64-alpha24-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.23-n64-alpha24/OmniCore-v0.10.23-n64-alpha24-debug.apk)**

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

## Alpha 24 validation

GitHub Actions run **32191808152** (registered bridge rerun) completed successfully and validated the full Alpha 24 path, including Kotlin/native compilation, OpenSL ES + AAudio N64 linking, isolated PCSX-ReARMed and Mupen64Plus-Next builds, signed **0.10.23** APK creation, stable DEV signer verification, native **16 KB alignment** and prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets. Users are responsible for supplying content and firmware they are legally entitled to use.

## Ownership, copyright and third-party licenses

**Copyright © 2026 [@mauricio-gamedev](https://github.com/mauricio-gamedev).** Original OmniCore project identity, documentation, branding and original project material are protected by copyright except where an applicable source/component license grants additional rights.

Public repository visibility does not place original OmniCore material in the public domain. See **[COPYRIGHT.md](COPYRIGHT.md)**.

Third-party components remain governed by their own licenses. PCSX-ReARMed, Mupen64Plus-Next and related GPL-covered components retain the rights and obligations granted by those licenses. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** and source archives published with applicable builds.

## Immediate development priorities

1. Validate `AA-SH` / `AA-EX` / `OpenSL` behavior on physical devices and compare hard-underrun growth during Zelda/Mario Kart menus.
2. Validate RacingComfort v2 center control and full steering range in Mario Kart 64.
3. Keep N64 changes limited to micro-corrections unless physical-device evidence requires otherwise.
4. Begin the **PlayStation 2** architecture/backend track without weakening PS1/N64 isolation.

---

**OmniCore — original project authorship and rights: [@mauricio-gamedev](https://github.com/mauricio-gamedev). Repository maintained at `mauricio-gamedev/OmniCore`.**
