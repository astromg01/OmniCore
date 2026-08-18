# Changelog

## 0.10.13 — N64 Alpha 14

### SmartPrecompile / shaders
- Added a bounded hidden N64 pre-execution pass before the first visible frame.
- The runtime snapshots the boot state when available, runs a few no-input/no-present frames, flushes GPU work and restores the original state before normal presentation.
- Early GLideN64 shader programs and Dynarec blocks can therefore materialize outside visible gameplay instead of all landing on the first interactive frames.
- Expanded persistent GLideN64 shader-cache page warm-up to a bounded 12 MiB budget.
- Shader-cache warm-up now prioritizes recent files rather than arbitrary directory order.
- SmartPrecompile remains fail-safe: unsupported serialization paths skip the hidden pass rather than breaking game boot.

### Performance intelligence
- SmartPrecompile completion is exposed through native telemetry.
- SmartPerf consumes the precompile-ready signal and can shorten WarmStart once runtime stability is demonstrated.
- ADPF can request transient CPU + GPU headroom during the bounded precompile stage.
- Existing RenderShield / BurstShield behavior remains responsible for measured presentation and frame-time spikes.
- Protected Intelligent 1.5× N64 rendering is retained; short performance spikes are not solved by blindly reducing resolution.

### Input / Game Intelligence
- Added `N64GameIntelligence`, a small ROM-identity compatibility layer used only by the N64 runtime.
- Smart Analog AUTO can now enable analog-to-D-pad bridging for known digital-movement titles even when the virtual D-pad remains visible.
- Added the first explicit digital-movement profile for Kirby-class N64 titles.
- Explicit Analog-only and Analog-to-D-pad modes continue overriding AUTO behavior.
- Existing radial precision, deadzone/sensitivity shaping, hysteresis and diagonal projection remain intact.

### Rendering / compatibility
- Preserved DirectPresenter with automatic RenderBridge fallback.
- Preserved framebuffer emulation for compatibility-sensitive N64 effects and menus.
- Preserved widescreen independence, Dynarec, GLideN64, AAudio, save states, editable touch controls and external-controller support.
- PS1-specific source remains isolated from N64 Alpha work.

### Build / validation
- Version code: 29.
- Version name: 0.10.13.
- Release tag: `v0.10.13-n64-alpha14`.
- GitHub Actions run `32155750953` passed architecture checks, Kotlin/native compilation, isolated PS1/N64 core builds, DEV signing verification and 16 KB ELF/APK alignment.

## 0.9.0 — Input & Frontend Polish

### Input
- Added Intelligent left-stick compatibility mode: native DualShock axes plus D-pad projection for early PS1 games.
- Added selectable Intelligent, Native and D-pad stick modes.
- Added Android USB/Bluetooth joystick-axis handling with deadzone normalization.
- Added configurable touch-control scale, opacity and optional haptics.

### Frontend
- Added library search and Recent / A–Z / Size ordering.
- Added confirmation before removing library entries.
- Preserved Runtime v7, EGL/GLES, AAudio, BIOS boot, CUE/BIN cache, saves and the stable DEV update path.

## 0.8.0 — Compatibility & UX

- Added persistent validated CUE/BIN staging cache for faster repeated launches.
- Added optional classic PlayStation BIOS boot/logo.
- Added 4:3, 16:9 presentation and fullscreen modes.
- Preserved the validated Runtime v7 / EGL-GLES video foundation.

## 0.7.0 — Video Composition Fix

- Fixed Android SurfaceView composition that could hide correctly rendered PS1 frames behind an opaque View background.
- Switched the PS1 compatibility path to XRGB8888 output and explicit GLES presentation.
- Marked the first real-device PS1 gameplay milestone with working video and audio.

## 0.6.0 — EGL/GLES Video Foundation

### Video / runtime
- Replaced the remaining direct CPU `ANativeWindow` presentation path with a dedicated **EGL + OpenGL ES 2** presenter.
- Kept video presentation on a renderer thread so Surface/GPU work cannot stall the libretro emulation thread.
- Added direct RGB565 texture uploads and explicit XRGB8888 / 0RGB1555 conversion paths.
- Added renderer diagnostics so produced frames, visible frames, presented frames and presentation failures can be distinguished.
- Kept the Runtime v6 decoupled A/V architecture that fixed audio starvation during real-device testing.

### Audio / performance
- Retained primed AAudio startup, adaptive bounded buffering and sample-rate adaptation.
- Kept SmartPerf runtime telemetry, thermal adaptation, frame pacing and Android performance hints.
- Preserved compatibility-first defaults instead of forcing aggressive GPU/SPU threading.

### Updates / signing
- Added an in-app DEV updater backed by OmniCore GitHub Releases.
- Added semantic version comparison and APK SHA-256 verification before installation.
- Integrated Android `PackageInstaller` for user-approved in-place updates.
- Introduced a stable **DEV-only** signing identity for v0.6.0+ development builds.
- Migration from v0.5.0 or older requires one final uninstall because older GitHub-runner debug builds were signed with different ephemeral certificates.

### Build / validation
- Added Runtime v7 and `gl_presenter` to the native build.
- CI validates the stable DEV signing certificate.
- CI continues validating native 16 KB ELF alignment and APK `zipalign -P 16` compatibility.
- The v0.6.0 APK and corresponding pinned PCSX-ReARMed source bundle are published through GitHub Releases.

## 0.5.0 — Decoupled Runtime

- Moved all `ANativeWindow` work off the libretro emulation thread.
- Added a latest-frame mailbox and independent render worker.
- Prevented display stalls from starving `retro_run()` and the audio producer.
- Increased video telemetry around generated, black, dropped and presented frames.
- Reworked AAudio startup so playback begins only after a real sample reservoir is available.
- Expanded adaptive audio diagnostics using frontend ring underruns in addition to platform xruns.

## 0.4.0 — Runtime Foundation

- Added a compatibility-first Android video path and disabled experimental direct software framebuffer access by default.
- Added primed AAudio output, bounded ring latency, live buffer tuning and sample-rate adaptation.
- Added hybrid libretro FPS/audio clock pacing with runtime A/V telemetry.
- Fixed PCSX-ReARMed threaded-rendering option values and safer compatibility defaults.
- Added BIOS health detection without bundling firmware.
- Prevented thermal-policy transitions from reopening the live AAudio stream.

## 0.2.0 — First functional PS1 backend

### Emulation
- Added PCSX-ReARMed/libretro as the first real backend.
- Added ARM64/ARMv7 core build scripts pinned to a reproducible upstream revision.
- Added the native libretro host, PS1 video/audio/input, save RAM and save states.
- Added touch and external-controller input.

### SmartPerf
- Added device-aware performance modes and thermal adaptation.
- Added Android Performance Hint / ADPF integration.
- Added Surface frame-rate hints, frame pacing and adaptive AAudio buffering.
- Added background content preparation and seekable SAF direct-access paths.
- Added conservative device profiling and native runtime optimization for performance testing.

### Build / compatibility
- Added 16 KB ELF/APK alignment verification in CI.
- Added corresponding PCSX-ReARMed source artifacts to the cloud build.

### Scope
- PS1 became the first functional console target.
- N64, PSP, Wii, PS2 and Switch remained planned frontend/backend milestones.
