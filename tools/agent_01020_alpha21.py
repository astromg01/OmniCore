from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)

# Version -------------------------------------------------------------------
p = "app/build.gradle.kts"
s = read(p)
s = replace_once(s, 'versionCode = 35\n        versionName = "0.10.19"', 'versionCode = 36\n        versionName = "0.10.20"', "version")
write(p, s)

# Native host header: expose rescued starvation events separately from hard
# underruns. Existing telemetry indices remain unchanged; rescue is appended.
p = "app/src/main/cpp/n64/n64_libretro_host.h"
s = read(p)
s = replace_once(
    s,
    "    float precisionGovernorConfidence = 0.0f;\n    float frameJitterMs = 0.0f;\n};",
    "    float precisionGovernorConfidence = 0.0f;\n    float frameJitterMs = 0.0f;\n    int audioRescues = 0;\n};",
    "telemetry rescue field",
)
s = replace_once(
    s,
    "    std::atomic<int> audioUnderruns_{0};\n    std::atomic<float> targetFrameMs_",
    "    std::atomic<int> audioUnderruns_{0};\n    std::atomic<int> audioRescues_{0};\n    std::atomic<float> targetFrameMs_",
    "audio rescue atomic",
)
write(p, s)

# Native host ---------------------------------------------------------------
p = "app/src/main/cpp/n64/n64_libretro_host.cpp"
s = read(p)

old_pop = '''    std::size_t pop(std::int16_t* output, std::size_t samples) {
        if (!output || samples == 0) return 0;
        const std::uint64_t read = read_.load(std::memory_order_relaxed);
        const std::uint64_t write = write_.load(std::memory_order_acquire);
        const std::size_t count = static_cast<std::size_t>(
            std::min<std::uint64_t>(samples, write - read));
        if (count > 0) {
            const std::size_t start = static_cast<std::size_t>(read % data_.size());
            const std::size_t first = std::min(count, data_.size() - start);
            std::memcpy(output, data_.data() + start, first * sizeof(std::int16_t));
            if (first < count) {
                std::memcpy(output + first, data_.data(), (count - first) * sizeof(std::int16_t));
            }
        }
        if (count < samples) {
            std::fill(output + count, output + samples, 0);
            underruns_.fetch_add(1, std::memory_order_relaxed);
        }
        read_.store(read + count, std::memory_order_release);
        return count;
    }
'''
new_pop = '''    std::size_t pop(std::int16_t* output, std::size_t samples) {
        if (!output || samples == 0) return 0;
        const std::uint64_t read = read_.load(std::memory_order_relaxed);
        const std::uint64_t write = write_.load(std::memory_order_acquire);
        std::size_t count = static_cast<std::size_t>(
            std::min<std::uint64_t>(samples, write - read));
        count &= ~static_cast<std::size_t>(1);
        if (count > 0) {
            const std::size_t start = static_cast<std::size_t>(read % data_.size());
            const std::size_t first = std::min(count, data_.size() - start);
            std::memcpy(output, data_.data() + start, first * sizeof(std::int16_t));
            if (first < count) {
                std::memcpy(output + first, data_.data(), (count - first) * sizeof(std::int16_t));
            }
        }
        // The real-time callback owns concealment. Do not inject zeroes here;
        // doing so made every short producer gap immediately audible.
        read_.store(read + count, std::memory_order_release);
        return count;
    }

    void noteUnderrun() { underruns_.fetch_add(1, std::memory_order_relaxed); }
'''
s = replace_once(s, old_pop, new_pop, "AudioRing pop")

s = replace_once(
    s,
    "    double resampleAccumulator = 0.0;\n    std::array<std::int16_t, 8192> resampleScratch{};",
    "    double resampleAccumulator = 0.0;\n    std::array<std::int16_t, 8192> resampleScratch{};\n    // Callback-only fixed storage: no allocation, locks or I/O on AAudio's\n    // real-time thread. Keeps one recent output tail for very short source gaps.\n    std::array<std::int16_t, 2048> callbackHistory{};\n    std::size_t callbackHistorySamples = 0;\n    int consecutiveStarvedCallbacks = 0;",
    "elastic callback fields",
)

callback_pattern = re.compile(
    r"    static aaudio_data_callback_result_t audioCallback\(\n"
    r"        AAudioStream\*, void\* userData, void\* audioData, std::int32_t numFrames\) \{.*?"
    r"        return AAUDIO_CALLBACK_RESULT_CONTINUE;\n    \}\n\n    bool createEgl",
    re.S,
)
callback_new = '''    static aaudio_data_callback_result_t audioCallback(
        AAudioStream*, void* userData, void* audioData, std::int32_t numFrames) {
        auto* self = static_cast<Impl*>(userData);
        if (!self || !audioData || numFrames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;

        const std::size_t requestedFrames = static_cast<std::size_t>(numFrames);
        const std::size_t requestedSamples = requestedFrames * 2u;
        auto* output = static_cast<std::int16_t*>(audioData);
        const std::size_t availableFrames = self->audioRing.availableSamples() / 2u;
        std::size_t producedSamples = 0;
        bool rescued = false;
        bool hardUnderrun = false;

        if (availableFrames >= requestedFrames) {
            producedSamples = self->audioRing.pop(output, requestedSamples);
            self->consecutiveStarvedCallbacks = 0;
        } else {
            // Source starvation is still fed to the native AAudio adaptation,
            // even if ElasticAudioBridge makes it inaudible to the user.
            self->audioRing.noteUnderrun();
            ++self->consecutiveStarvedCallbacks;

            const std::size_t minimumElasticFrames = std::max<std::size_t>(
                8u, (requestedFrames * 70u + 99u) / 100u);
            if (availableFrames >= minimumElasticFrames && requestedFrames > 1u) {
                // Consume only ~82% of the callback when possible, leaving a tiny
                // reserve in the ring. Expand that bounded slice to this callback
                // with linear interpolation. Processing backwards keeps it safe
                // in-place and avoids any temporary allocation on the RT thread.
                const std::size_t desiredConsume = std::max<std::size_t>(
                    8u, (requestedFrames * 82u + 99u) / 100u);
                const std::size_t consumeFrames = std::min(availableFrames, desiredConsume);
                producedSamples = self->audioRing.pop(output, consumeFrames * 2u);
                const std::size_t sourceFrames = producedSamples / 2u;
                if (sourceFrames >= 2u) {
                    for (std::size_t dst = requestedFrames; dst-- > 0u;) {
                        const double sourcePos = static_cast<double>(dst) *
                            static_cast<double>(sourceFrames - 1u) /
                            static_cast<double>(requestedFrames - 1u);
                        const std::size_t i0 = static_cast<std::size_t>(sourcePos);
                        const std::size_t i1 = std::min(i0 + 1u, sourceFrames - 1u);
                        const float frac = static_cast<float>(sourcePos - static_cast<double>(i0));
                        const float left = static_cast<float>(output[i0 * 2u]) +
                            (static_cast<float>(output[i1 * 2u]) - static_cast<float>(output[i0 * 2u])) * frac;
                        const float right = static_cast<float>(output[i0 * 2u + 1u]) +
                            (static_cast<float>(output[i1 * 2u + 1u]) - static_cast<float>(output[i0 * 2u + 1u])) * frac;
                        output[dst * 2u] = static_cast<std::int16_t>(std::lround(std::clamp(left, -32768.0f, 32767.0f)));
                        output[dst * 2u + 1u] = static_cast<std::int16_t>(std::lround(std::clamp(right, -32768.0f, 32767.0f)));
                    }
                    producedSamples = requestedSamples;
                    rescued = true;
                }
            }

            if (!rescued) {
                // A deeper gap cannot be safely time-stretched. Use the most
                // recent callback tail as a bounded continuity patch instead of
                // fading abruptly to silence. Repeated starvation beyond three
                // callbacks is counted as a hard underrun because it can become
                // perceptible even with concealment.
                producedSamples = self->audioRing.pop(output, requestedSamples);
                const std::size_t missingFrames = (requestedSamples - producedSamples) / 2u;
                const std::size_t historyFrames = self->callbackHistorySamples / 2u;
                const std::int16_t seamLeft = producedSamples >= 2u
                    ? output[producedSamples - 2u] : self->lastAudioLeft;
                const std::int16_t seamRight = producedSamples >= 2u
                    ? output[producedSamples - 1u] : self->lastAudioRight;

                for (std::size_t frame = 0; frame < missingFrames; ++frame) {
                    std::int16_t sourceLeft = seamLeft;
                    std::int16_t sourceRight = seamRight;
                    if (historyFrames > 0u) {
                        const std::size_t replayCount = std::min(historyFrames, std::max<std::size_t>(1u, missingFrames));
                        const std::size_t replayStart = historyFrames - replayCount;
                        const std::size_t src = replayStart + (frame % replayCount);
                        sourceLeft = self->callbackHistory[src * 2u];
                        sourceRight = self->callbackHistory[src * 2u + 1u];
                    }
                    const float blend = std::min(1.0f, static_cast<float>(frame + 1u) / 8.0f);
                    const float hold = 1.0f - 0.10f * static_cast<float>(frame + 1u) /
                        static_cast<float>(missingFrames + 1u);
                    const float left = (static_cast<float>(seamLeft) * (1.0f - blend) +
                        static_cast<float>(sourceLeft) * blend) * hold;
                    const float right = (static_cast<float>(seamRight) * (1.0f - blend) +
                        static_cast<float>(sourceRight) * blend) * hold;
                    output[producedSamples + frame * 2u] = static_cast<std::int16_t>(
                        std::lround(std::clamp(left, -32768.0f, 32767.0f)));
                    output[producedSamples + frame * 2u + 1u] = static_cast<std::int16_t>(
                        std::lround(std::clamp(right, -32768.0f, 32767.0f)));
                }
                producedSamples = requestedSamples;
                if (self->callbackHistorySamples >= 2u && self->consecutiveStarvedCallbacks <= 3) {
                    rescued = true;
                } else {
                    hardUnderrun = true;
                }
            }
        }

        if (requestedSamples >= 2u) {
            self->lastAudioLeft = output[requestedSamples - 2u];
            self->lastAudioRight = output[requestedSamples - 1u];
            const std::size_t keep = std::min(requestedSamples, self->callbackHistory.size());
            std::memcpy(
                self->callbackHistory.data(),
                output + (requestedSamples - keep),
                keep * sizeof(std::int16_t));
            self->callbackHistorySamples = keep;
        }
        if (self->owner) {
            if (rescued) self->owner->audioRescues_.fetch_add(1, std::memory_order_relaxed);
            if (hardUnderrun) self->owner->audioUnderruns_.fetch_add(1, std::memory_order_relaxed);
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    bool createEgl'''
s, n = callback_pattern.subn(callback_new, s, count=1)
if n != 1:
    raise SystemExit(f"audio callback: expected one match, found {n}")

# Reset callback continuity state whenever the stream is recreated/reprimed.
s = replace_once(
    s,
    "        audioRing.clear();\n        coreSampleRate = sampleRate > 1000.0 ? sampleRate : 44100.0;",
    "        audioRing.clear();\n        callbackHistorySamples = 0;\n        consecutiveStarvedCallbacks = 0;\n        coreSampleRate = sampleRate > 1000.0 ? sampleRate : 44100.0;",
    "open audio elastic reset",
)
s = replace_once(
    s,
    "        audioRing.clear();\n        resampleAccumulator = 0.0;\n        lastRingUnderruns = 0;\n        stableAudioChecks = 0;\n        armTransitionAudioShield(std::chrono::milliseconds(2200));",
    "        audioRing.clear();\n        callbackHistorySamples = 0;\n        consecutiveStarvedCallbacks = 0;\n        resampleAccumulator = 0.0;\n        lastRingUnderruns = 0;\n        stableAudioChecks = 0;\n        armTransitionAudioShield(std::chrono::milliseconds(2200));",
    "reprime elastic reset",
)

s = replace_once(
    s,
    "    audioUnderruns_.store(0, std::memory_order_release);\n    audioFillMs_.store",
    "    audioUnderruns_.store(0, std::memory_order_release);\n    audioRescues_.store(0, std::memory_order_release);\n    audioFillMs_.store",
    "start rescue reset",
)
s = replace_once(
    s,
    "    out.audioUnderruns = audioUnderruns_.load(std::memory_order_acquire);\n    out.audioFillMs",
    "    out.audioUnderruns = audioUnderruns_.load(std::memory_order_acquire);\n    out.audioRescues = audioRescues_.load(std::memory_order_acquire);\n    out.audioFillMs",
    "telemetry rescue export",
)
s = s.replace("Alpha 20", "Alpha 21")
write(p, s)

# JNI telemetry: append rescue count without moving any existing index.
p = "app/src/main/cpp/n64/n64_native_bridge.cpp"
s = read(p)
s = replace_once(
    s,
    'OmniCore N64 Runtime 0.10.19 • Mupen64Plus-Next • GLES3 + AAudio host v16 • TransitionAudioShield + PrecisionGovernor v2.1 + RacingComfort + MicroBurstShield + CruiseGuard + PassiveWarmCache + DirectPresenter • ',
    'OmniCore N64 Runtime 0.10.20 • Mupen64Plus-Next • GLES3 + AAudio host v17 • ElasticAudioBridge + TransitionAudioShield + PrecisionGovernor v2.1 + RacingComfort + MicroBurstShield + CruiseGuard + PassiveWarmCache + DirectPresenter • ',
    "runtime info",
)
s = replace_once(s, "    const jfloat values[22] = {", "    const jfloat values[23] = {", "JNI telemetry array")
s = replace_once(
    s,
    "        telemetry.precisionGovernorConfidence,\n        telemetry.frameJitterMs\n    };\n    jfloatArray result = env->NewFloatArray(22);\n    if (result) env->SetFloatArrayRegion(result, 0, 22, values);",
    "        telemetry.precisionGovernorConfidence,\n        telemetry.frameJitterMs,\n        static_cast<jfloat>(telemetry.audioRescues)\n    };\n    jfloatArray result = env->NewFloatArray(23);\n    if (result) env->SetFloatArrayRegion(result, 0, 23, values);",
    "JNI rescue append",
)
write(p, s)

# Kotlin telemetry ----------------------------------------------------------
p = "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
s = read(p)
s = replace_once(
    s,
    "        val precisionGovernorConfidence: Float = 0f,\n        val frameJitterMs: Float = 0f\n    ) {",
    "        val precisionGovernorConfidence: Float = 0f,\n        val frameJitterMs: Float = 0f,\n        val audioRescues: Int = 0\n    ) {",
    "Kotlin telemetry field",
)
s = replace_once(
    s,
    "            precisionGovernorConfidence = raw.getOrElse(20) { 0f }.coerceIn(0f, 1f),\n            frameJitterMs = raw.getOrElse(21) { 0f }.coerceAtLeast(0f)\n        )",
    "            precisionGovernorConfidence = raw.getOrElse(20) { 0f }.coerceIn(0f, 1f),\n            frameJitterMs = raw.getOrElse(21) { 0f }.coerceAtLeast(0f),\n            audioRescues = raw.getOrElse(22) { 0f }.roundToInt()\n        )",
    "Kotlin rescue mapping",
)
write(p, s)

# Performance HUD: show hard underruns separately from successfully concealed
# starvation events.
p = "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt"
s = read(p)
old = '''            append(" ms • underruns ")
            append(t.audioUnderruns)
            append(" • sync ")'''
new = '''            append(" ms • underruns ")
            append(t.audioUnderruns)
            append(" • rescues ")
            append(t.audioRescues)
            append(" • sync ")'''
if old not in s:
    raise SystemExit("performance HUD audio marker not found")
s = s.replace(old, new)
write(p, s)

print("OmniCore 0.10.20 Alpha 21 ElasticAudioBridge migration applied")
