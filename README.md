# OmniCore

> Android-first multi-system emulation hub focused on console isolation, stable frame pacing and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.1%20Alpha%202-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.1-n64-alpha2)
[![PS1](https://img.shields.io/badge/PS1-device%20validated-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![N64](https://img.shields.io/badge/N64-real--device%20testing-F4C95D)](https://github.com/libretro/mupen64plus-libretro-nx)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)

OmniCore is not a wrapper around one emulator. It is a **multi-system Android shell** where every console owns its own core integration, runtime policy, storage, settings and emulation-specific input behavior.

The current stable backend is **PlayStation 1**. **Nintendo 64** is the second integrated backend and is now in a real-device Alpha cycle. PSP, Wii / GameCube, PlayStation 2 and Nintendo Switch remain roadmap targets.

## Release channels

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 validated on Android hardware |
| N64 device-test | **0.10.1 Alpha 2** | Multi-system UI + N64 crash isolation + expanded ROM detection |

The N64 build is still experimental. CI validates compilation, packaging, signing and native alignment; actual N64 gameplay/video/audio/input must still be confirmed on physical devices.

## OmniCore 0.10.1 — N64 Alpha 2

Alpha 2 was created directly from the first physical-device Alpha 1 test cycle.

### Multi-system frontend

The home screen is now **OmniCore-first rather than PS1-first**:

- Unified **Biblioteca**.
- Dedicated **Sistemas** area.
- Dedicated **Ajustes** area.
- Console filters are library views, not forced file classifications.
- PS1 and N64 keep their own configuration surfaces.
- Planned systems can exist in the architecture without pretending their cores are functional.

### Automatic folder scanning

A selected folder may contain more than one supported system.

The current scanner can:

- keep PS1 `CUE/BIN` sets grouped correctly;
- continue scanning after finding a PS1 CUE instead of stopping early;
- identify Nintendo 64 ROMs in the same selected folder;
- avoid treating CUE-referenced PS1 BIN tracks as separate games;
- run import I/O off the Compose/UI thread;
- keep ambiguous files out of the wrong backend instead of blindly trusting the active UI filter.

The current folder scan covers files directly inside the selected folder. Recursive nested-folder discovery is a future library feature.

### Nintendo 64 ROM detection

N64 recognition is now **signature-first**, not extension-only.

Recognized native byte orders:

- big-endian `.z64` — header `80 37 12 40`;
- byte-swapped `.v64` — header `37 80 40 12`;
- little-endian `.n64` — header `40 12 37 80`.

The Alpha 2 import/preparation path supports:

- `.z64`
- `.n64`
- `.v64`
- valid `.rom` / `.bin` dumps when their actual N64 header matches
- ZIP containing a recognized N64 ROM
- GZIP containing a recognized N64 ROM

ZIP/GZIP payloads are materialized into the N64-only cache and normalized to canonical big-endian `.z64` without modifying the source file.

`7z` is **not** advertised yet because OmniCore does not currently bundle a validated 7z extraction backend.

### N64 crash isolation

Nintendo 64 now runs in a dedicated Android process:

`com.omnicore.emulator:n64`

This is an important architectural boundary. If Mupen64Plus-Next, GLideN64 or a device graphics driver crashes natively during an experimental N64 boot, the failure should be contained to the N64 process rather than terminating the OmniCore library / PS1 process with it.

This does not by itself prove that N64 gameplay is fixed; it makes the next real-device diagnosis safer and more observable.

### N64 runtime foundation

- **Mupen64Plus-Next/libretro** pinned to `f275caf4b2bfa1e6d1c51636746ea793f3d80320`.
- Android `arm64-v8a` and `armeabi-v7a` builds.
- Dedicated `omnicore_n64_runtime` JNI/native host.
- Dedicated `N64EmulationActivity` and Surface lifecycle.
- Real libretro hardware-render negotiation through `RETRO_ENVIRONMENT_SET_HW_RENDER`.
- EGL + OpenGL ES 3.
- GLideN64-first rendering path.
- Dynarec-first conservative CPU policy.
- N64-only SmartPerf decisions.
- N64-specific touch controls: analog, A/B, Z, L/R, Start, D-pad and C-buttons.
- Android physical-controller input mapping.
- Dedicated adaptive AAudio path.
- Frame-time, p95, dropped-frame and audio-underrun telemetry.
- RSP HLE and conservative automatic Expansion Pak behavior while compatibility data is being collected.

## PlayStation 1 — stable DEV 0.9.4

The PS1 backend has real-device validated gameplay, video, audio and controls using the pinned PCSX-ReARMed core.

Highlights:

- PCSX-ReARMed/libretro pinned reproducibly.
- ARM64 + ARMv7.
- Native C++/JNI runtime.
- EGL/OpenGL ES presentation.
- Adaptive AAudio.
- Stable per-pointer multitouch controls.
- Android USB/Bluetooth controller input.
- Save RAM / memory cards and save states.
- Optional user-supplied PS1 BIOS.
- CUE/BIN folder workflow plus supported single-file images such as CHD/PBP.
- Persistent prepared-disc cache.
- 4:3, 16:9 presentation and fullscreen modes.

No PlayStation BIOS is bundled.

## SmartPerf

Performance management is part of OmniCore's runtime architecture rather than a single global “boost” switch.

Current principles include:

- per-console performance policy;
- conservative device profiling;
- frame pacing based on core timing;
- thermal-pressure awareness;
- bounded adaptive audio buffering;
- background content preparation;
- compatibility-first defaults on lower-end devices;
- no root requirement, hidden APIs, forced clocks or persistent vendor tweaks.

N64 and PS1 do **not** share console-specific tuning state.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 2 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1 build

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

**[Direct APK — OmniCore-v0.9.4-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.4-dev/OmniCore-v0.9.4-debug.apk)**

### Experimental multi-system / N64 build

**[OmniCore v0.10.1 N64 Alpha 2](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.1-n64-alpha2)**

**[Direct APK — OmniCore-v0.10.1-n64-alpha2-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.1-n64-alpha2/OmniCore-v0.10.1-n64-alpha2-debug.apk)**

The Alpha uses the same stable DEV signing identity as modern OmniCore DEV builds, so compatible installations can update in place.

## Content and firmware policy

OmniCore does **not** include ROMs, game images, BIOS files, firmware, console encryption keys or proprietary game assets.

Users are responsible for supplying content and firmware they are legally entitled to use.

## Android / build baseline

- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- Android NDK: `28.2.13676358`
- CMake: 3.31.5
- Native runtime: C++20
- ABIs: `arm64-v8a`, `armeabi-v7a`
- Native ELF / APK **16 KB page-size compatibility** verified in CI
- Stable DEV signing certificate verified in release CI

## Reproducible cores

PCSX-ReARMed pin:

`da2cb8ecd17fd0932ab6d94774c0522beebce6e3`

Mupen64Plus-Next pin:

`f275caf4b2bfa1e6d1c51636746ea793f3d80320`

Corresponding source archives are published beside applicable development APKs.

## Alpha 2 CI validation

Run `32087263427` validated the complete Alpha 2 packaging path:

- multi-system architecture checks;
- Kotlin/native host compilation;
- PCSX-ReARMed build;
- Mupen64Plus-Next ARM64/ARMv7 build;
- PS1/N64 core coexistence;
- source archives;
- signed 0.10.1 APK;
- stable DEV certificate;
- 16 KB native ELF/APK alignment;
- GitHub Alpha 2 prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Licensing

PCSX-ReARMed and Mupen64Plus-Next are distributed under GNU GPL v2 terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the corresponding source archives.

## Immediate development priorities

1. Repeat physical-device N64 boot testing with Alpha 2 and record the last visible boot stage if the N64 process fails.
2. Fix the first real Mupen/GLideN64 runtime compatibility issue without destabilizing PS1.
3. Tune N64 performance only after actual runtime telemetry is available.
4. Add N64 save RAM persistence.
5. Add N64 save states after persistence is stable.
6. Add recursive multi-system folder scanning and richer library metadata.
7. Continue PS1 regression testing against the stable 0.9.4 foundation.

---

**OmniCore** is developed by [Mauricio.gamedev (@mauricio-gamedev)](https://github.com/mauricio-gamedev).
