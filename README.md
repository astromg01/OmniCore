# OmniCore

> Android-first multi-system emulation frontend focused on clean console isolation, stable frame pacing and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.0-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.0-n64-alpha1)
[![PS1](https://img.shields.io/badge/PS1-device%20validated-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![N64](https://img.shields.io/badge/N64-device%20test%20alpha-F4C95D)](https://github.com/libretro/mupen64plus-libretro-nx)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)
[![Core licenses](https://img.shields.io/badge/core%20licenses-GPL--2.0-important)](THIRD_PARTY_NOTICES.md)

OmniCore is a native Android emulation hub built around **independent console backends**. A console owns its own core integration, runtime policy, settings, storage and emulation-specific input behavior instead of sharing console-specific state with another system.

The project is being developed progressively. **PlayStation 1 is the stable functional backend. Nintendo 64 is now the second integrated backend and is available as an experimental real-device test alpha.** PSP, Wii / GameCube, PlayStation 2 and Nintendo Switch remain roadmap targets.

## Release channels

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1-focused build validated on Android hardware |
| N64 device-test alpha | **0.10.0 Alpha 1** | N64 runtime integrated and CI-validated; real-device compatibility testing in progress |

The N64 alpha is intentionally not described as fully validated gameplay yet. CI verifies the complete Android build, native cores, signing and 16 KB alignment, while actual game compatibility, rendering, audio and controls are being verified on physical devices.

## Current stable — v0.9.4 DEV

The PS1 backend has crossed the major runtime milestone: **real gameplay, video, audio and controls have been validated on Android hardware** using the OmniCore frontend and the pinned PCSX-ReARMed core.

### PlayStation 1

- **PCSX-ReARMed/libretro** pinned to a reproducible upstream revision.
- Android builds for **arm64-v8a** and **armeabi-v7a**.
- Native **C++20/JNI libretro host**.
- Runtime v7 with emulation, audio and presentation decoupled.
- EGL + OpenGL ES 2 texture presenter.
- Stable XRGB8888 core-output path with explicit GLES conversion.
- `SurfaceView` no-draw composition path validated after real-device black-screen debugging.
- AAudio output with priming, sample-rate adaptation and bounded adaptive buffering.
- Real gameplay validated with stable video and non-stuttering audio on Android hardware.
- Touch controls with stable per-pointer multitouch ownership.
- D-pad, left analog stick, face buttons, shoulders, Start and Select.
- Intelligent left-stick mode: native DualShock axes plus D-pad projection for early PS1 games that only understand digital movement.
- Selectable left-stick modes: Intelligent, Native and D-pad.
- Configurable touch size, per-control sizing, opacity and optional haptics.
- Android USB/Bluetooth controller axes through the native Android joystick path.
- Save RAM / memory-card persistence and save states.
- Optional user-supplied PS1 BIOS import; no BIOS is bundled.
- Optional classic PlayStation BIOS boot/logo when a valid real BIOS is available.
- CUE/BIN folder workflows plus supported single-file images such as CHD and PBP.
- Persistent validated CUE/BIN disc cache so unchanged games do not need to copy large BIN tracks on every launch.
- Library search, recent/A–Z/size sorting and confirmation before removing entries.
- Presentation modes: **4:3 original, 16:9 expansion and fullscreen**.
- Runtime diagnostics for produced, presented and dropped frames plus audio-buffer state.

> **16:9 note:** the current widescreen option changes frontend presentation. It is not presented as a universal per-game 3D geometry patch.

## Nintendo 64 — v0.10.0 Alpha 1

Nintendo 64 is the first non-PS1 backend to reach an installable OmniCore alpha. It is intentionally isolated from the working PS1 runtime so N64-specific experiments cannot silently change PS1 settings or behavior.

### N64 foundation included in Alpha 1

- **Mupen64Plus-Next/libretro** pinned to revision `f275caf4b2bfa1e6d1c51636746ea793f3d80320`.
- Android builds for **arm64-v8a** and **armeabi-v7a**.
- Dedicated `omnicore_n64_runtime` native library and JNI bridge.
- Dedicated `N64EmulationActivity` and `SurfaceView` lifecycle.
- Real libretro hardware-render path through `RETRO_ENVIRONMENT_SET_HW_RENDER`.
- EGL + OpenGL ES 3 frontend context.
- GLideN64-first rendering path.
- Dedicated offscreen framebuffer and final GLES blit to the Android surface.
- Dynarec-first conservative CPU policy.
- N64-only settings, storage, ROM preparation, input preferences and SmartPerf decisions.
- Supported import extensions: **`.z64`, `.n64`, `.v64`**.
- ROM byte-order detection and normalization to a canonical local `.z64` cache without modifying the source file.
- N64-specific touch controller with analog stick, A/B, Z, L/R, Start, D-pad and C-buttons.
- Physical Android controller mapping into the N64 libretro input model.
- Dedicated adaptive AAudio path and audio-underrun telemetry.
- Allocation-free rolling frame telemetry for the native session.
- N64 SmartPerf policy using frame time, p95 latency, dropped frames, audio underruns and thermal pressure.
- Safe startup policy: CPU/RSP/threading choices are not aggressively hot-swapped in the middle of `retro_run`.
- Current Alpha 1 keeps **RSP HLE** and **Expansion Pak handling automatic** while real-device compatibility data is collected.

### N64 validation status

The Alpha 1 pipeline has already validated:

- Kotlin + C++ host compilation
- ARM64 native build
- ARMv7 native build
- PCSX-ReARMed and Mupen64Plus-Next coexistence in the same APK
- stable DEV signing identity
- native **16 KB ELF alignment**
- APK alignment verification
- public prerelease packaging

Physical-device N64 testing is the next gate. Until that testing is complete, the project does **not** claim universal N64 gameplay compatibility.

## Fast PS1 startup

CUE/BIN compatibility requires materializing CD tracks as real local files because PCSX-ReARMed can reopen tracks through standard file I/O. OmniCore keeps that compatibility path but avoids repeating the expensive copy on every launch.

OmniCore now:

1. Reads the CUE and resolves every referenced track.
2. Builds a fingerprint from source metadata and CUE contents.
3. Materializes and validates the local disc set once.
4. Reuses the prepared disc cache while the source remains unchanged.
5. Rebuilds automatically if the source fingerprint changes.

The cache can be cleared manually from **Tuning → Início rápido PS1**.

## SmartPerf

OmniCore treats performance management as runtime architecture rather than a collection of fixed “boost” switches.

Current performance systems include:

- Conservative hardware profiling.
- Per-console and per-session runtime policies.
- Android thermal-status adaptation.
- Android Performance Hint / ADPF integration where available.
- Emulation frame-time measurement.
- Frame pacing based on the active core refresh rate.
- Surface refresh-rate hints when supported.
- Adaptive AAudio buffering using platform xruns and frontend underrun telemetry.
- Background content preparation.
- Direct access to seekable Android document-provider files where safe.
- Compatibility-first defaults on lower-end devices.
- No root requirement, hidden APIs, forced CPU/GPU clocks or persistent vendor tweaks.

N64 extends this model with an isolated SmartPerf policy that can react to p95 frame time, dropped frames, audio underruns and thermal pressure without borrowing PS1-specific settings.

See [OPTIMIZATION.md](OPTIMIZATION.md) for implementation details.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 1 device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

The order remains deliberate: each console must integrate cleanly with OmniCore's library and Android shell while keeping its own emulation-specific runtime, settings, storage and compatibility policy.

## In-app DEV updates

OmniCore development builds include an in-app updater that can:

1. Check OmniCore GitHub Releases.
2. Compare compatible development versions.
3. Download the matching APK.
4. Verify its SHA-256 digest when available.
5. Hand the verified APK to Android's `PackageInstaller`.

Development builds use a stable **DEV-only** signing certificate so compatible builds can update over the installed app while preserving app data.

> Builds through v0.5.0 used ephemeral GitHub-runner debug certificates. Modern DEV builds use the stable OmniCore DEV identity. This identity is not intended for Play Store production signing.

## Downloads

### Stable PS1 build

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

Direct APK:

**[OmniCore-v0.9.4-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.4-dev/OmniCore-v0.9.4-debug.apk)**

### Experimental Nintendo 64 build

**[OmniCore v0.10.0 N64 Alpha 1](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.0-n64-alpha1)**

Direct APK:

**[OmniCore-v0.10.0-n64-alpha1-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.0-n64-alpha1/OmniCore-v0.10.0-n64-alpha1-debug.apk)**

Alpha releases are for testing and can contain game-specific regressions or incomplete persistence features. Keep important save data backed up while the runtime remains under active development.

## Content and firmware policy

OmniCore does **not** include:

- ROMs or game images
- BIOS files
- firmware
- console encryption keys
- proprietary game assets

Users are responsible for providing content and firmware they are legally entitled to use.

## Android / build baseline

- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- Android NDK: 28.2.13676358
- CMake: 3.31.5
- Native runtime: C++20
- ARM targets: `arm64-v8a`, `armeabi-v7a`
- Native ELF / APK alignment validated for **16 KB page-size compatibility** in CI
- Stable DEV signing identity verified during release builds

## Reproducible core builds

### PlayStation 1

PCSX-ReARMed is pinned to revision:

`da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

### Nintendo 64

Mupen64Plus-Next is pinned to revision:

`f275caf4b2bfa1e6d1c51636746ea793f3d80320`

The Android workflow fetches the exact pinned source revisions, builds the ARM libraries and publishes the corresponding source bundles alongside the N64 Alpha APK.

This keeps the distributed binary/core source relationship explicit and reproducible.

## Project structure

```text
app/
  src/main/java/com/omnicore/emulator/
    core/
      nativebridge/     Shared frontend-native entry points where appropriate
      n64/              Nintendo 64 core integration
      ps1/              PlayStation 1 core integration
    emulation/           Per-console Android emulation activities and controls
    performance/         Device profiling + console/session performance policy
    settings/            Per-console and frontend settings
    storage/             Library, BIOS, SAF and console-specific preparation
    ui/                  Compose frontend
    update/              DEV update system

  src/main/cpp/
    libretro_host_v7.cpp
    gl_presenter.cpp
    native_bridge.cpp
    n64/
      n64_libretro_host.cpp
      n64_libretro_host.h
      n64_libretro_abi.h
      n64_native_bridge.cpp

tools/
  fetch_ps1_core.sh
  build_ps1_core_android.sh
  fetch_n64_core.sh
  build_n64_core_android.sh
```

## CI validation

The N64 Alpha build path validates:

- project/runtime architecture consistency
- Android/JDK/NDK toolchain
- stable DEV signing setup
- Kotlin and native host compilation
- pinned PCSX-ReARMed checkout/build
- pinned Mupen64Plus-Next checkout/build
- ARM64 and ARMv7 outputs for both console cores
- PS1/N64 shared-APK coexistence
- corresponding source archives
- signed Android APK assembly
- stable DEV certificate fingerprint
- native 16 KB ELF alignment
- APK `zipalign` validation
- GitHub prerelease publication

No copyrighted ROMs, BIOS, firmware or game assets are used in CI.

## Licensing

PCSX-ReARMed and Mupen64Plus-Next are distributed under **GNU GPL v2** terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the corresponding source archives released beside the APK.

OmniCore's final distribution/licensing model must remain compatible with every backend included in a public build. Core licensing is treated as an architectural requirement, not an afterthought.

## Development priorities

1. Complete the first physical-device N64 boot/render/audio/input validation cycle.
2. Fix N64 game-specific compatibility issues without destabilizing PS1.
3. Add N64 save RAM persistence before treating the backend as daily-use ready.
4. Add N64 save states only after base persistence is stable.
5. Add stateful N64 SmartPerf hysteresis/cooldowns after real telemetry is collected.
6. Continue PS1 regression and compatibility testing against the stable 0.9.4 foundation.
7. Improve per-game input profiles, diagnostics and exportable logs.
8. Move the N64 backend from Alpha to DEV only after repeatable real-device validation.
9. Start the next console backend only after PS1 and N64 remain architecturally isolated and regression-safe.
10. Prepare production signing and store-distribution strategy after runtime and licensing requirements are mature.

---

**OmniCore** is developed by [Mauricio.gamedev (@mauricio-gamedev)](https://github.com/mauricio-gamedev).
