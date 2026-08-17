# Third-party notices

## PCSX-ReARMed

OmniCore 0.2 can bundle a separately built PCSX-ReARMed libretro shared library for PlayStation emulation.

- Project: PCSX-ReARMed
- Upstream: https://github.com/libretro/pcsx_rearmed
- Pinned revision: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3`
- Upstream license: GPL-2.0
- Local license copy: `third_party/licenses/GPL-2.0.txt`

The GitHub Actions build also archives the exact checked-out PCSX-ReARMed source tree used to build the APK. OmniCore does not include Sony BIOS files, games or other proprietary console content.

## libretro ABI declarations

`app/src/main/cpp/libretro_abi.h` contains a small subset of declarations from the libretro API needed by OmniCore's frontend. The canonical `libretro.h` header carries a permissive license from the RetroArch team; its notice is referenced in that source file and the canonical upstream is:

https://github.com/libretro/libretro-common/blob/master/include/libretro.h
