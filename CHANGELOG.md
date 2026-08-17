# OmniCore 0.4.0 — Runtime Foundation

- Compatibility-first Android video path; experimental direct Surface framebuffer disabled by default.
- Primed AAudio output, bounded ring latency, live buffer tuning and sample-rate adaptation.
- Hybrid libretro FPS/audio clock pacing with runtime video/audio telemetry.
- Fixed PCSX-ReARMed threaded-rendering option values (`sync`/`async`) and safer compatibility defaults.
- BIOS health detection (verified retail / unknown / HLE) without bundling firmware.
- Runtime no longer reopens AAudio during thermal policy transitions.
- Runtime v4 retained in source as a rollback reference; app builds with v5.

# Changelog

## 0.2.0

### Emulation
- Added first real backend path: PCSX-ReARMed/libretro for PlayStation.
- Added ARM64/ARMv7 core build scripts pinned to a reproducible upstream revision.
- Added native libretro host, PS1 video/audio/input, memory-card save RAM and save states.
- Added touch and external-controller digital input.

### SmartPerf 2
- Added device-aware performance modes and thermal adaptation.
- Added ADPF Performance Hint integration with measured per-frame work duration.
- Added Android Surface frame-rate hint support.
- Added RGB565 render fast path.
- Reworked audio ring to block copies and bounded latency capacity.
- Fixed libretro audio backpressure reporting so the core sees the number of stereo frames actually accepted instead of a false full-consumption result.
- Added adaptive AAudio buffering based on xruns and internal starvation.
- Added headroom-aware deadline frame pacing.
- Added background content preparation and zero-copy seekable SAF path.
- Increased fallback copy block size to 256 KiB to reduce I/O overhead on large disc images without materially increasing memory pressure.
- Cached hardware profile detection.

### Build / compatibility
- Updated dependency baseline to the current stable Compose BOM 2026.06.00.
- Added 16 KB ELF/APK alignment verification in CI using `llvm-readelf` LOAD-segment alignment plus `zipalign -P 16`.
- Kept native debug runtime optimized with `-O2` for meaningful device measurements.
- Added corresponding PCSX-ReARMed source artifact to CI.

### Known limits
- PS1 v0.2 focuses on single-file games; CUE/M3U disk-control support is not implemented yet.
- Analog sticks/remapping are not implemented yet.
- N64, PSP, Wii, PS2 and Switch are not emulated yet; their frontend slots remain planned.
