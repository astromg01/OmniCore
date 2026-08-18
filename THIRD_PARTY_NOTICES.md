# Third-party notices

OmniCore development builds can bundle separately built libretro cores. Console cores remain independent native components and are distributed with their corresponding source archives when required by their licenses.

## PCSX-ReARMed

OmniCore uses PCSX-ReARMed as the PlayStation 1 backend.

- Project: PCSX-ReARMed
- Upstream: https://github.com/libretro/pcsx_rearmed
- Pinned revision: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`
- Upstream license: GPL-2.0
- Local GPL text: `third_party/licenses/GPL-2.0.txt`

The Android release workflow archives the exact checked-out PCSX-ReARMed source tree used to build distributed development APKs.

## Mupen64Plus-Next

OmniCore uses Mupen64Plus-Next as the Nintendo 64 backend in the current experimental N64 Alpha line.

- Project: Mupen64Plus-Next / mupen64plus-libretro-nx
- Upstream: https://github.com/libretro/mupen64plus-libretro-nx
- Pinned revision: `f275caf4b2bfa1e6d1c51636746ea793f3d80320`
- Upstream license: GPL-2.0
- Local GPL text: `third_party/licenses/GPL-2.0.txt`

The Android N64 workflow fetches that exact revision, applies OmniCore Android packaging/build adjustments required for core coexistence and 16 KB ELF alignment, builds ARM64 and ARMv7 shared libraries, and publishes the corresponding source archive beside applicable N64 Alpha APKs.

The current N64 runtime integrates Mupen64Plus-Next through a dedicated libretro host, EGL/OpenGL ES 3 hardware rendering and GLideN64. Nintendo 64 remains an experimental backend under active real-device compatibility and performance validation.

## libretro ABI declarations

`app/src/main/cpp/libretro_abi.h` and the N64-specific ABI declarations under `app/src/main/cpp/n64/` contain the subset of libretro API declarations needed by OmniCore's native frontend hosts.

Canonical upstream libretro API header:

https://github.com/libretro/libretro-common/blob/master/include/libretro.h

## Proprietary content

OmniCore does not include BIOS files, Nintendo ROMs, PlayStation game images, firmware, console keys or other proprietary console/game content. Users must provide content and firmware they are legally entitled to use.
