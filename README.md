# OmniCore

> Android-first multi-system emulation frontend focused on clean architecture, stable frame pacing and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Current DEV](https://img.shields.io/badge/current%20DEV-0.6.0-7C5CFF)](https://github.com/astromg01/OmniCore/releases/tag/v0.6.0-dev)
[![PS1](https://img.shields.io/badge/PS1-PCSX--ReARMed-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![License note](https://img.shields.io/badge/core%20license-GPL--2.0-important)](THIRD_PARTY_NOTICES.md)

OmniCore is a unified Android emulation hub being built around **independent native backends** with one shared library, input layer, save system, performance manager and user interface.

The project is intentionally progressive: **PlayStation 1 is the first functional backend**. N64, PSP, Wii, PS2 and Switch remain roadmap targets and are not presented as working emulators yet.

## Current status — v0.6.0 DEV

The current development build focuses on making the PS1 path technically solid before the next console backend is added.

### PlayStation 1

- **PCSX-ReARMed/libretro** pinned to a reproducible upstream revision.
- Android builds for **arm64-v8a** and **armeabi-v7a**.
- Native **C++20/JNI libretro host**.
- Dedicated emulation, audio and rendering paths.
- **Runtime v7** with a separate video presentation thread.
- **EGL + OpenGL ES 2** texture presenter instead of direct CPU blitting to `ANativeWindow`.
- Direct RGB565 texture upload path plus explicit conversion for XRGB8888 / 0RGB1555.
- AAudio output with priming, sample-rate adaptation and bounded adaptive buffering.
- Touch controls, D-pad, analog input and Android/Bluetooth controller support.
- Save RAM / memory-card persistence and save states.
- Optional user-supplied PS1 BIOS import; no BIOS is bundled.
- CUE/BIN folder workflows plus supported single-file images such as CHD and PBP.
- Runtime diagnostics for produced, visible, dropped and presented frames.

## SmartPerf

OmniCore treats performance management as part of the runtime architecture rather than a collection of fixed "boost" switches.

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
- Direct access to seekable Android document-provider files where possible.
- Compatibility-first defaults on lower-end devices.
- No root requirement, hidden APIs, forced CPU/GPU clocks or persistent vendor tweaks.

See [OPTIMIZATION.md](OPTIMIZATION.md) for implementation details.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Active / functional** |
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
4. Verifies its SHA-256 digest when provided by GitHub.
5. Hands the verified APK to Android's `PackageInstaller`.

Development builds from v0.6.0 onward use a stable **DEV-only** signing certificate so compatible future DEV builds can update over the installed app while preserving app data.

> **Migration note:** builds through v0.5.0 were produced with ephemeral GitHub-runner debug certificates. Moving from v0.5.0 or older to v0.6.0 requires one final uninstall/reinstall. This DEV signing identity is not intended for Play Store production signing.

## Download

The current development release is:

**[OmniCore v0.6.0 DEV — EGL/GLES Video Foundation](https://github.com/astromg01/OmniCore/releases/tag/v0.6.0-dev)**

Development releases are for testing and may contain compatibility regressions. Keep important save data backed up while the runtime is still under active development.

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
    emulation/     Emulation activity and controls
    performance/   SmartPerf runtime policy
    settings/      Core/user settings
    storage/       Library, BIOS and Android SAF handling
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

The `Android Build` workflow currently validates the full development path:

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

1. Finish real-device validation of the PS1 EGL/GLES renderer.
2. Harden PS1 compatibility, save behavior and per-game settings.
3. Improve frontend diagnostics and update UX.
4. Integrate the first non-PS1 backend.
5. Expand the shared performance layer without device-specific hacks.
6. Prepare production signing and store-distribution strategy only after the runtime foundation is stable.

---

**OmniCore** is developed by [@astromg01](https://github.com/astromg01).
