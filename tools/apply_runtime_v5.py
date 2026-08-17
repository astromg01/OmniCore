from pathlib import Path
import re

root = Path('.')
src = root / 'app/src/main/cpp/libretro_host_v4.cpp'
dst = root / 'app/src/main/cpp/libretro_host_v5.cpp'
text = src.read_text()

# 1) Keep audio latency bounded: ~93 ms max stereo @ 44.1 kHz.
text = text.replace('constexpr std::size_t kAudioRingSamples = 32768;', 'constexpr std::size_t kAudioRingSamples = 8192;')

# 2) Expose ring occupancy in samples for priming/clock control.
needle = '''    unsigned occupancyPercent() const {
        const std::uint64_t r = read_.load(std::memory_order_acquire);
        const std::uint64_t w = write_.load(std::memory_order_acquire);
        const std::uint64_t used = std::min<std::uint64_t>(capacity_, w - r);
        return static_cast<unsigned>((used * 100u) / std::max<std::uint64_t>(1, capacity_));
    }

    std::uint64_t underruns() const { return underruns_.load(std::memory_order_acquire); }
'''
replace = '''    unsigned occupancyPercent() const {
        const std::uint64_t r = read_.load(std::memory_order_acquire);
        const std::uint64_t w = write_.load(std::memory_order_acquire);
        const std::uint64_t used = std::min<std::uint64_t>(capacity_, w - r);
        return static_cast<unsigned>((used * 100u) / std::max<std::uint64_t>(1, capacity_));
    }

    std::size_t availableSamples() const {
        const std::uint64_t r = read_.load(std::memory_order_acquire);
        const std::uint64_t w = write_.load(std::memory_order_acquire);
        return static_cast<std::size_t>(std::min<std::uint64_t>(capacity_, w - r));
    }
    std::size_t capacitySamples() const { return capacity_; }
    std::uint64_t underruns() const { return underruns_.load(std::memory_order_acquire); }
    std::uint64_t overruns() const { return overruns_.load(std::memory_order_acquire); }
'''
if needle not in text:
    raise SystemExit('AudioRing insertion point not found')
text = text.replace(needle, replace)

# 3) Route all core audio through the runtime rate adapter.
old_callbacks = '''    static void audioSampleCallback(std::int16_t left, std::int16_t right) {
        if (!active_) return;
        const std::int16_t stereo[2] = {left, right};
        active_->audioRing_.push(stereo, 2);
    }
    static std::size_t audioBatchCallback(const std::int16_t* data, std::size_t frames) {
        if (!active_ || !data) return 0;
        return active_->audioRing_.push(data, frames * 2) / 2;
    }
'''
new_callbacks = '''    static void audioSampleCallback(std::int16_t left, std::int16_t right) {
        if (!active_) return;
        const std::int16_t stereo[2] = {left, right};
        active_->pushAudioFrames(stereo, 1);
    }
    static std::size_t audioBatchCallback(const std::int16_t* data, std::size_t frames) {
        if (!active_ || !data) return 0;
        active_->pushAudioFrames(data, frames);
        // PCSX-ReARMed treats this as a sink. Always report the input batch as
        // consumed so a transient Android buffer condition never stalls emulation.
        return frames;
    }
'''
if old_callbacks not in text:
    raise SystemExit('audio callback block not found')
text = text.replace(old_callbacks, new_callbacks)

# 4) Compatibility-first renderer. The zero-copy ANativeWindow path remains in
# the source for later opt-in testing, but it is not exposed to cores by default.
old_fb = '''            case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
                return acquireSoftwareFramebuffer(static_cast<retro_framebuffer*>(data));
'''
new_fb = '''            case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
                // Compatibility mode: use the core-owned framebuffer and copy at
                // video refresh. Direct Surface buffers can block the emulation
                // thread on some Android devices/providers, starving audio and
                // producing a black screen. Re-enable only after device validation.
                return false;
'''
if old_fb not in text:
    raise SystemExit('software framebuffer env block not found')
text = text.replace(old_fb, new_fb)

# 5) Count actual video presentation and retain the safe copy renderer.
text = text.replace('''        ANativeWindow_unlockAndPost(window_);
    }

    bool openAudio(double sampleRate) {''', '''        ANativeWindow_unlockAndPost(window_);
        presentedFrames_.fetch_add(1, std::memory_order_relaxed);
    }

    void pushAudioFrames(const std::int16_t* data, std::size_t frames) {
        if (!data || frames == 0) return;
        const double inRate = coreSampleRate_ > 1000.0 ? coreSampleRate_ : 44100.0;
        const double outRate = outputSampleRate_ > 1000 ? static_cast<double>(outputSampleRate_) : inRate;
        if (std::abs(outRate - inRate) < 1.0) {
            audioRing_.push(data, frames * 2);
            return;
        }

        // Lightweight duration-preserving rate adapter. It runs on the emulator
        // thread, never in AAudio's realtime callback. Android usually honours
        // 44.1 kHz, but this prevents pitch/speed drift when the device exposes
        // a different client rate.
        resampleScratch_.clear();
        const std::size_t estimate = static_cast<std::size_t>(frames * (outRate / inRate) + 4.0);
        if (resampleScratch_.capacity() < estimate * 2) resampleScratch_.reserve(estimate * 2);
        for (std::size_t i = 0; i < frames; ++i) {
            resampleAccumulator_ += outRate;
            while (resampleAccumulator_ >= inRate) {
                resampleScratch_.push_back(data[i * 2]);
                resampleScratch_.push_back(data[i * 2 + 1]);
                resampleAccumulator_ -= inRate;
            }
        }
        if (!resampleScratch_.empty()) audioRing_.push(resampleScratch_.data(), resampleScratch_.size());
    }

    bool openAudio(double sampleRate) {''')

# 6) Replace AAudio lifecycle with primed low-latency output and no empty-stream start.
pattern = re.compile(r'''    bool openAudio\(double sampleRate\) \{.*?\n    std::filesystem::path saveRamPath\(\) const''', re.S)
match = pattern.search(text)
if not match:
    raise SystemExit('openAudio..saveRamPath section not found')
new_audio = r'''    bool openAudio(double sampleRate) {
        closeAudio();
        audioRing_.clear();
        coreSampleRate_ = sampleRate > 1000.0 ? sampleRate : 44100.0;
        audioSampleRate_ = coreSampleRate_;
        resampleAccumulator_ = 0.0;
        const RuntimePerformanceConfig config = performanceSnapshot();

        auto openMode = [&](aaudio_sharing_mode_t sharing) {
            AAudioStreamBuilder* builder = nullptr;
            if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) return false;
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSharingMode(builder, sharing);
            // Games need deterministic callback cadence even in the sustainable
            // preset. Power policy is handled by ADPF, not a high-latency audio path.
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setSampleRate(builder, static_cast<std::int32_t>(coreSampleRate_));
            AAudioStreamBuilder_setDataCallback(builder, aaudioCallback, this);
            const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &audioStream_);
            AAudioStreamBuilder_delete(builder);
            if (result != AAUDIO_OK || !audioStream_) {
                audioStream_ = nullptr;
                return false;
            }

            outputSampleRate_ = std::max<std::int32_t>(1, AAudioStream_getSampleRate(audioStream_));
            const std::int32_t burst = AAudioStream_getFramesPerBurst(audioStream_);
            if (burst > 0) {
                int bursts = std::max(3, config.audioBufferBursts);
                if (minimumAudioLatencyMs_ > 0) {
                    const int requestedFrames = static_cast<int>(outputSampleRate_ * minimumAudioLatencyMs_ / 1000.0);
                    bursts = std::max(bursts, (requestedFrames + burst - 1) / burst);
                }
                appliedAudioBufferBursts_ = std::clamp(bursts, 3, 8);
                const int requested = burst * appliedAudioBufferBursts_;
                const int applied = AAudioStream_setBufferSizeInFrames(audioStream_, requested);
                if (applied > 0) appliedAudioBufferFrames_ = applied;
                else appliedAudioBufferFrames_ = requested;

                const int primeMsFrames = std::max(1, outputSampleRate_ / 40); // ~25 ms
                audioPrimeFrames_ = std::min<int>(
                    static_cast<int>(audioRing_.capacitySamples() / 2 / 2),
                    std::max(burst * 3, primeMsFrames));
            } else {
                appliedAudioBufferBursts_ = 4;
                appliedAudioBufferFrames_ = std::max(256, outputSampleRate_ / 50);
                audioPrimeFrames_ = std::max(256, outputSampleRate_ / 40);
            }

            lastXRunCount_ = std::max<std::int32_t>(0, AAudioStream_getXRunCount(audioStream_));
            lastRingUnderruns_ = audioRing_.underruns();
            lastRingOverruns_ = audioRing_.overruns();
            audioStarted_ = false;
            return true;
        };

        if (config.tryExclusiveAudio && openMode(AAUDIO_SHARING_MODE_EXCLUSIVE)) return true;
        return openMode(AAUDIO_SHARING_MODE_SHARED);
    }

    bool startAudioIfPrimed(bool force = false) {
        if (!audioStream_) return false;
        if (audioStarted_) return true;
        const std::size_t availableFrames = audioRing_.availableSamples() / 2;
        if (!force && availableFrames < static_cast<std::size_t>(std::max(1, audioPrimeFrames_))) return false;
        if (AAudioStream_requestStart(audioStream_) == AAUDIO_OK) {
            audioStarted_ = true;
            return true;
        }
        setStatus("RUNTIME E12 • falha ao iniciar saída AAudio");
        return false;
    }

    void closeAudio() {
        if (audioStream_) {
            if (audioStarted_) AAudioStream_requestStop(audioStream_);
            AAudioStream_close(audioStream_);
            audioStream_ = nullptr;
        }
        audioStarted_ = false;
        audioRing_.clear();
        appliedAudioBufferBursts_ = 0;
        appliedAudioBufferFrames_ = 0;
        audioPrimeFrames_ = 0;
        outputSampleRate_ = 0;
        resampleAccumulator_ = 0.0;
    }

    void adaptAudioBuffer() {
        if (!audioStream_) return;
        const int burst = AAudioStream_getFramesPerBurst(audioStream_);
        if (burst <= 0) return;
        const auto config = performanceSnapshot();
        const int xruns = std::max<std::int32_t>(0, AAudioStream_getXRunCount(audioStream_));
        const auto underruns = audioRing_.underruns();
        const auto overruns = audioRing_.overruns();
        int next = appliedAudioBufferBursts_ > 0 ? appliedAudioBufferBursts_ : std::max(3, config.audioBufferBursts);
        if (xruns > lastXRunCount_ || underruns > lastRingUnderruns_) {
            next = std::min(8, std::max(std::max(3, config.audioBufferBursts), next + 1));
            stableAudioChecks_ = 0;
        } else if (next > std::max(3, config.audioBufferBursts) && ++stableAudioChecks_ >= 10) {
            --next;
            stableAudioChecks_ = 0;
        }
        if (next != appliedAudioBufferBursts_) {
            const int applied = AAudioStream_setBufferSizeInFrames(audioStream_, burst * next);
            if (applied > 0) appliedAudioBufferFrames_ = applied;
            appliedAudioBufferBursts_ = next;
        }
        lastXRunCount_ = xruns;
        lastRingUnderruns_ = underruns;
        lastRingOverruns_ = overruns;
    }

    std::filesystem::path saveRamPath() const'''
text = text[:match.start()] + new_audio + text[match.end():]

# 7) Do not tear down AAudio on thermal/performance changes.
old_reconf = '''                if (audioReconfigureRequested_.exchange(false, std::memory_order_acq_rel)) {
                    const auto changed = performanceSnapshot();
                    hint.apply(performanceTargetNanos(fps_, changed), changed.preferPowerEfficiency);
                    if (audioSampleRate_ > 1000.0) openAudio(audioSampleRate_);
                }
'''
new_reconf = '''                if (audioReconfigureRequested_.exchange(false, std::memory_order_acq_rel)) {
                    const auto changed = performanceSnapshot();
                    hint.apply(performanceTargetNanos(fps_, changed), changed.preferPowerEfficiency);
                    // Keep the live audio stream. Reopening it on every thermal
                    // policy transition creates gaps and can sound like slow audio.
                    adaptAudioBuffer();
                }
'''
if old_reconf not in text:
    raise SystemExit('audio reconfigure block not found')
text = text.replace(old_reconf, new_reconf)

# 8) Replace fixed wall-clock pacing with hybrid frame/audio clocking + telemetry.
old_loop_tail = '''                const auto workStart = clock::now();
                api.run();
                if (directLocked_) releaseDirectFramebuffer(true);
                const auto workEnd = clock::now();
                hint.report(std::chrono::duration_cast<std::chrono::nanoseconds>(workEnd - workStart).count());

                if (audioStatusCallback_) {
                    const auto nowUnderruns = audioRing_.underruns();
                    audioStatusCallback_(audioStream_ != nullptr, audioRing_.occupancyPercent(), nowUnderruns > audioStatusUnderruns);
                    audioStatusUnderruns = nowUnderruns;
                }

                ++frames;
                if (frames % autosaveFrames == 0) saveSaveRam(api);
                if (frames % tuneFrames == 0) adaptAudioBuffer();

                nextFrame += frameStep;
                const auto now = clock::now();
                if (nextFrame > now) {
                    std::this_thread::sleep_until(nextFrame);
                } else if (now - nextFrame > std::chrono::milliseconds(250)) {
                    nextFrame = now;
                }
'''
new_loop_tail = '''                const auto workStart = clock::now();
                api.run();
                if (directLocked_) releaseDirectFramebuffer(true);
                const auto workEnd = clock::now();
                hint.report(std::chrono::duration_cast<std::chrono::nanoseconds>(workEnd - workStart).count());

                ++frames;
                // Prime AAudio with real emulated samples before its realtime
                // callback starts consuming. This removes the startup underrun
                // cascade seen on slower Android devices.
                const bool audioReady = startAudioIfPrimed(frames >= 4);

                if (audioStatusCallback_) {
                    const auto nowUnderruns = audioRing_.underruns();
                    audioStatusCallback_(audioReady, audioRing_.occupancyPercent(), nowUnderruns > audioStatusUnderruns);
                    audioStatusUnderruns = nowUnderruns;
                }

                if (frames % autosaveFrames == 0) saveSaveRam(api);
                if (frames % tuneFrames == 0) adaptAudioBuffer();

                if (frames % static_cast<std::uint64_t>(std::max(30.0, fps_ * 2.0)) == 0) {
                    const auto now = clock::now();
                    const double seconds = std::chrono::duration<double>(now - telemetryStart_).count();
                    const std::uint64_t presented = presentedFrames_.load(std::memory_order_relaxed);
                    const std::uint64_t deltaFrames = frames - telemetryEmuFrames_;
                    const double hostFps = seconds > 0.0 ? static_cast<double>(deltaFrames) / seconds : 0.0;
                    const unsigned audioOcc = audioRing_.occupancyPercent();
                    const auto underruns = audioRing_.underruns();
                    if (frames > static_cast<std::uint64_t>(fps_ * 1.5) && presented == 0) {
                        setStatus("RUNTIME E10 • core executa, mas nenhum frame chegou à Surface");
                    } else if (underruns > telemetryUnderruns_ + 8) {
                        setStatus("RUNTIME W11 • áudio instável • " + std::to_string(audioOcc) + "% buffer");
                    } else {
                        char line[180]{};
                        std::snprintf(line, sizeof(line), "RUN OK • %.1f/%.1f fps • vídeo %llu • áudio %u%% • u%llu/o%llu",
                                      hostFps, fps_, static_cast<unsigned long long>(presented), audioOcc,
                                      static_cast<unsigned long long>(underruns),
                                      static_cast<unsigned long long>(audioRing_.overruns()));
                        setStatus(line);
                    }
                    telemetryStart_ = now;
                    telemetryEmuFrames_ = frames;
                    telemetryUnderruns_ = underruns;
                }

                nextFrame += frameStep;
                const auto now = clock::now();
                // Audio occupancy provides a small drift correction while the
                // nominal libretro FPS remains authoritative for game speed.
                const int occupancyError = static_cast<int>(audioRing_.occupancyPercent()) - 45;
                const auto correction = std::chrono::microseconds(std::clamp(occupancyError * 24, -900, 900));
                const auto sleepTarget = nextFrame + correction;
                if (!audioReady) {
                    // During priming, run without sleeping for at most a few
                    // frames so playback starts with a healthy reservoir.
                    nextFrame = now;
                } else if (sleepTarget > now) {
                    std::this_thread::sleep_until(sleepTarget);
                } else if (now - nextFrame > frameStep * 4) {
                    // Never accumulate seconds of timing debt after a pause or
                    // one unusually expensive frame.
                    nextFrame = now;
                }
'''
if old_loop_tail not in text:
    raise SystemExit('run loop pacing block not found')
text = text.replace(old_loop_tail, new_loop_tail)

# 9) Telemetry epoch must begin immediately before the emulation loop.
text = text.replace('''            auto nextFrame = clock::now();
            std::uint64_t frames = 0;''', '''            auto nextFrame = clock::now();
            telemetryStart_ = nextFrame;
            telemetryEmuFrames_ = 0;
            telemetryUnderruns_ = audioRing_.underruns();
            presentedFrames_.store(0, std::memory_order_relaxed);
            std::uint64_t frames = 0;''')

# 10) Add runtime audio/video state members.
member_needle = '''    AudioRing audioRing_;
    AAudioStream* audioStream_ = nullptr;
    double audioSampleRate_ = 0.0;
    int appliedAudioBufferBursts_ = 0;
    int lastXRunCount_ = 0;
    std::uint64_t lastRingUnderruns_ = 0;
    int stableAudioChecks_ = 0;
    unsigned minimumAudioLatencyMs_ = 0;
    retro_audio_buffer_status_callback_t audioStatusCallback_ = nullptr;
'''
member_replace = '''    AudioRing audioRing_;
    AAudioStream* audioStream_ = nullptr;
    double audioSampleRate_ = 0.0;
    double coreSampleRate_ = 44100.0;
    std::int32_t outputSampleRate_ = 0;
    bool audioStarted_ = false;
    int audioPrimeFrames_ = 0;
    int appliedAudioBufferBursts_ = 0;
    int appliedAudioBufferFrames_ = 0;
    int lastXRunCount_ = 0;
    std::uint64_t lastRingUnderruns_ = 0;
    std::uint64_t lastRingOverruns_ = 0;
    int stableAudioChecks_ = 0;
    unsigned minimumAudioLatencyMs_ = 0;
    retro_audio_buffer_status_callback_t audioStatusCallback_ = nullptr;
    double resampleAccumulator_ = 0.0;
    std::vector<std::int16_t> resampleScratch_;

    std::atomic<std::uint64_t> presentedFrames_{0};
    std::chrono::steady_clock::time_point telemetryStart_{};
    std::uint64_t telemetryEmuFrames_ = 0;
    std::uint64_t telemetryUnderruns_ = 0;
'''
if member_needle not in text:
    raise SystemExit('audio member block not found')
text = text.replace(member_needle, member_replace)

dst.write_text(text)

# CMake switches atomically to runtime v5 while retaining v4 as rollback source.
cmake = root / 'app/src/main/cpp/CMakeLists.txt'
c = cmake.read_text().replace('libretro_host_v4.cpp', 'libretro_host_v5.cpp')
cmake.write_text(c)

# Correct PCSX-ReARMed core option values and make compatibility the default.
settings = root / 'app/src/main/java/com/omnicore/emulator/settings/Ps1Settings.kt'
s = settings.read_text()
s = s.replace('''            add("pcsx_rearmed_gpu_thread_rendering=${if (threadedGpu) "enabled" else "disabled"}")''', '''            add("pcsx_rearmed_gpu_thread_rendering=${if (threadedGpu) (if (preset == Preset.PERFORMANCE) "async" else "sync") else "disabled"}")''')
s = s.replace('''            add("pcsx_rearmed_region=auto")
            add("pcsx_rearmed_memcard1=libretro")''', '''            add("pcsx_rearmed_region=auto")
            add("pcsx_rearmed_psxclock=auto")
            add("pcsx_rearmed_cd_turbo=disabled")
            add("pcsx_rearmed_nostalls=disabled")
            add("pcsx_rearmed_icache_emulation=enabled")
            add("pcsx_rearmed_exception_emulation=disabled")
            add("pcsx_rearmed_gpu_slow_llists=auto")
            add("pcsx_rearmed_fractional_framerate=auto")
            add("pcsx_rearmed_neon_interlace_enable_v2=auto")
            add("pcsx_rearmed_noxadecoding=disabled")
            add("pcsx_rearmed_nocdaudio=disabled")
            add("pcsx_rearmed_show_bios_bootlogo=disabled")
            add("pcsx_rearmed_memcard1=libretro")''')
settings.write_text(s)

# BIOS health detector. We validate without distributing firmware.
bios = root / 'app/src/main/java/com/omnicore/emulator/storage/Ps1BiosHealth.kt'
bios.write_text('''package com.omnicore.emulator.storage

import java.io.File
import java.security.MessageDigest

data class Ps1BiosStatus(
    val hasCandidate: Boolean,
    val verifiedRetail: Boolean,
    val fileName: String?,
    val shortLabel: String,
    val detail: String
)

object Ps1BiosHealth {
    private val knownMd5 = mapOf(
        "c53ca5908936d412331790f4426c6c33" to "PSXONPSP660.bin",
        "6e3735ff4c7dc899ee98981385f6f3d0" to "scph101.bin",
        "1e68c231d0896b7eadcad1d7d8e76129" to "scph7001.bin",
        "490f666e1afb15b7362b406ed1cea246" to "scph5501.bin",
        "924e392ed05558ffdb115408c263dccf" to "scph1001.bin"
    )

    fun inspect(systemDir: File): Ps1BiosStatus {
        val candidates = systemDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("bin", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        if (candidates.isEmpty()) {
            return Ps1BiosStatus(false, false, null, "HLE", "Nenhuma BIOS de PS1 importada; usando HLE do core.")
        }
        for (file in candidates) {
            val md5 = runCatching { md5(file) }.getOrNull() ?: continue
            val canonical = knownMd5[md5]
            if (canonical != null) {
                return Ps1BiosStatus(true, true, file.name, "BIOS OK", "BIOS retail verificada: ${file.name}")
            }
        }
        val first = candidates.first()
        return Ps1BiosStatus(true, false, first.name, "BIOS ?", "BIOS encontrada, mas o hash não corresponde ao conjunto retail conhecido.")
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
''')

# Surface runtime telemetry and BIOS state in the emulation HUD.
activity = root / 'app/src/main/java/com/omnicore/emulator/emulation/EmulationActivity.kt'
a = activity.read_text()
a = a.replace('import com.omnicore.emulator.storage.Ps1Files', 'import com.omnicore.emulator.storage.Ps1Files\nimport com.omnicore.emulator.storage.Ps1BiosHealth')
a = a.replace('''    private var lastRuntimeMessage = ""
''', '''    private var lastRuntimeMessage = ""
    private var biosLabel = "HLE"
''')
a = a.replace('''                    if (text.startsWith("BOOT 6/6")) {
                        successfulPolls++
                        if (successfulPolls >= 7) statusView.visibility = View.GONE
                    } else if (text.startsWith("BOOT E")) {
                        statusView.visibility = View.VISIBLE
                        statusView.setBackgroundColor(Color.argb(225, 90, 15, 28))
                    }
''', '''                    if (text.startsWith("BOOT 6/6") || text.startsWith("RUN OK")) {
                        successfulPolls++
                        if (successfulPolls >= 7) statusView.visibility = View.GONE
                    } else if (text.startsWith("BOOT E") || text.startsWith("RUNTIME E")) {
                        statusView.visibility = View.VISIBLE
                        statusView.setBackgroundColor(Color.argb(225, 90, 15, 28))
                    } else if (text.startsWith("RUNTIME W")) {
                        statusView.visibility = View.VISIBLE
                        statusView.setBackgroundColor(Color.argb(220, 88, 58, 8))
                    }
''')
a = a.replace('''        ps1Config = Ps1Settings.resolve(this)
        registerThermalAdaptation()
''', '''        ps1Config = Ps1Settings.resolve(this)
        val biosHealth = Ps1BiosHealth.inspect(Ps1Files.systemDir(this))
        biosLabel = biosHealth.shortLabel
        registerThermalAdaptation()
''')
a = a.replace('''            text = "${ps1Config.preset.label} • ${if (ps1Config.dualShock) "DualShock" else "Digital"}"
''', '''            text = "${ps1Config.preset.label} • ${if (ps1Config.dualShock) "DualShock" else "Digital"} • $biosLabel"
''')
activity.write_text(a)

# Version bump: this is a runtime milestone, not a patch.
gradle = root / 'app/build.gradle.kts'
g = gradle.read_text().replace('versionCode = 5', 'versionCode = 6').replace('versionName = "0.3.1"', 'versionName = "0.4.0"')
gradle.write_text(g)

changelog = root / 'CHANGELOG.md'
old = changelog.read_text()
entry = '''# OmniCore 0.4.0 — Runtime Foundation\n\n- Compatibility-first Android video path; experimental direct Surface framebuffer disabled by default.\n- Primed AAudio output, bounded ring latency, live buffer tuning and sample-rate adaptation.\n- Hybrid libretro FPS/audio clock pacing with runtime video/audio telemetry.\n- Fixed PCSX-ReARMed threaded-rendering option values (`sync`/`async`) and safer compatibility defaults.\n- BIOS health detection (verified retail / unknown / HLE) without bundling firmware.\n- Runtime no longer reopens AAudio during thermal policy transitions.\n- Runtime v4 retained in source as a rollback reference; app builds with v5.\n\n'''
changelog.write_text(entry + old)

print('runtime v5 migration prepared')
