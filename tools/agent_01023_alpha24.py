from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Version --------------------------------------------------------------------
build = read("app/build.gradle.kts")
build = replace_once(build, 'versionCode = 38', 'versionCode = 39', 'versionCode')
build = replace_once(build, 'versionName = "0.10.22"', 'versionName = "0.10.23"', 'versionName')
write("app/build.gradle.kts", build)

# N64-only native link dependency -------------------------------------------
cmake = read("app/src/main/cpp/CMakeLists.txt")
cmake = replace_once(
    cmake,
    'find_library(aaudio-lib aaudio)\nfind_library(dl-lib dl)',
    'find_library(aaudio-lib aaudio)\nfind_library(opensles-lib OpenSLES)\nfind_library(dl-lib dl)',
    'OpenSL find_library',
)
cmake = replace_once(
    cmake,
    '    ${aaudio-lib}\n    ${dl-lib}\n    ${egl-lib}\n    ${glesv3-lib}\n)',
    '    ${aaudio-lib}\n    ${opensles-lib}\n    ${dl-lib}\n    ${egl-lib}\n    ${glesv3-lib}\n)',
    'OpenSL N64 link',
)
write("app/src/main/cpp/CMakeLists.txt", cmake)

host_path = "app/src/main/cpp/n64/n64_libretro_host.cpp"
host = read(host_path)
host = replace_once(
    host,
    '#include <aaudio/AAudio.h>\n#include <android/log.h>',
    '#include <aaudio/AAudio.h>\n#include <SLES/OpenSLES.h>\n#include <SLES/OpenSLES_Android.h>\n#include <android/log.h>',
    'OpenSL includes',
)

# RacingComfort v2: preserve full range and remove the old 0.96 cap. The new
# curve is continuous, softer near center and reaches exactly 1.0 at the rim.
old_racing = '''    if (profile == "racing") {
        // RacingComfort: Mario Kart benefits from a wider fine-steering zone and
        // a near-neutral effective default sensitivity. Full steering remains
        // reachable at the rim, so this improves control without capping range.
        constexpr float kRacingFineZone = 0.42f;
        if (normalized < kRacingFineZone) {
            const float local = normalized / kRacingFineZone;
            normalized = std::pow(local, 1.16f) * kRacingFineZone;
        }
        normalized = std::min(1.0f, normalized / 0.995f);
        normalized *= userSensitivity * 0.96f;
    } else {
'''
new_racing = '''    if (profile == "racing") {
        // RacingComfort v2: Mario Kart steering needs a calm center but full
        // N64 stick range at the rim. The old profile multiplied the final
        // magnitude by 0.96, unintentionally capping maximum steering. Use one
        // continuous curve instead: ~22% softer at the center, progressively
        // linear toward the outside and exactly full-scale at 1.0.
        constexpr float kRacingCenterGain = 0.78f;
        normalized = normalized *
            (kRacingCenterGain + (1.0f - kRacingCenterGain) * normalized);
        normalized *= userSensitivity;
    } else {
'''
host = replace_once(host, old_racing, new_racing, 'RacingComfort v2 curve')
host = replace_once(
    host,
    '''    normalized = std::clamp(normalized, 0.0f, 1.0f);
    return {x / magnitude * normalized, y / magnitude * normalized};
}''',
    '''    normalized = std::clamp(normalized, 0.0f, 1.0f);
    float outputX = x / magnitude * normalized;
    float outputY = y / magnitude * normalized;
    if (profile == "racing") {
        // Touch sticks can leak a little vertical noise while the player is
        // steering horizontally. Suppress only that tiny non-dominant component;
        // intentional vertical input and diagonal/menu movement remain available.
        if (std::abs(outputX) < 0.035f) outputX = 0.0f;
        if (std::abs(outputX) > 0.10f && std::abs(outputY) < 0.22f &&
            std::abs(outputX) > std::abs(outputY) * 1.20f) {
            outputY *= 0.30f;
        }
    }
    return {
        std::clamp(outputX, -1.0f, 1.0f),
        std::clamp(outputY, -1.0f, 1.0f)
    };
}''',
    'RacingComfort v2 axis stabilization',
)

# AudioBackend Auto state -----------------------------------------------------
host = replace_once(
    host,
    '''    AudioRing audioRing;
    AAudioStream* audioStream = nullptr;
    bool audioStarted = false;
''',
    '''    enum class AudioBackend : int {
        NONE = 0,
        AAUDIO_SHARED = 1,
        AAUDIO_EXCLUSIVE = 2,
        OPENSL = 3,
    };
    static constexpr int kOpenSlQueueBuffers = 4;

    AudioRing audioRing;
    AAudioStream* audioStream = nullptr;
    SLObjectItf slEngineObject = nullptr;
    SLEngineItf slEngine = nullptr;
    SLObjectItf slOutputMixObject = nullptr;
    SLObjectItf slPlayerObject = nullptr;
    SLPlayItf slPlay = nullptr;
    SLAndroidSimpleBufferQueueItf slBufferQueue = nullptr;
    std::array<std::array<std::int16_t, 1024>, kOpenSlQueueBuffers> slBuffers{};
    int slBufferFrames = 0;
    int slNextBuffer = 0;
    AudioBackend audioBackend = AudioBackend::NONE;
    bool audioStarted = false;
    bool audioFallbackUsed = false;
    std::atomic<int> aaudioLastError{0};
    std::chrono::steady_clock::time_point audioHealthWindowStarted{};
    int audioHealthHardBaseline = 0;
    int audioHealthXrunBaseline = 0;
''',
    'AudioBackend state',
)

# Move concealment into one backend-neutral function so both AAudio and OpenSL
# consume exactly the same ring/rescue path.
callback_start = host.index('    static aaudio_data_callback_result_t audioCallback(')
callback_end = host.index('    bool createEgl(', callback_start)
new_callbacks = r'''    void renderAudioFrames(std::int16_t* output, std::size_t requestedFrames) {
        if (!output || requestedFrames == 0u) return;
        const std::size_t requestedSamples = requestedFrames * 2u;
        const std::size_t availableFrames = audioRing.availableSamples() / 2u;
        std::size_t producedSamples = 0;
        bool rescued = false;
        bool hardUnderrun = false;

        if (availableFrames >= requestedFrames) {
            producedSamples = audioRing.pop(output, requestedSamples);
            consecutiveStarvedCallbacks = 0;
        } else {
            audioRing.noteUnderrun();
            ++consecutiveStarvedCallbacks;

            const std::size_t minimumElasticFrames = std::max<std::size_t>(
                8u, (requestedFrames * 70u + 99u) / 100u);
            if (availableFrames >= minimumElasticFrames && requestedFrames > 1u) {
                const std::size_t desiredConsume = std::max<std::size_t>(
                    8u, (requestedFrames * 82u + 99u) / 100u);
                const std::size_t consumeFrames = std::min(availableFrames, desiredConsume);
                producedSamples = audioRing.pop(output, consumeFrames * 2u);
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
                producedSamples = audioRing.pop(output, requestedSamples);
                const std::size_t missingFrames = (requestedSamples - producedSamples) / 2u;
                const std::size_t historyFrames = callbackHistorySamples / 2u;
                const std::int16_t seamLeft = producedSamples >= 2u
                    ? output[producedSamples - 2u] : lastAudioLeft;
                const std::int16_t seamRight = producedSamples >= 2u
                    ? output[producedSamples - 1u] : lastAudioRight;

                for (std::size_t frame = 0; frame < missingFrames; ++frame) {
                    std::int16_t sourceLeft = seamLeft;
                    std::int16_t sourceRight = seamRight;
                    if (historyFrames > 0u) {
                        const std::size_t replayCount = std::min(historyFrames, std::max<std::size_t>(1u, missingFrames));
                        const std::size_t replayStart = historyFrames - replayCount;
                        const std::size_t src = replayStart + (frame % replayCount);
                        sourceLeft = callbackHistory[src * 2u];
                        sourceRight = callbackHistory[src * 2u + 1u];
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
                if (callbackHistorySamples >= 2u && consecutiveStarvedCallbacks <= 3) rescued = true;
                else hardUnderrun = true;
            }
        }

        if (requestedSamples >= 2u) {
            lastAudioLeft = output[requestedSamples - 2u];
            lastAudioRight = output[requestedSamples - 1u];
            const std::size_t keep = std::min(requestedSamples, callbackHistory.size());
            std::memcpy(
                callbackHistory.data(),
                output + (requestedSamples - keep),
                keep * sizeof(std::int16_t));
            callbackHistorySamples = keep;
        }
        if (owner) {
            if (rescued) owner->audioRescues_.fetch_add(1, std::memory_order_relaxed);
            if (hardUnderrun) owner->audioUnderruns_.fetch_add(1, std::memory_order_relaxed);
        }
    }

    static aaudio_data_callback_result_t audioCallback(
        AAudioStream*, void* userData, void* audioData, std::int32_t numFrames) {
        auto* self = static_cast<Impl*>(userData);
        if (!self || !audioData || numFrames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
        self->renderAudioFrames(
            static_cast<std::int16_t*>(audioData),
            static_cast<std::size_t>(numFrames));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void audioErrorCallback(AAudioStream*, void* userData, aaudio_result_t error) {
        auto* self = static_cast<Impl*>(userData);
        if (self) self->aaudioLastError.store(static_cast<int>(error), std::memory_order_release);
    }

    static void openSlBufferQueueCallback(SLAndroidSimpleBufferQueueItf queue, void* userData) {
        auto* self = static_cast<Impl*>(userData);
        if (!self || !queue || !self->audioStarted || self->slBufferFrames <= 0) return;
        const int slot = self->slNextBuffer;
        auto& buffer = self->slBuffers[static_cast<std::size_t>(slot)];
        self->renderAudioFrames(buffer.data(), static_cast<std::size_t>(self->slBufferFrames));
        const SLresult result = (*queue)->Enqueue(
            queue,
            buffer.data(),
            static_cast<SLuint32>(self->slBufferFrames * 2 * sizeof(std::int16_t)));
        if (result == SL_RESULT_SUCCESS) {
            self->slNextBuffer = (slot + 1) % kOpenSlQueueBuffers;
        }
    }

'''
host = host[:callback_start] + new_callbacks + host[callback_end:]

# Backend label is useful both in runtime breadcrumbs and telemetry UI.
host = replace_once(
    host,
    '''    bool transitionAudioShieldActive() const {
        return std::chrono::steady_clock::now() < transitionAudioShieldUntil;
    }

    void updateAudioTelemetry() {''',
    '''    bool transitionAudioShieldActive() const {
        return std::chrono::steady_clock::now() < transitionAudioShieldUntil;
    }

    const char* audioBackendLabel() const {
        switch (audioBackend) {
            case AudioBackend::AAUDIO_SHARED: return "AA-SH";
            case AudioBackend::AAUDIO_EXCLUSIVE: return "AA-EX";
            case AudioBackend::OPENSL: return "OpenSL";
            default: return "OFF";
        }
    }

    void updateAudioTelemetry() {''',
    'audio backend label',
)

# Replace the AAudio-only open path with AudioBackend Auto. Shared low-latency is
# preferred for vendor stability; exclusive is a secondary AAudio attempt; the
# native OpenSL queue is the compatibility fallback.
open_start = host.index('    bool openAudio(double sampleRate, int requestedBursts) {')
open_end = host.index('    void closeAudio() {', open_start)
new_open = r'''    void resetAudioPipelineState(double sampleRate) {
        audioRing.clear();
        callbackHistorySamples = 0;
        consecutiveStarvedCallbacks = 0;
        audioPrimeStableFrames = 0;
        coreSampleRate = sampleRate > 1000.0 ? sampleRate : 44100.0;
        audioSyncScaleSmoothed = 1.0;
        resampleNextOutputPos = 0.0;
        resampleInputFramesSeen = 0;
        resamplePrevLeft = 0;
        resamplePrevRight = 0;
        resampleHavePrev = false;
        aaudioLastError.store(0, std::memory_order_release);
        audioHealthWindowStarted = {};
        audioHealthHardBaseline = owner
            ? owner->audioUnderruns_.load(std::memory_order_acquire) : 0;
        audioHealthXrunBaseline = 0;
    }

    void configureStartupPrime() {
        const int startupFloorFrames = std::max(1, outputSampleRate * 90 / 1000);
        const int startupCeilingFrames = std::max(startupFloorFrames, outputSampleRate * 120 / 1000);
        const int deviceSafetyFrames = std::max(
            framesPerBurst * 8, audioBufferFrames + framesPerBurst * 4);
        audioPrimeFrames = std::clamp(
            std::max(startupFloorFrames, deviceSafetyFrames),
            startupFloorFrames,
            std::min(startupCeilingFrames, static_cast<int>(audioRing.capacitySamples() / 2u)));
        armTransitionAudioShield(std::chrono::milliseconds(6000));
        stableAudioChecks = 0;
        audioStarted = false;
        audioPrimeStableFrames = 0;
        audioHealthWindowStarted = std::chrono::steady_clock::now();
        audioHealthHardBaseline = owner
            ? owner->audioUnderruns_.load(std::memory_order_acquire) : 0;
        updateAudioTelemetry();
    }

    bool openAAudioMode(aaudio_sharing_mode_t sharing, AudioBackend mode, int requestedBursts) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) return false;
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, sharing);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);
        AAudioStreamBuilder_setErrorCallback(builder, audioErrorCallback, this);
        const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &audioStream);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK || !audioStream) {
            audioStream = nullptr;
            return false;
        }
        outputSampleRate = std::max(1, AAudioStream_getSampleRate(audioStream));
        framesPerBurst = std::max(1, AAudioStream_getFramesPerBurst(audioStream));
        int minBursts = std::clamp(requestedBursts, 2, 8);
        if (minimumAudioLatencyMs > 0) {
            const int latencyFrames = outputSampleRate * minimumAudioLatencyMs / 1000;
            minBursts = std::max(minBursts, (latencyFrames + framesPerBurst - 1) / framesPerBurst);
        }
        appliedAudioBursts = std::clamp(minBursts, 2, 8);
        const int requestedFrames = framesPerBurst * appliedAudioBursts;
        const int appliedFrames = AAudioStream_setBufferSizeInFrames(audioStream, requestedFrames);
        audioBufferFrames = appliedFrames > 0 ? appliedFrames : requestedFrames;
        audioBackend = mode;
        if (owner) owner->audioBackendMode_.store(static_cast<int>(audioBackend), std::memory_order_release);
        configureStartupPrime();
        lastXRunCount = std::max(0, AAudioStream_getXRunCount(audioStream));
        audioHealthXrunBaseline = lastXRunCount;
        lastRingUnderruns = audioRing.underruns();
        logPrint(ANDROID_LOG_INFO, "AudioBackend Auto opened %s @ %d Hz / burst %d",
                 audioBackendLabel(), outputSampleRate, framesPerBurst);
        return true;
    }

    bool openOpenSLES(int requestedBursts) {
        outputSampleRate = 48000;
        slBufferFrames = std::clamp(outputSampleRate / 100, 192, 512); // ~10 ms
        framesPerBurst = slBufferFrames;
        appliedAudioBursts = std::clamp(std::max(requestedBursts, 4), 4, 8);

        if (slCreateEngine(&slEngineObject, 0, nullptr, 0, nullptr, nullptr) != SL_RESULT_SUCCESS ||
            !slEngineObject) return false;
        if ((*slEngineObject)->Realize(slEngineObject, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS ||
            (*slEngineObject)->GetInterface(slEngineObject, SL_IID_ENGINE, &slEngine) != SL_RESULT_SUCCESS ||
            !slEngine) return false;
        if ((*slEngine)->CreateOutputMix(slEngine, &slOutputMixObject, 0, nullptr, nullptr) != SL_RESULT_SUCCESS ||
            !slOutputMixObject ||
            (*slOutputMixObject)->Realize(slOutputMixObject, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return false;

        SLDataLocator_AndroidSimpleBufferQueue sourceLocator = {
            SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
            static_cast<SLuint32>(kOpenSlQueueBuffers)
        };
        SLDataFormat_PCM format = {
            SL_DATAFORMAT_PCM,
            2,
            SL_SAMPLINGRATE_48,
            SL_PCMSAMPLEFORMAT_FIXED_16,
            SL_PCMSAMPLEFORMAT_FIXED_16,
            SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
            SL_BYTEORDER_LITTLEENDIAN
        };
        SLDataSource source = {&sourceLocator, &format};
        SLDataLocator_OutputMix sinkLocator = {SL_DATALOCATOR_OUTPUTMIX, slOutputMixObject};
        SLDataSink sink = {&sinkLocator, nullptr};
        const SLInterfaceID ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
        const SLboolean required[] = {SL_BOOLEAN_TRUE};
        if ((*slEngine)->CreateAudioPlayer(
                slEngine, &slPlayerObject, &source, &sink, 1, ids, required) != SL_RESULT_SUCCESS ||
            !slPlayerObject ||
            (*slPlayerObject)->Realize(slPlayerObject, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS ||
            (*slPlayerObject)->GetInterface(slPlayerObject, SL_IID_PLAY, &slPlay) != SL_RESULT_SUCCESS ||
            (*slPlayerObject)->GetInterface(
                slPlayerObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &slBufferQueue) != SL_RESULT_SUCCESS ||
            !slPlay || !slBufferQueue) return false;
        if ((*slBufferQueue)->RegisterCallback(
                slBufferQueue, openSlBufferQueueCallback, this) != SL_RESULT_SUCCESS) return false;

        slNextBuffer = 0;
        audioBufferFrames = slBufferFrames * kOpenSlQueueBuffers;
        audioBackend = AudioBackend::OPENSL;
        if (owner) owner->audioBackendMode_.store(static_cast<int>(audioBackend), std::memory_order_release);
        configureStartupPrime();
        lastRingUnderruns = audioRing.underruns();
        logPrint(ANDROID_LOG_INFO, "AudioBackend Auto opened OpenSL fallback @ %d Hz / queue %dx%d",
                 outputSampleRate, kOpenSlQueueBuffers, slBufferFrames);
        return true;
    }

    bool openAudio(double sampleRate, int requestedBursts) {
        closeAudio();
        audioFallbackUsed = false;
        resetAudioPipelineState(sampleRate);
        requestedBursts = std::clamp(requestedBursts, 2, 8);

        // Compatibility-first order: Shared AAudio avoids vendor-exclusive path
        // quirks while retaining the modern low-latency callback. Exclusive is
        // still attempted if Shared cannot open, then OpenSL handles devices or
        // drivers where AAudio is unavailable/unhealthy.
        if (openAAudioMode(AAUDIO_SHARING_MODE_SHARED, AudioBackend::AAUDIO_SHARED, requestedBursts)) return true;
        if (openAAudioMode(AAUDIO_SHARING_MODE_EXCLUSIVE, AudioBackend::AAUDIO_EXCLUSIVE, requestedBursts)) return true;
        return openOpenSLES(requestedBursts);
    }

    bool fallbackToOpenSLES(int requestedBursts, const char* reason) {
        if (audioBackend == AudioBackend::OPENSL) return true;
        const double savedCoreRate = coreSampleRate;
        logPrint(ANDROID_LOG_WARN, "AudioHealthWatch switching %s -> OpenSL (%s)",
                 audioBackendLabel(), reason ? reason : "health");
        closeAudio();
        resetAudioPipelineState(savedCoreRate);
        audioFallbackUsed = true;
        return openOpenSLES(requestedBursts);
    }

'''
host = host[:open_start] + new_open + host[open_end:]

# Close both backend families cleanly.
close_start = host.index('    void closeAudio() {')
close_end = host.index('    void reprimeAudio() {', close_start)
new_close = r'''    void closeAudio() {
        if (audioStream) {
            if (audioStarted) AAudioStream_requestStop(audioStream);
            AAudioStream_close(audioStream);
            audioStream = nullptr;
        }
        if (slPlay) (*slPlay)->SetPlayState(slPlay, SL_PLAYSTATE_STOPPED);
        if (slBufferQueue) (*slBufferQueue)->Clear(slBufferQueue);
        if (slPlayerObject) (*slPlayerObject)->Destroy(slPlayerObject);
        if (slOutputMixObject) (*slOutputMixObject)->Destroy(slOutputMixObject);
        if (slEngineObject) (*slEngineObject)->Destroy(slEngineObject);
        slPlayerObject = nullptr;
        slPlay = nullptr;
        slBufferQueue = nullptr;
        slOutputMixObject = nullptr;
        slEngine = nullptr;
        slEngineObject = nullptr;
        slBufferFrames = 0;
        slNextBuffer = 0;
        audioBackend = AudioBackend::NONE;
        if (owner) owner->audioBackendMode_.store(0, std::memory_order_release);
        audioStarted = false;
        framesPerBurst = 0;
        audioBufferFrames = 0;
        audioPrimeFrames = 0;
        audioPrimeStableFrames = 0;
        audioSyncScaleSmoothed = 1.0;
        resampleNextOutputPos = 0.0;
        resampleInputFramesSeen = 0;
        resamplePrevLeft = 0;
        resamplePrevRight = 0;
        resampleHavePrev = false;
        audioRing.clear();
        if (owner) {
            owner->audioFillMs_.store(0.0f, std::memory_order_release);
            owner->audioBufferMs_.store(0.0f, std::memory_order_release);
            owner->pacingCorrectionPct_.store(0.0f, std::memory_order_release);
        }
    }

'''
host = host[:close_start] + new_close + host[close_end:]

# Reprime/start work at the same complete libretro-frame boundary for both APIs.
reprime_start = host.index('    void reprimeAudio() {')
reprime_end = host.index('    void startAudioIfReady() {', reprime_start)
new_reprime = r'''    void reprimeAudio() {
        if (audioBackend == AudioBackend::AAUDIO_SHARED ||
            audioBackend == AudioBackend::AAUDIO_EXCLUSIVE) {
            if (audioStream && audioStarted) AAudioStream_requestPause(audioStream);
        } else if (audioBackend == AudioBackend::OPENSL) {
            if (slPlay) (*slPlay)->SetPlayState(slPlay, SL_PLAYSTATE_STOPPED);
            if (slBufferQueue) (*slBufferQueue)->Clear(slBufferQueue);
            slNextBuffer = 0;
        }
        audioStarted = false;
        audioRing.clear();
        callbackHistorySamples = 0;
        consecutiveStarvedCallbacks = 0;
        audioPrimeStableFrames = 0;
        audioSyncScaleSmoothed = 1.0;
        resampleNextOutputPos = 0.0;
        resampleInputFramesSeen = 0;
        resamplePrevLeft = 0;
        resamplePrevRight = 0;
        resampleHavePrev = false;
        lastRingUnderruns = 0;
        stableAudioChecks = 0;
        audioHealthWindowStarted = std::chrono::steady_clock::now();
        audioHealthHardBaseline = owner
            ? owner->audioUnderruns_.load(std::memory_order_acquire) : 0;
        armTransitionAudioShield(std::chrono::milliseconds(2200));
        updateAudioTelemetry();
    }

'''
host = host[:reprime_start] + new_reprime + host[reprime_end:]

start_start = host.index('    void startAudioIfReady() {')
start_end = host.index('    double audioSyncScale() {', start_start)
new_start = r'''    void startAudioIfReady() {
        if (audioBackend == AudioBackend::NONE || audioStarted) return;
        const std::size_t availableFrames = audioRing.availableSamples() / 2u;
        if (availableFrames < static_cast<std::size_t>(std::max(1, audioPrimeFrames))) {
            audioPrimeStableFrames = 0;
            return;
        }
        if (++audioPrimeStableFrames < 2) return;

        bool startedNow = false;
        if (audioBackend == AudioBackend::AAUDIO_SHARED ||
            audioBackend == AudioBackend::AAUDIO_EXCLUSIVE) {
            startedNow = audioStream && AAudioStream_requestStart(audioStream) == AAUDIO_OK;
        } else if (audioBackend == AudioBackend::OPENSL && slPlay && slBufferQueue) {
            (*slBufferQueue)->Clear(slBufferQueue);
            slNextBuffer = 0;
            startedNow = true;
            for (int slot = 0; slot < kOpenSlQueueBuffers; ++slot) {
                auto& buffer = slBuffers[static_cast<std::size_t>(slot)];
                renderAudioFrames(buffer.data(), static_cast<std::size_t>(slBufferFrames));
                if ((*slBufferQueue)->Enqueue(
                        slBufferQueue,
                        buffer.data(),
                        static_cast<SLuint32>(slBufferFrames * 2 * sizeof(std::int16_t))) != SL_RESULT_SUCCESS) {
                    startedNow = false;
                    break;
                }
            }
            slNextBuffer = 0;
            if (startedNow) {
                startedNow = (*slPlay)->SetPlayState(slPlay, SL_PLAYSTATE_PLAYING) == SL_RESULT_SUCCESS;
            }
        }
        if (startedNow) {
            audioStarted = true;
            audioPrimeStableFrames = 0;
            audioHealthWindowStarted = std::chrono::steady_clock::now();
            audioHealthHardBaseline = owner
                ? owner->audioUnderruns_.load(std::memory_order_acquire) : 0;
            audioHealthXrunBaseline = audioStream
                ? std::max(0, AAudioStream_getXRunCount(audioStream)) : 0;
            logPrint(ANDROID_LOG_INFO, "StartupAudioGate opened %s with %zu frames queued (target=%d)",
                     audioBackendLabel(), availableFrames, audioPrimeFrames);
        }
    }

'''
host = host[:start_start] + new_start + host[start_end:]

host = replace_once(
    host,
    '        if (!audioStream || outputSampleRate <= 0) return 1.0;',
    '        if (audioBackend == AudioBackend::NONE || outputSampleRate <= 0) return 1.0;',
    'audio sync backend guard',
)
host = replace_once(
    host,
    '        if (!data || frames == 0 || !audioStream) return;',
    '        if (!data || frames == 0 || audioBackend == AudioBackend::NONE) return;',
    'pushAudio backend guard',
)

# AAudio remains dynamically size-adjustable. OpenSL uses a fixed four-buffer
# queue. HealthWatch judges hard audible failures rather than rescued microgaps;
# an unhealthy AAudio session can downgrade once to OpenSL without oscillation.
adapt_start = host.index('    void adaptAudio(int requestedBursts) {')
adapt_end = host.index('\n};\n\nLibretroHost& LibretroHost::instance()', adapt_start)
new_adapt = r'''    void adaptAudio(int requestedBursts) {
        if (audioBackend == AudioBackend::NONE || framesPerBurst <= 0) return;
        requestedBursts = std::clamp(requestedBursts, 2, 8);
        if (transitionAudioShieldActive()) requestedBursts = std::max(requestedBursts, 7);

        if (audioBackend == AudioBackend::OPENSL) {
            updateAudioTelemetry();
            return;
        }
        if (!audioStream) return;

        const int xruns = std::max(0, AAudioStream_getXRunCount(audioStream));
        const auto underruns = audioRing.underruns();
        int next = appliedAudioBursts;
        if (xruns > lastXRunCount || underruns > lastRingUnderruns) {
            next = std::min(8, std::max(requestedBursts, appliedAudioBursts + 2));
            stableAudioChecks = 0;
        } else if (next < requestedBursts) {
            next = requestedBursts;
            stableAudioChecks = 0;
        } else if (next > requestedBursts && ++stableAudioChecks >= 24) {
            --next;
            stableAudioChecks = 0;
        }
        if (next != appliedAudioBursts) {
            const int requestedFrames = framesPerBurst * next;
            const int appliedFrames = AAudioStream_setBufferSizeInFrames(audioStream, requestedFrames);
            audioBufferFrames = appliedFrames > 0 ? appliedFrames : requestedFrames;
            appliedAudioBursts = next;
        }

        const int hardUnderruns = owner
            ? owner->audioUnderruns_.load(std::memory_order_acquire) : 0;
        const int error = aaudioLastError.exchange(0, std::memory_order_acq_rel);
        const auto now = std::chrono::steady_clock::now();
        if (audioHealthWindowStarted.time_since_epoch().count() == 0) {
            audioHealthWindowStarted = now;
            audioHealthHardBaseline = hardUnderruns;
            audioHealthXrunBaseline = xruns;
        }
        const auto healthAge = now - audioHealthWindowStarted;
        const int hardDelta = std::max(0, hardUnderruns - audioHealthHardBaseline);
        const int xrunDelta = std::max(0, xruns - audioHealthXrunBaseline);
        if (!audioFallbackUsed && audioStarted && healthAge >= std::chrono::milliseconds(2500) &&
            (error != 0 || hardDelta >= 8 || xrunDelta >= 3)) {
            const char* reason = error != 0 ? "AAudio error" :
                (xrunDelta >= 3 ? "AAudio xruns" : "audible underruns");
            if (fallbackToOpenSLES(std::max(requestedBursts, 6), reason)) {
                updateAudioTelemetry();
                return;
            }
        }
        if (healthAge >= std::chrono::seconds(8)) {
            audioHealthWindowStarted = now;
            audioHealthHardBaseline = hardUnderruns;
            audioHealthXrunBaseline = xruns;
        }

        lastXRunCount = xruns;
        lastRingUnderruns = underruns;
        updateAudioTelemetry();
    }
'''
host = host[:adapt_start] + new_adapt + host[adapt_end:]

# Runtime state/telemetry and messages ---------------------------------------
host = replace_once(
    host,
    '    audioRescues_.store(0, std::memory_order_release);\n    audioFillMs_.store(0.0f, std::memory_order_release);',
    '    audioRescues_.store(0, std::memory_order_release);\n    audioBackendMode_.store(0, std::memory_order_release);\n    audioFillMs_.store(0.0f, std::memory_order_release);',
    'audio backend reset',
)
host = replace_once(
    host,
    '    setMessage("N64 BOOT 1/6 • Alpha 22 StartupAudioGate + ElasticAudioBridge…");',
    '    setMessage("N64 BOOT 1/6 • Alpha 24 AudioBackend Auto + RacingComfort v2…");',
    'boot message',
)
host = replace_once(
    host,
    '    out.audioRescues = audioRescues_.load(std::memory_order_acquire);\n    out.audioFillMs = audioFillMs_.load(std::memory_order_acquire);',
    '    out.audioRescues = audioRescues_.load(std::memory_order_acquire);\n    out.audioBackendMode = static_cast<float>(audioBackendMode_.load(std::memory_order_acquire));\n    out.audioFillMs = audioFillMs_.load(std::memory_order_acquire);',
    'telemetry backend mode',
)
old_run_ok = '''    if (impl_->presentedFrames == 1) {
        setMessage(impl_->audioStream
            ? (impl_->directPresent
                ? "N64 RUN OK • DirectPresenter GLES3 • AAudio nativo pronto"
                : "N64 RUN OK • RenderBridge fallback GLES3 • AAudio nativo pronto")
            : (impl_->directPresent
                ? "N64 RUN OK • DirectPresenter GLES3 • áudio indisponível"
                : "N64 RUN OK • RenderBridge fallback GLES3 • áudio indisponível"));
    }
'''
new_run_ok = '''    if (impl_->presentedFrames == 1) {
        std::string message = impl_->directPresent
            ? "N64 RUN OK • DirectPresenter GLES3"
            : "N64 RUN OK • RenderBridge fallback GLES3";
        message += " • áudio ";
        message += impl_->audioBackendLabel();
        message += impl_->audioBackend == Impl::AudioBackend::NONE ? " indisponível" : " pronto";
        setMessage(std::move(message));
    }
'''
host = replace_once(host, old_run_ok, new_run_ok, 'RUN OK audio backend message')
host = host.replace(
    'N64 AAudio unavailable; continuing without audio output',
    'N64 AudioBackend Auto unavailable; continuing without audio output',
)
write(host_path, host)

# Header telemetry ------------------------------------------------------------
header_path = "app/src/main/cpp/n64/n64_libretro_host.h"
header = read(header_path)
header = replace_once(
    header,
    '    int audioRescues = 0;\n};',
    '    int audioRescues = 0;\n    float audioBackendMode = 0.0f;\n};',
    'Telemetry backend field',
)
header = replace_once(
    header,
    '    std::atomic<int> audioRescues_{0};\n    std::atomic<float> targetFrameMs_',
    '    std::atomic<int> audioRescues_{0};\n    std::atomic<int> audioBackendMode_{0};\n    std::atomic<float> targetFrameMs_',
    'backend atomic',
)
write(header_path, header)

# JNI telemetry and runtime identity -----------------------------------------
bridge_path = "app/src/main/cpp/n64/n64_native_bridge.cpp"
bridge = read(bridge_path)
bridge = replace_once(
    bridge,
    'OmniCore N64 Runtime 0.10.22 • Mupen64Plus-Next • GLES3 + AAudio host v19 • StartupAudioGate + SmoothAudioResampler + SyncSlew + ElasticAudioBridge + TransitionAudioShield + PrecisionGovernor v2.1 + RacingComfort + MicroBurstShield + CruiseGuard + PassiveWarmCache + DirectPresenter',
    'OmniCore N64 Runtime 0.10.23 • Mupen64Plus-Next • GLES3 + AudioBackend Auto host v20 • AAudio Shared/Exclusive + OpenSL fallback + AudioHealthWatch + StartupAudioGate + SmoothAudioResampler + SyncSlew + ElasticAudioBridge + TransitionAudioShield + PrecisionGovernor v2.1 + RacingComfort v2 + MicroBurstShield + CruiseGuard + PassiveWarmCache + DirectPresenter',
    'runtime info',
)
bridge = replace_once(bridge, 'const jfloat values[23] = {', 'const jfloat values[24] = {', 'JNI array size')
bridge = replace_once(
    bridge,
    '        static_cast<jfloat>(telemetry.audioRescues)\n    };',
    '        static_cast<jfloat>(telemetry.audioRescues),\n        telemetry.audioBackendMode\n    };',
    'JNI backend value',
)
bridge = replace_once(
    bridge,
    'jfloatArray result = env->NewFloatArray(23);\n    if (result) env->SetFloatArrayRegion(result, 0, 23, values);',
    'jfloatArray result = env->NewFloatArray(24);\n    if (result) env->SetFloatArrayRegion(result, 0, 24, values);',
    'JNI telemetry length',
)
write(bridge_path, bridge)

# Kotlin telemetry/HUD --------------------------------------------------------
kbridge_path = "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
kbridge = read(kbridge_path)
kbridge = replace_once(
    kbridge,
    '        val frameJitterMs: Float = 0f,\n        val audioRescues: Int = 0\n    ) {',
    '        val frameJitterMs: Float = 0f,\n        val audioRescues: Int = 0,\n        val audioBackendMode: Int = 0\n    ) {',
    'Kotlin telemetry field',
)
kbridge = replace_once(
    kbridge,
    '            frameJitterMs = raw.getOrElse(21) { 0f }.coerceAtLeast(0f),\n            audioRescues = raw.getOrElse(22) { 0f }.roundToInt()\n        )',
    '            frameJitterMs = raw.getOrElse(21) { 0f }.coerceAtLeast(0f),\n            audioRescues = raw.getOrElse(22) { 0f }.roundToInt(),\n            audioBackendMode = raw.getOrElse(23) { 0f }.roundToInt()\n        )',
    'Kotlin telemetry mapping',
)
write(kbridge_path, kbridge)

activity_path = "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt"
activity = read(activity_path)
activity = replace_once(
    activity,
    '            append(" ms • underruns ")\n            append(t.audioUnderruns)',
    '            append(" ms • backend ")\n            append(when (t.audioBackendMode) {\n                1 -> "AA-SH"\n                2 -> "AA-EX"\n                3 -> "OpenSL"\n                else -> "OFF"\n            })\n            append(" • underruns ")\n            append(t.audioUnderruns)',
    'performance backend label',
)
activity = replace_once(
    activity,
    'statusView.text = "N64 • ${decision.level.name} / ${decision.effective.cpuMode.label} / GLES3 + AAudio…"',
    'statusView.text = "N64 • ${decision.level.name} / ${decision.effective.cpuMode.label} / GLES3 + Audio Auto…"',
    'startup backend label',
)
write(activity_path, activity)

print("OmniCore 0.10.23 Alpha 24 migration applied: AudioBackend Auto + AudioHealthWatch + RacingComfort v2")
