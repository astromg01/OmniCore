# OmniCore 0.2.0 — PS1 Core + SmartPerf 2

OmniCore is an Android-first universal emulation hub. The long-term frontend is designed to host separate native backends for N64, PS1, PS2, PSP, Wii and Switch while keeping library, input, saves and performance policy unified.

## v0.2 milestone

PS1 is the first real emulation backend. The cloud build fetches a pinned PCSX-ReARMed revision, builds its Android ARM libraries and packages them with OmniCore.

Included in this milestone:

- Unified library UI for N64, PS1, PS2, PSP, Wii and Switch.
- PlayStation core status is `READY`; the other systems remain planned.
- PCSX-ReARMed libretro core built for `arm64-v8a` and `armeabi-v7a`.
- Native C++20/JNI libretro host with video, AAudio, input, memory-card saves and save states.
- Touch gamepad plus Android/Bluetooth game-controller button mapping.
- Optional PS1 BIOS import. No BIOS is bundled; the core can fall back to its HLE path when supported.
- Android Storage Access Framework library with persisted URI access.
- Zero-copy content path for seekable Android document providers; non-seekable streams are copied in a background worker only when required.
- GitHub Actions build with APK and corresponding PCSX-ReARMed source artifact.
- No ROMs, BIOS, firmware, console keys or proprietary game files are bundled.

## SmartPerf 2

The performance layer is device-aware and session-aware rather than a collection of fixed "boost" flags.

- Conservative hardware classification using RAM, CPU count, 64-bit capability, Media Performance Class, low-RAM classification and observed maximum CPU frequency when readable.
- Android thermal-status adaptation while a game is running.
- Android Performance Hint / ADPF session when available, fed with measured emulation-thread work duration.
- Surface frame-rate hint for the emulated refresh rate when supported by Android.
- Deadline-based frame pacing with a short low-latency yield only when measured CPU headroom exists.
- RGB565 zero-conversion rendering fast path.
- Lock-free-style single-producer/single-consumer audio ring indices with block `memcpy` instead of sample-by-sample copies.
- Adaptive AAudio buffer reacts to both platform xruns and ring-buffer starvation, then shrinks after a stable period.
- Exclusive low-latency audio is only attempted on suitable profiles and always falls back to shared mode.
- Native runtime is compiled with `-O2` even in the debug APK so device performance tests are meaningful.
- No root, hidden APIs, fixed CPU clocks, vendor-specific properties or persistent system changes.

See [OPTIMIZATION.md](OPTIMIZATION.md) for the policy details.

## PS1 content supported in v0.2

Single-file content is the first target: `chd`, `pbp`, `iso`, `bin`, `img`, `mdf`, `cbn` and `exe` as accepted by the current frontend filter. Multi-disc / descriptor workflows such as CUE/M3U are intentionally deferred until the disk-control layer is added.

## Build baseline

- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- Kotlin / Compose Compiler plugin 2.3.21
- Compose BOM 2026.06.00
- Activity Compose 1.13.0
- Lifecycle 2.10.0
- compileSdk / targetSdk 36
- minSdk 26
- NDK 28.2.13676358
- CMake 3.31.5

## Cloud build

Push the repository to GitHub or run the `Android Build` workflow manually. The workflow:

1. Installs the pinned Android toolchain.
2. Fetches PCSX-ReARMed at the revision recorded in `third_party/PCSX_REARMED_PIN.txt`.
3. Builds the core for ARM64 and ARMv7.
4. Archives the corresponding core source.
5. Builds `app-debug.apk`.
6. Checks ELF LOAD-segment alignment and runs `zipalign -P 16` validation.
7. Uploads the APK and PCSX-ReARMed source as workflow artifacts.

The source package itself does not contain ROMs or BIOS files.

## Local build

With Android SDK/NDK installed:

```bash
./tools/fetch_ps1_core.sh
ANDROID_NDK_HOME=/path/to/android-ndk ./tools/build_ps1_core_android.sh
gradle :app:assembleDebug
```

## Licensing note

PCSX-ReARMed is distributed under GNU GPL v2 terms. `THIRD_PARTY_NOTICES.md` records the pinned component and the workflow emits its corresponding source bundle. Before a public/store release, the licensing strategy for the OmniCore frontend itself must be finalized so distribution remains compatible with every bundled core.

## Validation status

The v0.2 source/runtime passed local native syntax and project-structure checks. A full APK build could not be executed in the current container because it does not contain the Android SDK/NDK and cannot directly clone the pinned core source. The included `Android Build` GitHub Actions workflow is the reproducible full-build path. See `VALIDATION.md` for the exact checks completed.

## Next milestones

1. On-device PS1 compatibility/performance validation and per-game profiles.
2. Analog-stick input and controller remapping.
3. N64 backend.
4. PSP backend.
5. Wii backend.
6. PS2 backend evaluation/integration.
7. Switch backend evaluation last.

The working name and application ID are still changeable before publication.
