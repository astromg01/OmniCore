#!/usr/bin/env python3
from pathlib import Path

BACKEND = Path("app/src/main/java/com/omnicore/emulator/core/ps2/Pcsx2PS2Backend.kt")
BRIDGE = Path("app/src/main/java/com/omnicore/emulator/core/ps2/PS2NativeBridge.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


# Visibility v2 changes only the accounting semantics: cullTests now receives
# primitiveTests, so CULL% is final rejected primitives / primitive attempts and
# prim/s is a true primitive-entry rate instead of a mixed two-stage test rate.
bridge = BRIDGE.read_text(encoding="utf-8")
bridge = replace_once(
    bridge,
    '            cullTests = values.long("cullTests"),\n',
    '            cullTests = values.long("primitiveTests"),\n',
    "#29 Visibility v2 primitive denominator",
)
BRIDGE.write_text(bridge, encoding="utf-8")

backend = BACKEND.read_text(encoding="utf-8")
old_policy = '''        val useGsPipeline = pipelineCapable && learnedProfile == PERF_PROFILE_GS
        val visualSafeBalanced = learnedProfile == PERF_PROFILE_BALANCED
        // Lockstep keeps strict GS ordering and therefore avoids the true
        // two-object Pipelined path which flickered fog on the #21/#22
        // device test. Renderer compatibility is left to PCSX2; unlike #21
        // this does not force Vulkan for BALANCED.
        val useGsLockstep = pipelineCapable && visualSafeBalanced
        val gsBackMode = when {
            useGsPipeline -> 3
            useGsLockstep -> 2
            else -> 0
        }
'''
new_policy = '''        // #29 physical-device result: both two-object Pipelined and Vulkan
        // Lockstep reproduced intermittent fog flashing in an alpha-heavy scene.
        // Keep the source-built GS single-object/inline for Alpha 6. This is a
        // correctness guardrail, not a performance fallback: no renderer,
        // resolution, emulation timing or effect is changed to hide the cost.
        val useGsPipeline = false
        val visualSafeBalanced = learnedProfile == PERF_PROFILE_BALANCED
        val useGsLockstep = false
        val gsBackMode = 0
'''
backend = replace_once(backend, old_policy, new_policy, "#29 fog-safe GS back-thread rollback")
backend = backend.replace('"A6#24 preboot profile=', '"A6#29 preboot profile=')
backend = backend.replace('"A6#24 native=$nativeMetricSeen profile=', '"A6#29 native=$nativeMetricSeen profile=')
BACKEND.write_text(backend, encoding="utf-8")

print("OMNICORE_PCSX2_ALPHA6_29_RUNTIME_PATCH_OK visibility_v2=1 true_primitive_rate=1 gs_back_off=1 fog_safety=1")
