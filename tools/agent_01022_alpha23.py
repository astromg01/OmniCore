from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app/build.gradle.kts"
HOST = ROOT / "app/src/main/cpp/n64/n64_libretro_host.cpp"
BRIDGE = ROOT / "app/src/main/cpp/n64/n64_native_bridge.cpp"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, 'versionCode = 37', 'versionCode = 38', 'versionCode')
gradle = replace_once(gradle, 'versionName = "0.10.21"', 'versionName = "0.10.22"', 'versionName')
GRADLE.write_text(gradle, encoding="utf-8")

host = HOST.read_text(encoding="utf-8")

host = replace_once(
    host,
    "    double resampleAccumulator = 0.0;\n    std::array<std::int16_t, 8192> resampleScratch{};",
    "    // Alpha 23 SmoothAudioResampler: keep one continuous source timeline so\n"
    "    // tiny pacing corrections never duplicate/drop whole PCM samples.\n"
    "    double audioSyncScaleSmoothed = 1.0;\n"
    "    double resampleNextOutputPos = 0.0;\n"
    "    std::uint64_t resampleInputFramesSeen = 0;\n"
    "    std::int16_t resamplePrevLeft = 0;\n"
    "    std::int16_t resamplePrevRight = 0;\n"
    "    bool resampleHavePrev = false;\n"
    "    std::array<std::int16_t, 8192> resampleScratch{};",
    "resampler state fields",
)

reset_block = (
    "audioSyncScaleSmoothed = 1.0;\n"
    "        resampleNextOutputPos = 0.0;\n"
    "        resampleInputFramesSeen = 0;\n"
    "        resamplePrevLeft = 0;\n"
    "        resamplePrevRight = 0;\n"
    "        resampleHavePrev = false;"
)
reset_count = host.count("resampleAccumulator = 0.0;")
if reset_count < 3:
    raise SystemExit(f"resampler reset: expected at least 3 assignments, found {reset_count}")
host = host.replace("resampleAccumulator = 0.0;", reset_block)

sync_start = host.index("    double audioSyncScale() {")
push_start = host.index("    void pushAudio(", sync_start)
adapt_start = host.index("    void adaptAudio(", push_start)

new_audio_path = r'''    double audioSyncScale() {
        if (!audioStream || outputSampleRate <= 0) return 1.0;
        updateAudioTelemetry();
        const float fillMs = owner ? owner->audioFillMs_.load(std::memory_order_acquire) : 0.0f;
        const float bufferMs = owner ? owner->audioBufferMs_.load(std::memory_order_acquire) : 0.0f;
        const float steadyTargetFillMs = std::max(42.0f, bufferMs * 1.65f);
        const bool transitionShield = transitionAudioShieldActive();
        const float targetFillMs = transitionShield
            ? std::max(steadyTargetFillMs, 76.0f)
            : steadyTargetFillMs;

        // SyncSlew: keep the existing reserve policy, but never jump instantly
        // between correction ratios. Dense N64 mixes make abrupt sample-rate
        // steps much easier to hear than simple music or ambience.
        double targetScale = 1.0;
        if (transitionShield && fillMs < targetFillMs * 0.42f) targetScale = 1.0180;
        else if (transitionShield && fillMs < targetFillMs * 0.68f) targetScale = 1.0120;
        else if (transitionShield && fillMs < targetFillMs * 0.90f) targetScale = 1.0065;
        else if (fillMs < targetFillMs * 0.55f) targetScale = 1.0075;
        else if (fillMs < targetFillMs * 0.80f) targetScale = 1.0035;
        else if (fillMs > targetFillMs * 1.75f) targetScale = 0.9945;
        else if (fillMs > targetFillMs * 1.40f) targetScale = 0.9975;

        const bool criticallyLow = fillMs < targetFillMs * 0.42f;
        const double maxStep = criticallyLow ? 0.0035 : (transitionShield ? 0.0020 : 0.0012);
        const double delta = targetScale - audioSyncScaleSmoothed;
        if (std::abs(delta) <= maxStep) {
            audioSyncScaleSmoothed = targetScale;
        } else {
            audioSyncScaleSmoothed += std::copysign(maxStep, delta);
        }
        if (std::abs(targetScale - 1.0) < 0.000001 &&
            std::abs(audioSyncScaleSmoothed - 1.0) < 0.0006) {
            audioSyncScaleSmoothed = 1.0;
        }
        audioSyncScaleSmoothed = std::clamp(audioSyncScaleSmoothed, 0.9945, 1.0180);
        if (owner) {
            owner->pacingCorrectionPct_.store(
                static_cast<float>((audioSyncScaleSmoothed - 1.0) * 100.0),
                std::memory_order_release);
        }
        return audioSyncScaleSmoothed;
    }

    void pushAudio(const std::int16_t* data, std::size_t frames) {
        if (!data || frames == 0 || !audioStream) return;
        const double syncScale = audioSyncScale();
        const double desiredOutRate =
            static_cast<double>(std::max(1, outputSampleRate)) * syncScale;
        const double inputRate = std::max(1.0, coreSampleRate);
        const double sourceStep = inputRate / std::max(1.0, desiredOutRate);

        std::size_t scratchCount = 0;
        auto flush = [&]() {
            if (scratchCount > 0) {
                audioRing.push(resampleScratch.data(), scratchCount);
                scratchCount = 0;
            }
        };
        auto append = [&](double left, double right) {
            if (scratchCount + 2 > resampleScratch.size()) flush();
            const auto clamp16 = [](double sample) -> std::int16_t {
                return static_cast<std::int16_t>(std::lround(
                    std::clamp(sample, -32768.0, 32767.0)));
            };
            resampleScratch[scratchCount++] = clamp16(left);
            resampleScratch[scratchCount++] = clamp16(right);
        };

        // Continuous streaming linear interpolation. At exactly 1.0x this is
        // effectively bit-transparent; when SyncSlew asks for a tiny correction,
        // output positions slide between adjacent source frames instead of
        // duplicating or deleting an entire PCM frame.
        for (std::size_t i = 0; i < frames; ++i) {
            const std::int16_t currentLeft = data[i * 2u];
            const std::int16_t currentRight = data[i * 2u + 1u];
            if (!resampleHavePrev) {
                resamplePrevLeft = currentLeft;
                resamplePrevRight = currentRight;
                resampleHavePrev = true;
                if (resampleInputFramesSeen == 0 && resampleNextOutputPos <= 0.0) {
                    append(currentLeft, currentRight);
                    resampleNextOutputPos += sourceStep;
                }
                ++resampleInputFramesSeen;
                continue;
            }

            const double segmentEnd = static_cast<double>(resampleInputFramesSeen);
            const double segmentStart = segmentEnd - 1.0;
            while (resampleNextOutputPos <= segmentEnd + 1.0e-9) {
                if (resampleNextOutputPos < segmentStart) {
                    resampleNextOutputPos = segmentStart;
                }
                const double t = std::clamp(
                    resampleNextOutputPos - segmentStart, 0.0, 1.0);
                const double left = static_cast<double>(resamplePrevLeft) +
                    (static_cast<double>(currentLeft) - static_cast<double>(resamplePrevLeft)) * t;
                const double right = static_cast<double>(resamplePrevRight) +
                    (static_cast<double>(currentRight) - static_cast<double>(resamplePrevRight)) * t;
                append(left, right);
                resampleNextOutputPos += sourceStep;
            }
            resamplePrevLeft = currentLeft;
            resamplePrevRight = currentRight;
            ++resampleInputFramesSeen;
        }
        flush();
        updateAudioTelemetry();
    }

'''

host = host[:sync_start] + new_audio_path + host[adapt_start:]
if "resampleAccumulator" in host:
    raise SystemExit("legacy nearest-neighbor resampleAccumulator still present")
if "SmoothAudioResampler" not in host or "SyncSlew" not in host:
    raise SystemExit("Alpha 23 audio markers missing")
HOST.write_text(host, encoding="utf-8")

bridge = BRIDGE.read_text(encoding="utf-8")
bridge = replace_once(bridge, "OmniCore N64 Runtime 0.10.21", "OmniCore N64 Runtime 0.10.22", "runtime version")
bridge = replace_once(bridge, "AAudio host v18", "AAudio host v19", "AAudio host version")
bridge = replace_once(
    bridge,
    "StartupAudioGate + ElasticAudioBridge + TransitionAudioShield",
    "StartupAudioGate + SmoothAudioResampler + SyncSlew + ElasticAudioBridge + TransitionAudioShield",
    "runtime audio feature string",
)
BRIDGE.write_text(bridge, encoding="utf-8")

print("OmniCore 0.10.22 Alpha 23 SmoothAudioResampler + SyncSlew migration applied")
