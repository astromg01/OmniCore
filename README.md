# OmniCore

> Android-first multi-system emulation hub built around isolated console runtimes, clean touch controls and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.5%20Alpha%206-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.5-n64-alpha6)
[![PS1](https://img.shields.io/badge/PS1-device%20validated-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![N64](https://img.shields.io/badge/N64-gameplay%20confirmed-F4C95D)](https://github.com/libretro/mupen64plus-libretro-nx)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)

OmniCore is a **multi-system Android emulation hub**, not a frontend built around a single console. Every supported system owns its own core integration, runtime policy, settings, storage and console-specific input behavior while sharing one library and one consistent Android experience.

**PlayStation 1** is the current stable DEV backend. **Nintendo 64** is the second integrated backend and is now in active real-device Alpha development. PSP, Wii / GameCube, PlayStation 2 and Nintendo Switch remain roadmap targets.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls validated on Android hardware |
| N64 Alpha | **0.10.5 Alpha 6** | Real-device N64 gameplay confirmed; performance/audio/touch tuning in active validation |

### Real-device Nintendo 64 milestone

The N64 runtime has now crossed its first real-device gameplay milestone.

**Alpha 5 successfully booted and rendered a real Nintendo 64 game on Android hardware using OmniCore's own N64 runtime, Mupen64Plus-Next, GLideN64 and the dedicated touch layer.**

That milestone validates the core loading path, ROM preparation, EGL/OpenGL ES 3 hardware rendering, isolated N64 process and basic controller path on a physical Android device.

It does **not** mean N64 is finished or universally compatible. The current Alpha 6 cycle is focused on the next layer of work: sustained frame rate, audio stability, touch comfort, widescreen behavior and broader game compatibility.

## OmniCore 0.10.5 — N64 Alpha 6

Alpha 6 is the current experimental multi-system build.

### Performance focus

The N64 path now includes:

- **Dynarec-first execution** instead of using the diagnostic Cached Interpreter for normal play.
- N64-specific **SmartPerf** telemetry and decisions.
- Native-resolution fallback under sustained frame pressure.
- Reduced GLideN64 buffer-copy / LOD work in the low-cost performance path.
- Frame pacing that discards excessive timing debt instead of issuing aggressive catch-up frame bursts.
- Dedicated **AAudio** output with adaptive buffering.
- Live audio-buffer target changes based on runtime underruns and frame telemetry.
- Thermal-aware performance policy.
- No root requirement, forced clocks, hidden APIs or persistent vendor tweaks.

These Alpha 6 optimizations are still being evaluated on physical devices and should not yet be interpreted as a universal performance guarantee.

### N64 rendering and widescreen

The N64 renderer uses:

- **Mupen64Plus-Next/libretro**
- EGL + **OpenGL ES 3**
- **GLideN64**
- libretro hardware-render negotiation through `RETRO_ENVIRONMENT_SET_HW_RENDER`

Presentation modes now include:

- **Original 4:3**
- **16:9 adjusted** using GLideN64's widescreen-aware aspect option
- **16:9 stretched**

The adjusted mode is intended to use the backend's widescreen behavior rather than simply stretching a 4:3 frontend image. Individual games may still expose visual quirks because original N64 software was generally authored around 4:3 output.

### N64 touch controls

Nintendo 64 has its own controller implementation rather than reusing PlayStation button semantics.

Current controls include:

- analog stick
- A / B
- Z
- L / R
- Start
- four C-buttons
- optional D-pad
- stable multi-pointer ownership
- slide retargeting between touch buttons
- configurable touch scale
- configurable opacity
- optional haptics
- **Clear**, Standard and Compact layouts
- dynamic idle fade in the Clear layout
- Android USB / Bluetooth physical-controller mapping

The visual controls are deliberately smaller than their touch hitboxes so the screen can remain cleaner without making the buttons harder to hit.

## Multi-system library

The home screen is **OmniCore-first**, not PS1-first or N64-first.

The app includes:

- unified **Biblioteca**
- **Sistemas** area
- **Ajustes** area
- system filters that act as library views rather than forced file classification
- independent PS1 and N64 configuration surfaces
- room for future systems without presenting planned cores as already functional

### Mixed folder scanning

A selected folder can contain supported content from more than one console.

The scanner can currently:

- keep PS1 `CUE/BIN` sets grouped correctly
- continue scanning after finding a PS1 CUE
- detect Nintendo 64 ROMs in the same selected folder
- avoid listing CUE-referenced PS1 BIN tracks as separate games
- perform import I/O away from the Compose/UI thread
- keep ambiguous files away from the wrong backend when confidence is insufficient

Recursive nested-folder discovery remains a future library improvement.

## Nintendo 64 ROM support

N64 recognition is **signature-first**, not extension-only.

Recognized native byte orders:

- big-endian `.z64` — header `80 37 12 40`
- byte-swapped `.v64` — header `37 80 40 12`
- little-endian `.n64` — header `40 12 37 80`

Current preparation support includes:

- `.z64`
- `.n64`
- `.v64`
- valid `.rom` / `.bin` dumps when the actual N64 signature matches
- ZIP containing a recognized N64 ROM
- GZIP containing a recognized N64 ROM

Byte-swapped and little-endian dumps are normalized into a canonical big-endian `.z64` file inside the N64-only cache. The original source file is not modified.

`7z` is not advertised yet because OmniCore does not currently bundle a validated 7z extraction backend.

## N64 crash isolation and diagnostics

Nintendo 64 runs in a dedicated Android process:

`com.omnicore.emulator:n64`

This keeps an experimental N64 failure isolated from the main OmniCore library / PS1 process.

The N64 path also records local boot breadcrumbs covering Activity creation, settings, UI, core probing, storage, ROM preparation, Surface creation and JNI/native startup. Java/Kotlin launch exceptions can be persisted and shown inside OmniCore after the N64 process exits.

This diagnostic path was used during the Alpha cycle to identify and fix an Android `WindowInsetsController` launch crash before the emulator core itself had even started.

## PlayStation 1 — stable DEV 0.9.4

The PS1 backend has real-device validated gameplay, video, audio and controls using the pinned PCSX-ReARMed core.

Highlights:

- **PCSX-ReARMed/libretro** pinned reproducibly
- ARM64 + ARMv7
- native C++/JNI runtime
- EGL / OpenGL ES presentation
- adaptive AAudio
- stable per-pointer multitouch controls
- Android USB/Bluetooth controller input
- save RAM / memory cards and save states
- optional user-supplied PS1 BIOS
- optional classic PS BIOS boot/logo with a valid real BIOS
- CUE/BIN folder workflow
- CHD/PBP single-file workflows where supported
- persistent prepared-disc cache
- 4:3, 16:9 presentation and fullscreen modes

No PlayStation BIOS is bundled.

## Console isolation

OmniCore intentionally keeps console-specific behavior separate.

PS1 and N64 do **not** share console-specific:

- runtime knobs
- core settings
- save directories
- controller semantics
- firmware policy
- performance tuning state

The shared application layer provides the library, navigation and common Android experience, while each console backend remains independently maintainable.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / gameplay confirmed / Alpha optimization** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

Future systems are not considered functional until their own runtime has been integrated and validated.

## Downloads

### Stable PS1 build

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

**[Direct APK — OmniCore-v0.9.4-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.4-dev/OmniCore-v0.9.4-debug.apk)**

### Experimental N64 / multi-system build

**[OmniCore v0.10.5 N64 Alpha 6](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.5-n64-alpha6)**

**[Direct APK — OmniCore-v0.10.5-n64-alpha6-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.5-n64-alpha6/OmniCore-v0.10.5-n64-alpha6-debug.apk)**

The Alpha uses the same stable DEV signing identity as modern OmniCore development builds, allowing compatible test installations to update in place.

Development releases may still contain game-specific compatibility or performance regressions. Keep important save data backed up while the runtime remains under active development.

## Content and firmware policy

OmniCore does **not** include:

- ROMs or game images
- BIOS files
- firmware
- console encryption keys
- proprietary game assets

Users are responsible for supplying content and firmware they are legally entitled to use.

Standard N64 cartridge ROMs do not require an external BIOS in the current Mupen64Plus-Next path. Future systems that require firmware will use their own isolated firmware management instead of a global shared BIOS folder.

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

## Alpha 6 CI validation

The Alpha 6 release pipeline validates:

- multi-system architecture checks
- Kotlin/native host compilation
- PCSX-ReARMed build
- Mupen64Plus-Next ARM64/ARMv7 build
- PS1/N64 core coexistence
- signed OmniCore 0.10.5 APK
- stable DEV certificate
- native 16 KB ELF alignment
- APK `zipalign -P 16` validation
- corresponding core source archives
- GitHub Alpha prerelease publication

CI does not use copyrighted ROMs, BIOS or firmware and therefore does not replace physical-device gameplay testing.

## Project structure

```text
app/
  src/main/java/com/omnicore/emulator/
    core/
      n64/           Nintendo 64 core integration and ROM preparation
    emulation/       PS1/N64 activities and console-specific touch input
    library/         Multi-system import / recognition
    performance/     Per-console SmartPerf policies
    settings/        Console-specific settings
    storage/         Library, saves, BIOS/firmware and caches
    ui/              Multi-system Compose frontend

  src/main/cpp/
    libretro_host_v7.cpp
    gl_presenter.cpp
    native_bridge.cpp
    n64/
      n64_libretro_host.cpp
      n64_native_bridge.cpp

third_party/
  PCSX_REARMED_PIN.txt
  MUPEN64PLUS_NEXT_PIN.txt
  licenses/

tools/
  fetch_ps1_core.sh
  build_ps1_core_android.sh
  fetch_n64_core.sh
  build_n64_core_android.sh
```

## Licensing

PCSX-ReARMed and Mupen64Plus-Next are distributed under GNU GPL v2 terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the corresponding source archives published with development builds.

OmniCore's distribution model must remain compatible with every backend included in a public build. Core licensing is treated as an architectural requirement.

## Immediate development priorities

1. Validate Alpha 6 N64 frame pacing and audio behavior on physical Android hardware.
2. Tune SmartPerf using real-device telemetry without destabilizing the now-working boot/render path.
3. Refine N64 Clear touch controls, hitboxes, layouts and per-device ergonomics.
4. Expand N64 ROM/game compatibility testing.
5. Add N64 save persistence and save states after runtime stability is sufficient.
6. Improve multi-system recursive folder scanning and library metadata.
7. Continue PS1 regression testing against the stable 0.9.4 foundation.
8. Integrate future consoles only behind their own isolated runtime/settings/storage boundary.

---

**OmniCore** is developed by [Mauricio.gamedev (@mauricio-gamedev)](https://github.com/mauricio-gamedev).
