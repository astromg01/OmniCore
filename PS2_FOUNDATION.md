# OmniCore PS2 Foundation

## Goal

Build a PlayStation 2 runtime for Android without weakening the validated PS1 and N64 runtimes. PS2 owns its own process, native shared object, storage, telemetry, input policy, backend adapter and performance policy.

This document is an architectural reference, not a claim that PS2 gameplay is already implemented.

## External references — architecture only

OmniCore may study public emulator projects to understand proven PS2 architecture and Android constraints. Reference does not mean copying code.

### Play!

- Project: `jpd002/Play-`
- Reference commit: `04bde0df87ee7c0e2f0151b51bb2cc22c88541da`
- Useful reference areas: Android lifecycle, ARM JIT viability, GS presentation, input, disc image flow, HLE BIOS strategy.
- License: permissive BSD-style license; any future source reuse must still preserve the upstream copyright/license notices and be reviewed explicitly before integration.

### PCSX2

- Project: `PCSX2/pcsx2`
- Reference commit: `4fd1a4192a2cf4e48ee60cbe92d7d9da98f0ce44`
- Useful reference areas: EE/VU/IOP/GS/SPU2 separation, recompilers, VM state, compatibility database strategy and performance telemetry.
- PCSX2 is used as an architecture reference only for the Android-first OmniCore PS2 track unless a future license/portability review explicitly approves a different integration plan.

## Isolation contract

The PS2 track must not modify PS1 or N64 runtime behavior as a shortcut.

Planned ownership:

- Android process: `com.omnicore.emulator:ps2`
- Native shared object: `libomnicore_ps2_runtime.so`
- Kotlin package: `com.omnicore.emulator.core.ps2`
- Native package: `app/src/main/cpp/ps2/`
- Storage root: PS2-owned directory only
- Backend integration: behind `PS2Backend`; UI/runtime code must not depend directly on a third-party emulator API.

## SmartPerf is mandatory, but evidence-driven

PS2 optimization is part of the architecture from the first milestone. It must remain measurable, bounded and reversible.

### Telemetry domains

The backend adapter is expected to expose enough data to distinguish bottlenecks instead of treating every slow frame the same:

- host frame time;
- EE time/load;
- VU time/load;
- GS time/load;
- present time;
- audio queue health / hard underruns;
- JIT cache pressure and invalidation rate when available;
- texture/cache pressure when available;
- device thermal state;
- memory pressure;
- renderer/backend identity.

### Allowed intelligent actions

Only actions supported by telemetry and backend capabilities should be automated. Examples:

- select Vulkan vs GLES when both are genuinely available;
- tune bounded JIT/code-cache budgets;
- tune texture/cache budgets to device memory class;
- tune queue-ahead and audio reserve conservatively;
- reduce background/prefetch work during thermal pressure;
- apply verified per-game compatibility profiles;
- move optional work away from critical emulation/presentation threads;
- warm caches without hidden gameplay frames.

### Protected quality rules

- Native/internal scale floor starts at **1.0x**.
- SmartPerf must not silently go below the quality floor.
- Dynamic resolution is disabled until physical-device evidence proves it is needed and user-visible policy exists.
- No global cycle-skip or timing hack is enabled by default.
- No setting is changed merely because a device reports many CPU cores.
- A single spike must not permanently change the runtime profile.

## Backend strategy

The initial candidate is **Play! behind the OmniCore adapter**, because it already targets Android and uses JIT on supported platforms. The first integration milestone will validate buildability and lifecycle boundaries before any gameplay-performance claims are made.

The adapter must support capability probing so a future backend can replace Play! without rewriting the OmniCore UI, controls or storage model.

## Media detection

PS1 and PS2 share extensions such as `.iso` and `.chd`. Therefore extension-only detection is insufficient.

Before PS2 is exposed as a normal launch target, OmniCore must add content-aware disc classification. Ambiguous images must remain unresolved instead of being guessed as PS1 or PS2.

## Milestones

1. **Foundation** — isolated process, native probe library, backend contract, SmartPerf/telemetry contract, CI.
2. **Backend bring-up** — reproducible Play! candidate integration behind `PS2Backend`, no UI shortcuts.
3. **First boot** — legal user-supplied disc image flow, stable lifecycle, first rendered frame, audio path.
4. **Controls/storage** — DualShock 2 touch/physical controller mapping, memory cards, save states where backend-safe.
5. **Performance baseline** — EE/VU/GS telemetry, Vulkan/GLES comparison, JIT/cache tuning, thermal behavior.
6. **Game Intelligence** — only verified compatibility/performance profiles, never broad unmeasured hacks.
7. **Device test Alpha** — signed APK only after complete CI gates pass.

## Content policy

OmniCore does not bundle commercial games, copyrighted BIOS dumps, firmware or proprietary assets. If a selected backend uses an HLE BIOS, it must be legally redistributable under that backend's license. If a backend requires a real PS2 BIOS, the user must provide a dump they are legally entitled to use.
