#pragma once

#include <android/native_window.h>
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include "n64_libretro_abi.h"

namespace omnicore::n64 {

struct RuntimeConfig {
    std::string romPath;
    std::string systemDir;
    std::string saveDir;
    std::string cpuMode;
    std::string rspMode;
    std::string pakMode;
    bool framebufferEmulation = true;
    bool threadedRenderer = false;
    int internalResolution = 1;
    int analogDeadzonePercent = 12;
    int analogSensitivityPercent = 100;
    int audioBufferBursts = 3;
};

struct Telemetry {
    float averageFrameMs = 0.0f;
    float p95FrameMs = 0.0f;
    int droppedFrames = 0;
    int audioUnderruns = 0;
    int sampleWindowFrames = 0;
};

class LibretroHost final {
public:
    static LibretroHost& instance();
    bool start(ANativeWindow* window, RuntimeConfig config);
    void stop();
    void setPaused(bool paused);
    bool running() const { return running_.load(std::memory_order_acquire); }
    std::string lastMessage() const;
    Telemetry telemetry() const;
    void setButton(unsigned retroPadId, bool pressed);
    void setAnalog(float x, float y, float cX, float cY);

private:
    static constexpr std::size_t kTelemetryCapacity = 120;
    LibretroHost() = default;
    ~LibretroHost();
    LibretroHost(const LibretroHost&) = delete;
    LibretroHost& operator=(const LibretroHost&) = delete;
    void run();
    void setMessage(std::string message);
    void buildCoreOptions();
    void recordFrame(float frameMs, float targetMs);
    bool environment(unsigned cmd, void* data);
    void videoRefresh(const void* data, unsigned width, unsigned height, std::size_t pitch);
    void audioSample(std::int16_t left, std::int16_t right);
    std::size_t audioBatch(const std::int16_t* data, std::size_t frames);
    std::int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) const;
    static bool environmentCallback(unsigned cmd, void* data);
    static void videoCallback(const void* data, unsigned width, unsigned height, std::size_t pitch);
    static void audioSampleCallback(std::int16_t left, std::int16_t right);
    static std::size_t audioBatchCallback(const std::int16_t* data, std::size_t frames);
    static void inputPollCallback();
    static std::int16_t inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id);
    static bool clearThreadWaitsCallback(unsigned cmd, void* data);
    static std::uintptr_t currentFramebufferCallback();
    static abi::retro_proc_address_t procAddressCallback(const char* symbol);

    ANativeWindow* window_ = nullptr;
    RuntimeConfig config_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> paused_{false};
    std::atomic<std::uint16_t> buttonMask_{0};
    std::atomic<std::int16_t> analogX_{0};
    std::atomic<std::int16_t> analogY_{0};
    std::atomic<std::int16_t> cX_{0};
    std::atomic<std::int16_t> cY_{0};
    mutable std::mutex messageMutex_;
    std::string message_ = "N64 host idle";
    mutable std::mutex telemetryMutex_;
    std::array<float, kTelemetryCapacity> frameWindow_{};
    std::size_t frameWindowCount_ = 0;
    std::size_t frameWindowWrite_ = 0;
    std::atomic<int> audioUnderruns_{0};
    std::atomic<float> targetFrameMs_{1000.0f / 60.0f};
    mutable std::mutex optionMutex_;
    std::unordered_map<std::string, std::string> options_;
    abi::retro_hw_render_callback hwRender_{};
    bool hwRenderRequested_ = false;
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace omnicore::n64
