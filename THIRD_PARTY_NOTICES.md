# Third-party notices

OmniCore development builds can bundle separately built emulator backends. Console runtimes remain isolated native components and are distributed with the notices/source material required by their licenses.

## PCSX-ReARMed

OmniCore uses PCSX-ReARMed as the PlayStation 1 backend.

- Project: PCSX-ReARMed
- Upstream: https://github.com/libretro/pcsx_rearmed
- Pinned revision: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`
- Upstream license: GPL-2.0
- Local GPL text: `third_party/licenses/GPL-2.0.txt`

The Android release workflow archives the exact checked-out PCSX-ReARMed source tree used to build the distributed APK.

## Mupen64Plus-Next

OmniCore uses Mupen64Plus-Next as the Nintendo 64 backend.

- Project: Mupen64Plus-Next / mupen64plus-libretro-nx
- Upstream: https://github.com/libretro/mupen64plus-libretro-nx
- Pinned revision: `f275caf4b2bfa1e6d1c51636746ea793f3d80320`
- Upstream license: GPL-2.0
- Local GPL text: `third_party/licenses/GPL-2.0.txt`

The Android N64 workflow fetches that exact revision, applies only the OmniCore Android packaging/build adjustments required for core coexistence and 16 KB ELF alignment, builds the ARM64 and ARMv7 shared libraries, and publishes the corresponding source archive beside applicable APKs.

## Play! — PS2 backend candidate

The PS2 development branch builds Play! as an external, separately fetched Android backend behind the OmniCore `PS2Backend` boundary. Play! source code is not copied into the OmniCore source tree by the bring-up scripts.

- Project: Play!
- Upstream: https://github.com/jpd002/Play-
- Pinned revision: `04bde0df87ee7c0e2f0151b51bb2cc22c88541da`
- Upstream license: BSD 2-Clause style
- Local license text: `third_party/licenses/Play-BSD-2-Clause.txt`
- Packaged backend library during bring-up: `libPlay.so`

The build fetches the exact upstream revision including its declared submodules and compiles the Android `Play` target separately for each supported ABI. OmniCore-specific lifecycle, telemetry, controls, storage and SmartPerf logic remain outside the upstream tree.

## libretro ABI declarations

`app/src/main/cpp/libretro_abi.h` and the N64-specific ABI declarations under `app/src/main/cpp/n64/` contain the subset of libretro API declarations needed by OmniCore's native frontend hosts.

Canonical upstream libretro API header:

https://github.com/libretro/libretro-common/blob/master/include/libretro.h

## Proprietary content

OmniCore does not include Sony BIOS files, Nintendo ROMs, games, firmware, console keys or other proprietary console/game content. Users must provide content they are legally entitled to use.
