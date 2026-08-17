from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "app/src/main/cpp"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise RuntimeError(f"{label}: start marker not found")
    b = text.find(end, a)
    if b < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:a] + replacement + text[b:]


src = (CPP / "libretro_host_v5.cpp").read_text()
v6 = src
v6 = replace_once(v6, "#include <chrono>\n#include <cmath>\n", "#include <chrono>\n#include <cmath>\n#include <condition_variable>\n", "condition_variable include")
v6 = replace_once(v6, "constexpr std::size_t kAudioRingSamples = 8192;", "constexpr std::size_t kAudioRingSamples = 16384;", "audio ring")
v6 = replace_once(
    v6,
    "    ~Impl() {\n        stop();\n        releaseDirectFramebuffer(false);\n        if (window_) {",
    "    ~Impl() {\n        stop();\n        stopRenderThread();\n        if (window_) {",
    "destructor",
)
v6 = replace_once(
    v6,
    "    void stop() {\n        stopRequested_.store(true, std::memory_order_release);\n        if (worker_.joinable()) worker_.join();\n        running_.store(false, std::memory_order_release);\n    }",
    "    void stop() {\n        stopRequested_.store(true, std::memory_order_release);\n        renderStop_.store(true, std::memory_order_release);\n        frameCv_.notify_all();\n        if (worker_.joinable()) worker_.join();\n        stopRenderThread();\n        running_.store(false, std::memory_order_release);\n    }",
    "stop lifecycle",
)

render_section = r'''    void startRenderThread() {
        if (renderWorker_.joinable()) return;
        renderStop_.store(false, std::memory_order_release);
        renderWorker_ = std::thread([this] { renderLoop(); });
    }

    void stopRenderThread() {
        renderStop_.store(true, std::memory_order_release);
        frameCv_.notify_all();
        if (renderWorker_.joinable() && renderWorker_.get_id() != std::this_thread::get_id()) {
            renderWorker_.join();
        }
    }

    static bool frameHasVisibleSignal(const std::uint8_t* data, unsigned width, unsigned height,
                                      std::size_t pitch, retro_pixel_format format) {
        if (!data || width == 0 || height == 0) return false;
        const unsigned stepY = std::max(1u, height / 12u);
        const unsigned stepX = std::max(1u, width / 16u);
        for (unsigned y = 0; y < height; y += stepY) {
            const auto* row = data + static_cast<std::size_t>(y) * pitch;
            for (unsigned x = 0; x < width; x += stepX) {
                if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
                    std::uint32_t p = 0;
                    std::memcpy(&p, row + static_cast<std::size_t>(x) * 4u, sizeof(p));
                    if ((p & 0x00FFFFFFu) != 0) return true;
                } else {
                    std::uint16_t p = 0;
                    std::memcpy(&p, row + static_cast<std::size_t>(x) * 2u, sizeof(p));
                    if ((p & (format == RETRO_PIXEL_FORMAT_0RGB1555 ? 0x7FFFu : 0xFFFFu)) != 0) return true;
                }
            }
        }
        return false;
    }

    void renderFrame(const void* data, unsigned width, unsigned height, std::size_t pitch) {
        // Critical runtime-v6 rule: libretro's emulation thread NEVER touches
        // ANativeWindow. It only snapshots the core-owned frame into a mailbox.
        // If the renderer is briefly copying metadata, drop this visual frame
        // instead of stalling retro_run() and starving audio.
        if (!data || data == reinterpret_cast<const void*>(static_cast<std::intptr_t>(-1)) ||
            width == 0 || height == 0) return;

        const std::size_t bpp = pixelFormat_ == RETRO_PIXEL_FORMAT_XRGB8888 ? 4u : 2u;
        const std::size_t rowBytes = static_cast<std::size_t>(width) * bpp;
        if (pitch < rowBytes) {
            malformedFrames_.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        producedFrames_.fetch_add(1, std::memory_order_relaxed);
        if (frameHasVisibleSignal(static_cast<const std::uint8_t*>(data), width, height, pitch, pixelFormat_)) {
            nonBlackFrames_.fetch_add(1, std::memory_order_relaxed);
        }

        std::unique_lock<std::mutex> lock(frameMutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            droppedFrames_.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        pendingFrame_.resize(rowBytes * static_cast<std::size_t>(height));
        const auto* src = static_cast<const std::uint8_t*>(data);
        for (unsigned y = 0; y < height; ++y) {
            std::memcpy(pendingFrame_.data() + static_cast<std::size_t>(y) * rowBytes,
                        src + static_cast<std::size_t>(y) * pitch,
                        rowBytes);
        }
        pendingWidth_ = width;
        pendingHeight_ = height;
        pendingPitch_ = rowBytes;
        pendingFormat_ = pixelFormat_;
        ++pendingSerial_;
        lock.unlock();
        frameCv_.notify_one();
    }

    void renderLoop() {
        std::vector<std::uint8_t> local;
        std::uint64_t seenSerial = 0;
        while (!renderStop_.load(std::memory_order_acquire)) {
            unsigned width = 0;
            unsigned height = 0;
            std::size_t pitch = 0;
            retro_pixel_format format = RETRO_PIXEL_FORMAT_RGB565;
            {
                std::unique_lock<std::mutex> lock(frameMutex_);
                frameCv_.wait_for(lock, std::chrono::milliseconds(25), [&] {
                    return renderStop_.load(std::memory_order_acquire) || pendingSerial_ != seenSerial;
                });
                if (renderStop_.load(std::memory_order_acquire)) break;
                if (pendingSerial_ == seenSerial || pendingFrame_.empty()) continue;
                local = pendingFrame_;
                width = pendingWidth_;
                height = pendingHeight_;
                pitch = pendingPitch_;
                format = pendingFormat_;
                seenSerial = pendingSerial_;
            }
            presentFrame(local.data(), width, height, pitch, format);
        }
    }

    void presentFrame(const std::uint8_t* data, unsigned width, unsigned height,
                      std::size_t pitch, retro_pixel_format format) {
        if (!window_ || !data || width == 0 || height == 0) return;
        const int desired = format == RETRO_PIXEL_FORMAT_RGB565
            ? WINDOW_FORMAT_RGB_565 : WINDOW_FORMAT_RGBA_8888;

        if (renderWidth_ != width || renderHeight_ != height || renderFormat_ != desired) {
            if (ANativeWindow_setBuffersGeometry(window_, static_cast<int>(width),
                                                 static_cast<int>(height), desired) != 0) {
                surfaceFailures_.fetch_add(1, std::memory_order_relaxed);
                return;
            }
            renderWidth_ = width;
            renderHeight_ = height;
            renderFormat_ = desired;
            windowHints_.allocate(window_);
        }

        ANativeWindow_Buffer buffer{};
        if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
            surfaceFailures_.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        const unsigned drawWidth = std::min(width, static_cast<unsigned>(buffer.width));
        const unsigned drawHeight = std::min(height, static_cast<unsigned>(buffer.height));
        auto* dstBase = static_cast<std::uint8_t*>(buffer.bits);

        if (format == RETRO_PIXEL_FORMAT_RGB565 && buffer.format == WINDOW_FORMAT_RGB_565) {
            const std::size_t copyBytes = static_cast<std::size_t>(drawWidth) * 2u;
            for (unsigned y = 0; y < drawHeight; ++y) {
                std::memcpy(dstBase + static_cast<std::size_t>(y) * buffer.stride * 2u,
                            data + static_cast<std::size_t>(y) * pitch,
                            copyBytes);
            }
        } else if (buffer.format == WINDOW_FORMAT_RGBA_8888 && format == RETRO_PIXEL_FORMAT_XRGB8888) {
            const std::size_t copyBytes = static_cast<std::size_t>(drawWidth) * 4u;
            for (unsigned y = 0; y < drawHeight; ++y) {
                std::memcpy(dstBase + static_cast<std::size_t>(y) * buffer.stride * 4u,
                            data + static_cast<std::size_t>(y) * pitch,
                            copyBytes);
            }
        } else if (buffer.format == WINDOW_FORMAT_RGBA_8888) {
            for (unsigned y = 0; y < drawHeight; ++y) {
                auto* dst = reinterpret_cast<std::uint32_t*>(
                    dstBase + static_cast<std::size_t>(y) * buffer.stride * 4u);
                const auto* src = reinterpret_cast<const std::uint16_t*>(
                    data + static_cast<std::size_t>(y) * pitch);
                for (unsigned x = 0; x < drawWidth; ++x) {
                    const std::uint16_t p = src[x];
                    const unsigned r5 = (p >> 10) & 31u;
                    const unsigned g5 = (p >> 5) & 31u;
                    const unsigned b5 = p & 31u;
                    const std::uint32_t r = r5 * 255u / 31u;
                    const std::uint32_t g = g5 * 255u / 31u;
                    const std::uint32_t b = b5 * 255u / 31u;
                    dst[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        } else {
            ANativeWindow_unlockAndPost(window_);
            surfaceFailures_.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        ANativeWindow_unlockAndPost(window_);
        presentedFrames_.fetch_add(1, std::memory_order_relaxed);
    }

'''
v6 = replace_between(
    v6,
    "    bool acquireSoftwareFramebuffer(retro_framebuffer* fb) {",
    "    void pushAudioFrames(const std::int16_t* data, std::size_t frames) {",
    render_section,
    "render pipeline",
)

v6 = replace_once(v6, "                api.run();\n                if (directLocked_) releaseDirectFramebuffer(true);\n", "                api.run();\n", "remove Surface from emu loop")
v6 = replace_once(v6, "                const bool audioReady = startAudioIfPrimed(frames >= 4);", "                const bool audioReady = startAudioIfPrimed(false);", "audio priming")
v6 = replace_once(
    v6,
    "            windowHints_.setFrameRate(window_, fps_);\n            const bool audioOk = openAudio(av.timing.sample_rate);",
    "            windowHints_.setFrameRate(window_, fps_);\n            producedFrames_.store(0, std::memory_order_relaxed);\n            nonBlackFrames_.store(0, std::memory_order_relaxed);\n            droppedFrames_.store(0, std::memory_order_relaxed);\n            presentedFrames_.store(0, std::memory_order_relaxed);\n            surfaceFailures_.store(0, std::memory_order_relaxed);\n            malformedFrames_.store(0, std::memory_order_relaxed);\n            startRenderThread();\n            const bool audioOk = openAudio(av.timing.sample_rate);",
    "render startup",
)
v6 = replace_once(v6, "            presentedFrames_.store(0, std::memory_order_relaxed);\n", "", "remove duplicate presented reset")

old_telemetry_start = "                    const std::uint64_t presented = presentedFrames_.load(std::memory_order_relaxed);"
old_telemetry_end = "                    telemetryStart_ = now;"
new_telemetry = r'''                    const std::uint64_t presented = presentedFrames_.load(std::memory_order_relaxed);
                    const std::uint64_t produced = producedFrames_.load(std::memory_order_relaxed);
                    const std::uint64_t visible = nonBlackFrames_.load(std::memory_order_relaxed);
                    const std::uint64_t dropped = droppedFrames_.load(std::memory_order_relaxed);
                    const std::uint64_t surfaceFail = surfaceFailures_.load(std::memory_order_relaxed);
                    const std::uint64_t deltaFrames = frames - telemetryEmuFrames_;
                    const double hostFps = seconds > 0.0 ? static_cast<double>(deltaFrames) / seconds : 0.0;
                    const unsigned audioOcc = audioRing_.occupancyPercent();
                    const auto underruns = audioRing_.underruns();
                    if (frames > static_cast<std::uint64_t>(fps_ * 1.5) && produced == 0) {
                        setStatus("RUNTIME E10 • core executa, mas não entregou frames de vídeo");
                    } else if (produced > 0 && presented == 0 && surfaceFail > 0) {
                        setStatus("RUNTIME E11 • Surface recusou frames • falhas " + std::to_string(surfaceFail));
                    } else if (produced > static_cast<std::uint64_t>(fps_ * 2.0) && visible == 0) {
                        setStatus("RUNTIME W12 • core envia frames, mas o conteúdo continua preto");
                    } else if (audioStarted_ && underruns > telemetryUnderruns_ + 8) {
                        setStatus("RUNTIME W11 • áudio instável • " + std::to_string(audioOcc) + "% buffer");
                    } else if (!audioStarted_ && frames > static_cast<std::uint64_t>(fps_ * 2.0)) {
                        setStatus("RUNTIME W13 • aguardando áudio do core • vídeo " + std::to_string(presented));
                    } else {
                        char line[220]{};
                        std::snprintf(line, sizeof(line),
                                      "RUN OK • %.1f/%.1f fps • vídeo %llu/%llu drop %llu • áudio %u%% • u%llu/o%llu",
                                      hostFps, fps_,
                                      static_cast<unsigned long long>(presented),
                                      static_cast<unsigned long long>(produced),
                                      static_cast<unsigned long long>(dropped), audioOcc,
                                      static_cast<unsigned long long>(underruns),
                                      static_cast<unsigned long long>(audioRing_.overruns()));
                        setStatus(line);
                    }
'''
v6 = replace_between(v6, old_telemetry_start, old_telemetry_end, new_telemetry, "telemetry")
v6 = replace_once(v6, "        releaseDirectFramebuffer(true);\n        if (gameLoaded) api.unloadGame();", "        stopRenderThread();\n        if (gameLoaded) api.unloadGame();", "render shutdown")

old_fields = '''    unsigned bufferWidth_ = 0;
    unsigned bufferHeight_ = 0;
    int bufferFormat_ = 0;
    ANativeWindow_Buffer directBuffer_{};
    bool directLocked_ = false;
    void* directData_ = nullptr;
    NativeWindowHints windowHints_;
'''
new_fields = '''    NativeWindowHints windowHints_;
    std::mutex frameMutex_;
    std::condition_variable frameCv_;
    std::vector<std::uint8_t> pendingFrame_;
    unsigned pendingWidth_ = 0;
    unsigned pendingHeight_ = 0;
    std::size_t pendingPitch_ = 0;
    retro_pixel_format pendingFormat_ = RETRO_PIXEL_FORMAT_RGB565;
    std::uint64_t pendingSerial_ = 0;
    std::thread renderWorker_;
    std::atomic<bool> renderStop_{false};
    unsigned renderWidth_ = 0;
    unsigned renderHeight_ = 0;
    int renderFormat_ = 0;
'''
v6 = replace_once(v6, old_fields, new_fields, "render fields")
v6 = replace_once(
    v6,
    "    std::atomic<std::uint64_t> presentedFrames_{0};\n",
    "    std::atomic<std::uint64_t> producedFrames_{0};\n    std::atomic<std::uint64_t> nonBlackFrames_{0};\n    std::atomic<std::uint64_t> droppedFrames_{0};\n    std::atomic<std::uint64_t> malformedFrames_{0};\n    std::atomic<std::uint64_t> surfaceFailures_{0};\n    std::atomic<std::uint64_t> presentedFrames_{0};\n",
    "telemetry fields",
)

# Keep pacing conservative until the new pipeline proves stable on-device.
v6 = replace_once(v6, "const int occupancyError = static_cast<int>(audioRing_.occupancyPercent()) - 45;", "const int occupancyError = static_cast<int>(audioRing_.occupancyPercent()) - 35;", "audio occupancy target")

(CPP / "libretro_host_v6.cpp").write_text(v6)

cmake = (CPP / "CMakeLists.txt").read_text()
cmake = replace_once(cmake, "libretro_host_v5.cpp", "libretro_host_v6.cpp", "CMake runtime")
(CPP / "CMakeLists.txt").write_text(cmake)

bridge_path = CPP / "native_bridge.cpp"
bridge = bridge_path.read_text()
bridge = replace_once(
    bridge,
    'return env->NewStringUTF("OmniCore Native Runtime 0.3.0 / libretro host v4 / SmartPerf 3");',
    'return env->NewStringUTF("OmniCore Native Runtime 0.5.0 / libretro host v6 / decoupled A/V");',
    "native runtime label",
)
bridge_path.write_text(bridge)

build_path = ROOT / "app/build.gradle.kts"
build = build_path.read_text()
build = replace_once(build, 'versionCode = 6', 'versionCode = 7', "versionCode")
build = replace_once(build, 'versionName = "0.4.0"', 'versionName = "0.5.0"', "versionName")
build_path.write_text(build)

settings_path = ROOT / "app/src/main/java/com/omnicore/emulator/settings/Ps1Settings.kt"
settings = settings_path.read_text()
settings = replace_once(
    settings,
    '''        Preset.PERFORMANCE -> Config(preset, false, false, false, false, true, true, true, 32, "simple", dualShock)
        Preset.BALANCED -> Config(preset, false, false, true, true, true, false, false, 16, "gaussian", dualShock)
        Preset.QUALITY -> Config(preset, true, false, true, true, true, false, false, 16, "gaussian", dualShock)
        else -> Config(Preset.SMART, false, false, true, true, true, false, false, 16, "simple", dualShock)
''',
    '''        Preset.PERFORMANCE -> Config(preset, false, false, false, false, true, true, true, 32, "simple", dualShock)
        Preset.BALANCED -> Config(preset, false, false, true, true, false, false, false, 8, "simple", dualShock)
        Preset.QUALITY -> Config(preset, true, false, true, true, false, false, false, 8, "gaussian", dualShock)
        else -> Config(Preset.SMART, false, false, false, true, false, false, false, 8, "simple", dualShock)
''',
    "compatibility presets",
)
settings_path.write_text(settings)

print("Runtime v6 migration prepared successfully")
print("- libretro_host_v6.cpp: decoupled emulation/render threads")
print("- audio: larger reservoir and no forced empty start")
print("- SMART/BALANCED: compatibility-first core threading")
print("- version: 0.5.0")
