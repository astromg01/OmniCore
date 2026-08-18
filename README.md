# OmniCore

> Android-first multi-system emulation hub focused on console isolation, stable frame pacing, clean touch input and device-aware performance.

[![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Stable DEV](https://img.shields.io/badge/stable%20DEV-0.9.4-57D8FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)
[![N64 Alpha](https://img.shields.io/badge/N64%20alpha-0.10.13%20Alpha%2014-9879FF)](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.13-n64-alpha14)
[![PS1](https://img.shields.io/badge/PS1-device%20validated-57D8FF)](https://github.com/libretro/pcsx_rearmed)
[![N64](https://img.shields.io/badge/N64-real--device%20testing-F4C95D)](https://github.com/libretro/mupen64plus-libretro-nx)
[![Native](https://img.shields.io/badge/native-16%20KB%20ready-3DDC84)](app/src/main/cpp/)

OmniCore is a **multi-system Android emulation hub**, not a frontend wrapped around one emulator. Each supported console owns its own core integration, runtime policy, settings, storage, performance logic and console-specific input behavior while sharing one Android library experience.

The current stable backend is **PlayStation 1**. **Nintendo 64** is the second integrated backend and is in active physical-device Alpha development. PSP, Wii / GameCube, PlayStation 2 and Nintendo Switch remain roadmap targets.

## Current status

| Channel | Version | Status |
|---|---:|---|
| Stable DEV | **0.9.4** | PS1 gameplay, video, audio and controls validated on Android hardware |
| N64 device-test | **0.10.13 Alpha 14** | Active physical-device testing with native Mupen64Plus-Next + GLideN64, SmartPrecompile and game-aware input |

The N64 channel is experimental. CI validates architecture, compilation, packaging, signing, native core coexistence and 16 KB compatibility. Real gameplay quality, driver behavior and remaining game-specific performance issues are validated on physical Android devices.

## OmniCore 0.10.13 — N64 Alpha 14

Alpha 14 continues the real-device N64 optimization cycle with a focus on **reducing first-use stutter, coordinating the existing performance systems and improving game-specific input compatibility**.

### SmartPrecompile

The N64 runtime now contains a bounded hidden warm-up stage before the first visible frame.

The current path:

- creates the GLideN64 hardware context;
- snapshots the boot state when serialization is available;
- runs a small number of hidden no-input / no-present frames;
- lets early GLideN64 shader programs and Dynarec blocks materialize before gameplay;
- flushes queued GPU work;
- restores the boot snapshot before the first visible frame;
- falls back safely if the core cannot provide the required snapshot path.

The goal is not to compile every possible shader at startup. The pass is intentionally bounded so OmniCore can move predictable first-use work out of visible gameplay without turning launch into a large blocking compilation step.

### Persistent shader-cache warm-up

GLideN64 shader storage remains persistent under the N64 system directory.

Alpha 14 expands the cache warm-up path to:

- verify the shader-cache directory before enabling storage;
- prioritize recent cache files;
- prefetch a bounded amount of existing shader data before context reset;
- use up to **12 MiB** of page-cache warm-up budget;
- cooperate with SmartPrecompile instead of treating file cache and runtime compilation as unrelated systems.

A first encounter with a completely new shader combination can still require real compilation. Repeated runs should benefit when the device driver supports reusable shader binaries.

### SmartPerf + ADPF cooperation

OmniCore now lets the performance systems share state instead of reacting independently.

During early N64 startup and precompile work:

- ADPF can request temporary CPU + GPU headroom;
- WarmStart protects audio and avoids premature low-latency tuning;
- SmartPrecompile reports completion back into SmartPerf;
- SmartPerf can leave the aggressive warm-up phase earlier once the session proves stable;
- BurstShield / RenderShield continue reacting to measured frame and presentation spikes.

This keeps optimization event-driven and bounded rather than permanently forcing maximum clocks or lowering visual quality for short transient spikes.

### DirectPresenter + RenderBridge fallback

On compatible devices OmniCore asks Android for a native buffer matching the selected internal N64 resolution and renders through the default framebuffer. Android's compositor then performs the final display scaling.

When this path is accepted, OmniCore avoids its older extra full-frame GLES blit before presentation.

If the device rejects the requested native-buffer geometry, OmniCore automatically keeps the proven **RenderBridge** framebuffer path instead.

### Smart Analog + Game Intelligence

Alpha 14 extends the N64 input system with lightweight ROM-aware compatibility policy.

The left analog keeps normal N64 analog behavior by default, but **AUTO** can now enable analog-to-D-pad bridging for known digital-movement titles without requiring the virtual arrows to be hidden.

Current behavior includes:

- **Inteligente / AUTO** — normal analog input plus compatibility-aware D-pad projection when appropriate;
- **Somente analógico** — never synthesizes D-pad input;
- **Analógico → D-pad** — explicitly converts the left analog into digital directions;
- radial deadzone and sensitivity shaping in one native stage;
- directional hysteresis to reduce flicker around thresholds;
- diagonal D-pad projection;
- separate synthesized and physical/touch D-pad masks so one input source cannot corrupt another.

The first explicit digital-movement profile is **Kirby**, allowing the left analog to drive games that normally respond only to N64 D-pad directions while AUTO is selected.

### Renderer and compatibility protections

Alpha 14 preserves the compatibility-sensitive foundation built during earlier N64 alphas:

- **Mupen64Plus-Next/libretro** pinned to `f275caf4b2bfa1e6d1c51636746ea793f3d80320`;
- **GLideN64** over OpenGL ES 3;
- Dynarec-first CPU policy;
- framebuffer emulation protected for compatibility-sensitive effects and menus;
- Intelligent **1.5×** internal-resolution path preserved (`960×720` at 4:3 / `960×540` widescreen);
- widescreen remains independent from framebuffer compatibility policy;
- DirectPresenter with automatic RenderBridge fallback;
- adaptive native AAudio;
- save RAM and save states;
- editable touch-control positions and per-control sizing;
- optional D-pad visibility;
- external Android controller support;
- frame-time, presentation, audio and runtime-state telemetry.

## Multi-system frontend

The home screen is OmniCore-first rather than tied to one console.

Current frontend architecture includes:

- unified **Biblioteca**;
- dedicated **Sistemas** area;
- dedicated **Ajustes** area;
- console filters as library views rather than forced file classifications;
- isolated PS1 and N64 configuration/runtime state;
- multi-system folder scanning;
- console-specific launch preparation and prepared-content caches.

## Nintendo 64 content handling

N64 recognition is signature-first rather than extension-only.

Recognized native byte orders:

- big-endian `.z64` — header `80 37 12 40`;
- byte-swapped `.v64` — header `37 80 40 12`;
- little-endian `.n64` — header `40 12 37 80`.

The N64 preparation path supports:

- `.z64`;
- `.n64`;
- `.v64`;
- correctly identified `.rom` / `.bin` dumps;
- ZIP containing a recognized N64 ROM;
- GZIP containing a recognized N64 ROM.

Prepared N64 content is normalized into the N64-only cache without modifying the user's source file.

`7z` is not advertised yet because OmniCore does not currently bundle a validated 7z extraction backend.

## N64 crash isolation

Nintendo 64 runs in a dedicated Android process:

`com.omnicore.emulator:n64`

This isolates experimental Mupen64Plus-Next / GLideN64 native failures from the main OmniCore library and the PS1 runtime.

## PlayStation 1 — stable DEV 0.9.4

The PS1 backend has real-device validated gameplay, video, audio and controls using the pinned PCSX-ReARMed core.

Highlights:

- PCSX-ReARMed/libretro pinned reproducibly;
- ARM64 + ARMv7;
- native C++/JNI runtime;
- EGL/OpenGL ES presentation;
- adaptive AAudio;
- stable per-pointer multitouch controls;
- Android USB/Bluetooth controller input;
- save RAM / memory cards and save states;
- optional user-supplied PS1 BIOS;
- CUE/BIN folder workflow plus supported single-file images such as CHD/PBP;
- persistent prepared-disc cache;
- 4:3, 16:9 presentation and fullscreen modes.

No PlayStation BIOS is bundled.

## SmartPerf architecture

Performance management is part of OmniCore's runtime architecture rather than a single global “boost” switch.

Current principles include:

- per-console performance policy;
- conservative device profiling;
- frame pacing based on core timing;
- thermal and memory-pressure awareness;
- measured CPU/GPU presentation pressure;
- bounded adaptive audio buffering;
- Android Performance Hint / ADPF integration when available;
- background content preparation;
- compatibility-first defaults on lower-end devices;
- no root requirement, hidden APIs, forced CPU/GPU clocks or persistent vendor tweaks.

N64 and PS1 do **not** share console-specific tuning state.

## Multi-system roadmap

| System | Backend direction | Status |
|---|---|---|
| PlayStation 1 | PCSX-ReARMed / libretro | **Functional / device validated** |
| Nintendo 64 | Mupen64Plus-Next / libretro | **Integrated / Alpha 14 real-device testing** |
| PSP | PPSSPP | Planned |
| Wii / GameCube | Dolphin | Planned |
| PlayStation 2 | Backend evaluation | Planned |
| Nintendo Switch | Experimental backend evaluation | Long-term |

## Downloads

### Stable PS1 build

**[OmniCore v0.9.4 DEV](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.9.4-dev)**

**[Direct APK — OmniCore-v0.9.4-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.9.4-dev/OmniCore-v0.9.4-debug.apk)**

### Experimental multi-system / N64 build

**[OmniCore v0.10.13 N64 Alpha 14](https://github.com/mauricio-gamedev/OmniCore/releases/tag/v0.10.13-n64-alpha14)**

**[Direct APK — OmniCore-v0.10.13-n64-alpha14-debug.apk](https://github.com/mauricio-gamedev/OmniCore/releases/download/v0.10.13-n64-alpha14/OmniCore-v0.10.13-n64-alpha14-debug.apk)**

Modern DEV / N64 test builds use the stable OmniCore DEV signing identity so compatible installations can update in place.

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

## Alpha 14 CI validation

Run `32155750953` validated the Alpha 14 release path:

- Alpha 14 migration and architecture guards;
- PS1/N64 isolation checks;
- Kotlin + native N64 host compilation;
- PCSX-ReARMed build;
- Mupen64Plus-Next ARM64/ARMv7 build;
- source archives;
- signed **0.10.13** APK;
- stable DEV certificate;
- 16 KB native ELF/APK alignment;
- GitHub Alpha 14 prerelease publication.

CI uses no copyrighted ROMs, BIOS or firmware.

## Licensing

PCSX-ReARMed and Mupen64Plus-Next are distributed under GNU GPL v2 terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the corresponding source archives.

## Immediate development priorities

1. Measure Alpha 14 SmartPrecompile impact on repeated real-device N64 runs.
2. Verify Kirby and other digital-movement titles through Smart Analog AUTO without regressing analog-native games.
3. Continue reducing renderer/framebuffer transition spikes without lowering the protected 1.5× Intelligent target.
4. Expand Game Intelligence only from confirmed compatibility data rather than broad game-name hacks.
5. Continue N64 regression testing for save states, widescreen, touch editing and physical controllers.
6. Add recursive multi-system folder scanning and richer library metadata.
7. Continue PS1 regression testing against the isolated stable 0.9.4 foundation.

---

**OmniCore** is developed by [Mauricio.gamedev (@mauricio-gamedev)](https://github.com/mauricio-gamedev).
