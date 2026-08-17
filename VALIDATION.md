# OmniCore 0.2.0 validation report

Date: 2026-08-17

## Passed in this environment

- Native runtime syntax check with Clang 17 (`-std=c++20 -Wall -Wextra -Wpedantic`) using minimal Android/AAudio/JNI declaration stubs.
- `libretro_host.cpp` passed syntax validation.
- `native_bridge.cpp` passed syntax validation.
- Android XML resources and manifest parsed successfully.
- `gradle/libs.versions.toml` parsed successfully.
- GitHub Actions workflow YAML parsed successfully.
- Shell syntax passed for `tools/fetch_ps1_core.sh` and `tools/build_ps1_core_android.sh`.
- Kotlin sources passed structural delimiter checks after the final patch set.
- PCSX-ReARMed is pinned to `da2cb8ecd17fd0932ab6d94774c0522beebce6e3` and the build scripts target ARM64 + ARMv7.

## Final v0.2 corrections included

- Correct libretro experimental environment command IDs.
- Background preparation for large Android document-provider content.
- Zero-copy `/proc/self/fd` session path for seekable providers.
- 256 KiB fallback copy buffer for non-seekable providers.
- Race-safe content descriptor/session ownership.
- Conservative SmartPerf automatic hardware classification.
- Thermal runtime policy adaptation.
- ADPF/Performance Hint runtime bridge loaded dynamically.
- Surface frame-rate hint bridge loaded dynamically.
- RGB565 direct row-copy video fast path.
- Block-copy audio ring.
- Internal underrun telemetry plus AAudio xrun telemetry.
- Correct libretro audio batch backpressure return value.
- Adaptive AAudio burst sizing.
- Native `-O2` debug runtime and release LTO configuration.
- 16 KiB ELF linker alignment request.
- CI checks ELF LOAD alignment with `llvm-readelf` and APK alignment with `zipalign -P 16`.

## Not executed here

A complete Android APK build was not executed in this container because the Android SDK/NDK toolchain is not installed and direct GitHub source cloning is unavailable from the container network. The included GitHub Actions workflow installs the pinned Android toolchain, fetches/builds PCSX-ReARMed, builds the debug APK and performs the native alignment checks.

The first device test should therefore be treated as the final integration gate for PS1 rendering/audio/input compatibility.
