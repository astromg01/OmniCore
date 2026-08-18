from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:90]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/build.gradle.kts",
    '        versionCode = 36\n        versionName = "0.10.20"',
    '        versionCode = 37\n        versionName = "0.10.21"',
)

host = "app/src/main/cpp/n64/n64_libretro_host.cpp"

replace_once(
    host,
    "    int consecutiveStarvedCallbacks = 0;\n",
    "    int consecutiveStarvedCallbacks = 0;\n"
    "    int audioPrimeStableFrames = 0;\n",
)

replace_once(
    host,
    "        callbackHistorySamples = 0;\n"
    "        consecutiveStarvedCallbacks = 0;\n"
    "        coreSampleRate = sampleRate > 1000.0 ? sampleRate : 44100.0;",
    "        callbackHistorySamples = 0;\n"
    "        consecutiveStarvedCallbacks = 0;\n"
    "        audioPrimeStableFrames = 0;\n"
    "        coreSampleRate = sampleRate > 1000.0 ? sampleRate : 44100.0;",
)

replace_once(
    host,
    "            // Start with enough PCM queued to survive scheduler jitter, then let\n"
    "            // SmartPerf reduce latency only after the stream proves stable.\n"
    "            audioPrimeFrames = std::min<int>(\n"
    "                static_cast<int>(audioRing.capacitySamples() / 4u),\n"
    "                std::max(framesPerBurst * 6, outputSampleRate / 20));\n",
    "            // StartupAudioGate: Alpha 21 started AAudio around 50 ms while the\n"
    "            // transition controller immediately targeted ~76 ms. That meant the\n"
    "            // stream could begin already below its own safe reserve. Prime to a\n"
    "            // bounded ~90 ms floor (or enough for the actual device buffer plus\n"
    "            // four bursts) before the callback is allowed to consume anything.\n"
    "            const int startupFloorFrames = std::max(1, outputSampleRate * 90 / 1000);\n"
    "            const int startupCeilingFrames = std::max(startupFloorFrames, outputSampleRate * 120 / 1000);\n"
    "            const int deviceSafetyFrames = std::max(\n"
    "                framesPerBurst * 8, audioBufferFrames + framesPerBurst * 4);\n"
    "            audioPrimeFrames = std::clamp(\n"
    "                std::max(startupFloorFrames, deviceSafetyFrames),\n"
    "                startupFloorFrames,\n"
    "                std::min(startupCeilingFrames, static_cast<int>(audioRing.capacitySamples() / 2u)));\n",
)

replace_once(
    host,
    "        audioPrimeFrames = 0;\n"
    "        resampleAccumulator = 0.0;",
    "        audioPrimeFrames = 0;\n"
    "        audioPrimeStableFrames = 0;\n"
    "        resampleAccumulator = 0.0;",
)

replace_once(
    host,
    "        callbackHistorySamples = 0;\n"
    "        consecutiveStarvedCallbacks = 0;\n"
    "        resampleAccumulator = 0.0;",
    "        callbackHistorySamples = 0;\n"
    "        consecutiveStarvedCallbacks = 0;\n"
    "        audioPrimeStableFrames = 0;\n"
    "        resampleAccumulator = 0.0;",
)

replace_once(
    host,
    "    void startAudioIfReady() {\n"
    "        if (!audioStream || audioStarted) return;\n"
    "        if (audioRing.availableSamples() / 2u < static_cast<std::size_t>(std::max(1, audioPrimeFrames))) return;\n"
    "        if (AAudioStream_requestStart(audioStream) == AAUDIO_OK) audioStarted = true;\n"
    "    }",
    "    void startAudioIfReady() {\n"
    "        if (!audioStream || audioStarted) return;\n"
    "        const std::size_t availableFrames = audioRing.availableSamples() / 2u;\n"
    "        if (availableFrames < static_cast<std::size_t>(std::max(1, audioPrimeFrames))) {\n"
    "            audioPrimeStableFrames = 0;\n"
    "            return;\n"
    "        }\n"
    "        // Only open the real-time consumer at an emulation frame boundary.\n"
    "        // Requiring two complete frames above the threshold prevents a single\n"
    "        // unusually large libretro audio batch from starting AAudio mid-batch.\n"
    "        if (++audioPrimeStableFrames < 2) return;\n"
    "        if (AAudioStream_requestStart(audioStream) == AAUDIO_OK) {\n"
    "            audioStarted = true;\n"
    "            audioPrimeStableFrames = 0;\n"
    "            logPrint(ANDROID_LOG_INFO, \"StartupAudioGate opened with %zu frames queued (target=%d)\",\n"
    "                     availableFrames, audioPrimeFrames);\n"
    "        }\n"
    "    }",
)

replace_once(
    host,
    "            audioRing.push(data, frames * 2u);\n"
    "            startAudioIfReady();\n"
    "            updateAudioTelemetry();",
    "            audioRing.push(data, frames * 2u);\n"
    "            updateAudioTelemetry();",
)

replace_once(
    host,
    "        flush();\n"
    "        startAudioIfReady();\n"
    "        updateAudioTelemetry();\n"
    "    }\n\n"
    "    void adaptAudio",
    "        flush();\n"
    "        updateAudioTelemetry();\n"
    "    }\n\n"
    "    void adaptAudio",
)

replace_once(
    host,
    "        impl_->core.run();\n"
    "        const auto afterRun = std::chrono::steady_clock::now();",
    "        impl_->core.run();\n"
    "        // StartupAudioGate is evaluated only after the whole libretro frame has\n"
    "        // delivered its PCM. This keeps AAudio from racing a partially produced\n"
    "        // first/menu frame and also applies after pause/load-state reprimes.\n"
    "        impl_->startAudioIfReady();\n"
    "        const auto afterRun = std::chrono::steady_clock::now();",
)

replace_once(
    host,
    '    setMessage("N64 BOOT 1/6 • Alpha 21 TransitionAudioShield + RacingComfort…");',
    '    setMessage("N64 BOOT 1/6 • Alpha 22 StartupAudioGate + ElasticAudioBridge…");',
)

replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    'OmniCore N64 Runtime 0.10.20 • Mupen64Plus-Next • GLES3 + AAudio host v17 • ElasticAudioBridge + TransitionAudioShield + PrecisionGovernor v2.1 + RacingComfort + MicroBurstShield + CruiseGuard + PassiveWarmCache + DirectPresenter',
    'OmniCore N64 Runtime 0.10.21 • Mupen64Plus-Next • GLES3 + AAudio host v18 • StartupAudioGate + ElasticAudioBridge + TransitionAudioShield + PrecisionGovernor v2.1 + RacingComfort + MicroBurstShield + CruiseGuard + PassiveWarmCache + DirectPresenter',
)

print("OmniCore 0.10.21 Alpha 22 StartupAudioGate migration applied")
