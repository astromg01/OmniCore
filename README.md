# OmniCore

> Android-first multi-system emulation frontend focused on clean architecture, stable frame pacing and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Current DEV](https://img.shields.io/badge/current%20DEV-0.9.0-7C5CFF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.0-dev)
[![PS1](https://img.shields.io/badge/PS1-functional-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![Runtime](https://img.shields.io/badge/runtime-v7%20EGL%2FGLES-9879FF)](app/src/main/cpp/)
[![License note](https://img.shields.io/badge/core%20license-GPL--2.0-important)](THIRD_PARTY_NOTICES.md)

OmniCore is a unified Android emulation hub built around **independent native backends** with one shared library, input layer, save system, performance manager, update system and user interface.

The project is intentionally progressive: **PlayStation 1 is the first functional backend**. N64, PSP, Wii / GameCube, PS2 and Switch remain roadmap targets and are not presented as working emulators yet.

## Current status — v0.9.0 DEV

The PS1 backend has crossed the first major runtime milestone: **real gameplay, video and audio have been validated on Android hardware** using the OmniCore frontend and the pinned PCSX-ReARMed core.

The v0.9.0 line preserves the validated v0.7/v0.8 runtime foundation and focuses on input compatibility and frontend polish.

### PlayStation 1

- **PCSX-ReARMed/libretro** pinned to a reproducible upstream revision.
- Android builds for **arm64-v8a** and **armeabi-v7a**.
- Native **C++20/JNI libretro host**.
- **Runtime v7** with emulation, audio and presentation decoupled.
- **EGL + OpenGL ES 2** texture presenter.
- Stable **XRGB8888** core-output path with explicit GLES conversion.
- `SurfaceView` no-draw composition path validated after real-device black-screen debugging.
- AAudio output with priming, sample-rate adaptation and bounded adaptive buffering.
- Real gameplay validated with stable video and non-stuttering audio on Android hardware.
- Touch controls with D-pad, left analog stick, face buttons, shoulders, Start and Select.
- **Intelligent left-stick mode**: native DualShock axes plus D-pad projection for early PS1 games that only understand digital movement.
- Selectable left-stick modes: Intelligent, Native and D-pad.
- Configurable touch size, opacity and optional haptics.
- Android USB/Bluetooth controller axes through the native Android joystick path.
- Save RAM / memory-card persistence and save states.
- Optional user-supplied PS1 BIOS import; no BIOS is bundled.
- Optional **classic PlayStation BIOS boot/logo** when a valid real BIOS is available.
- CUE/BIN folder workflows plus supported single-file images such as CHD and PBP.
- Persistent validated **CUE/BIN disc cache** so unchanged games do not need to copy large BIN tracks on every launch.
- Library search, recent/A–Z/size sorting and confirmation before removing entries.
- Presentation modes: **4:3 original, 16:9 expansion and fullscreen**.
- Runtime diagnostics for produced, presented and dropped frames plus audio-buffer state.

> **16:9 note:** the current widescreen option changes frontend presentation. It is not presented as a universal per-game 3D geometry patch.

## Fast PS1 startup

CUE/BIN compatibility originally required materializing CD tracks as real local files because PCSX-ReARMed reopens tracks through standard file I/O. That path is kept for compatibility, but v0.8.0 no longer repeats the expensive copy on every launch.

OmniCore now:

1. Reads the CUE and resolves every referenced track.
2. Builds a fingerprint from the source metadata and CUE contents.
3. Materializes and validates the local disc set once.
4. Reuses the prepared disc cache while the source remains unchanged.
5. Rebuilds automatically if the source fingerprint changes.

The cache can be cleared manually from **Tuning → Início rápido PS1**.

## SmartPerf

OmniCore treats performance management as part of the runtime architecture rather than a collection of fixed “boost” switches.

Current performance systems include:

- Conservative hardware profiling.
- Per-session performance policy.
- Android thermal-status adaptation.
- Android Performance Hint / ADPF integration where available.
- Emulation frame-time measurement.
- Frame pacing based on the core refresh rate.
- Surface refresh-rate hints when supported.
- Adaptive AAudio buffering using platform xruns and frontend underrun telemetry.
- Background content preparation.
- Direct access to seekable Android document-provider files where safe.
- Compatibility-first defaults on lower-end devices.
- No root requirement, hidden APIs, forced CPU/GPU clocks or persistent vendor tweaks.

See [OPTIMIZATION.md](OPTIMIZATION.md) for implementation details.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / active development** |
| Nintendo 64 | Mupen64Plus family | Planned |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

The order is deliberate: each backend should integrate cleanly with the same library, input, save, performance and update systems instead of becoming a collection of unrelated emulator wrappers.

## In-app DEV updates

Starting with **v0.6.0**, OmniCore development builds include an in-app updater that:

1. Checks OmniCore GitHub Releases.
2. Compares semantic versions.
3. Downloads the matching DEV APK.
4. Verifies its SHA-256 digest when available.
5. Hands the verified APK to Android's `PackageInstaller`.

Development builds from v0.6.0 onward use a stable **DEV-only** signing certificate so compatible future DEV builds can update over the installed app while preserving app data.

> **Migration note:** builds through v0.5.0 were produced with ephemeral GitHub-runner debug certificates. Moving from v0.5.0 or older to v0.6.0 required one final uninstall/reinstall. Builds from v0.6.0 onward share the stable DEV signing identity. This identity is not intended for Play Store production signing.

## Download

The current development release is:

**[OmniCore v0.9.0 DEV — Input & Frontend Polish](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.0-dev)**

Direct APK:

**[OmniCore-v0.9.0-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.0-dev/OmniCore-v0.9.0-debug.apk)**

Development releases are for testing and may still contain game-specific compatibility regressions. Keep important save data backed up while the runtime remains under active development.

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

## Reproducible PS1 core build

The PCSX-ReARMed revision used by OmniCore is recorded in:

`third_party/PCSX_REARMED_PIN.txt`

The Android workflow fetches that exact revision, builds the ARM libraries and publishes the corresponding PCSX-ReARMed source bundle alongside the APK.

This keeps the binary/core source relationship explicit and reproducible.

## Project structure

```text
app/
  src/main/java/com/omnicore/emulator/
    core/          Core registry and backend integration
    emulation/     Emulation activity and touch/controller input
    performance/   SmartPerf runtime policy
    settings/      Core/user settings and presentation modes
    storage/       Library, BIOS, SAF and disc preparation
    ui/            Compose frontend
    update/        DEV update system

  src/main/cpp/
    libretro_host_v7.cpp
    gl_presenter.cpp
    native_bridge.cpp

third_party/
  PCSX_REARMED_PIN.txt
  licenses/

tools/
  fetch_ps1_core.sh
  build_ps1_core_android.sh
```

## CI validation

The `Android Build` workflow validates the full development path:

- project/runtime version consistency
- Android/JDK/NDK toolchain
- pinned PCSX-ReARMed checkout
- ARM64 and ARMv7 PS1-core builds
- OmniCore APK compilation
- stable DEV signing certificate
- native 16 KB alignment
- APK `zipalign` validation
- GitHub development Release publication
- corresponding PCSX-ReARMed source archive

## Licensing

PCSX-ReARMed is distributed under **GNU GPL v2** terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the bundled license text for details.

OmniCore's final distribution/licensing model must remain compatible with every backend included in a public build. Core licensing is treated as an architectural requirement, not an afterthought.

## Development priorities

1. Expand PS1 real-device and per-game compatibility testing without destabilizing the working v0.7 renderer/runtime foundation.
2. Refine startup latency, disc caching and BIOS behavior.
3. Improve touch-control ergonomics, remapping and per-game profiles.
4. Add proper presentation/scaling controls and evaluate safe game-specific widescreen mechanisms.
5. Improve library metadata, covers and diagnostics/exportable logs.
6. Harden save-state/memory-card behavior and recovery.
7. Begin the first non-PS1 backend only after the PS1 foundation remains stable across a broader test set.
8. Prepare production signing and store-distribution strategy after the runtime and licensing model are mature.

---

**OmniCore** is developed by [Mauricio.gamedev (@mauricio-gamedev)](https://github.com/mauricio-gamedev).
