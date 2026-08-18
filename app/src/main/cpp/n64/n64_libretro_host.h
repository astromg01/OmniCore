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
    std::string saveRamPath;
    std::string diagnosticPath;
    std::string verificationPath;
    std::string cpuMode;
    std::string rspMode;
    std::string pakMode;
    std::string expansionPak;
    std::string aspectRatio = "4:3";
    bool framebufferEmulation = true;
    bool leanGraphics = false;
    bool threadedRenderer = false;
    int internalResolution = 10;
    int analogDeadzonePercent = 12;
    int analogSensitivityPercent = 100;
    std::string smartAnalogMode = "auto";
    bool smartAnalogAutoDpad = false;
    bool precisionAnalog = true;
    int audioBufferBursts = 4;
};

struct Telemetry {
    float averageFrameMs = 0.0f;
    float p95FrameMs = 0.0f;
    int droppedFrames = 0;
    int audioUnderruns = 0;
    int sampleWindowFrames = 0;
    float audioFillMs = 0.0f;
    float audioBufferMs = 0.0f;
    float targetFps = 0.0f;
    float pacingCorrectionPct = 0.0f;
    float presentAverageMs = 0.0f;
    float presentP95Ms = 0.0f;
    float adpfActive = 0.0f;
    float burstShieldActive = 0.0f;
    float warmStartActive = 0.0f;
    float shaderCacheEnabled = 0.0f;
    float directPresenterActive = 0.0f;
    float shaderCacheReady = 0.0f;
    float smartAnalogDpadActive = 0.0f;
    float passiveWarmCacheReady = 0.0f;
    float precisionGovernorMode = 0.0f;
    float precisionGovernorConfidence = 0.0f;
    float frameJitterMs = 0.0f;
};

class LibretroHost final {
public:
    static LibretroHost& instance();
    bool start(ANativeWindow* window, RuntimeConfig config);
    void stop();
    void setPaused(bool paused);
    void setAudioTargetBursts(int bursts);
    bool requestSaveState(std::string path);
    bool requestLoadState(std::string path);
    bool requestReset();
    bool running() const { return running_.load(std::memory_order_acquire); }
    std::string lastMessage() const;
    Telemetry telemetry() const;
    void setButton(unsigned retroPadId, bool pressed);
    void setAnalog(float x, float y, float cX, float cY);

private:
    enum class CommandType { NONE, SAVE_STATE, LOAD_STATE, RESET };
    static constexpr std::size_t kTelemetryCapacity = 120;
    LibretroHost() = default;
    ~LibretroHost();
    LibretroHost(const LibretroHost&) = delete;
    LibretroHost& operator=(const LibretroHost&) = delete;
    void run();
    void setMessage(std::string message);
    void buildCoreOptions();
    void recordFrame(float frameMs, float targetMs);
    void recordPresent(float presentMs);
    bool environment(unsigned cmd, void* data);
    void videoRefresh(const void* data, unsigned width, unsigned height, std::size_t pitch);
    void audioSample(std::int16_t left, std::int16_t right);
    std::size_t audioBatch(const std::int16_t* data, std::size_t frames);
    std::int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) const;
    bool processPendingCommand();
    void loadSaveRam();
    void persistSaveRam(bool force);
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
    std::atomic<std::uint16_t> smartDpadMask_{0};
    std::atomic<std::int16_t> analogX_{0};
    std::atomic<std::int16_t> analogY_{0};
    std::atomic<std::int16_t> cX_{0};
    std::atomic<std::int16_t> cY_{0};
    std::atomic<int> audioTargetBursts_{4};
    std::atomic<bool> menuTransitionBoost_{false};
    std::atomic<bool> interactionTransitionBoost_{false};
    std::atomic<float> audioFillMs_{0.0f};
    std::atomic<float> audioBufferMs_{0.0f};
    std::atomic<float> targetFps_{60.0f};
    std::atomic<float> pacingCorrectionPct_{0.0f};
    mutable std::mutex messageMutex_;
    std::string message_ = "N64 host idle";
    mutable std::mutex telemetryMutex_;
    std::array<float, kTelemetryCapacity> frameWindow_{};
    std::size_t frameWindowCount_ = 0;
    std::size_t frameWindowWrite_ = 0;
    std::array<float, kTelemetryCapacity> presentWindow_{};
    std::size_t presentWindowCount_ = 0;
    std::size_t presentWindowWrite_ = 0;
    std::atomic<int> audioUnderruns_{0};
    std::atomic<float> targetFrameMs_{1000.0f / 60.0f};
    std::atomic<bool> adpfActive_{false};
    std::atomic<bool> burstShieldActive_{false};
    std::atomic<bool> warmStartActive_{false};
    std::atomic<bool> shaderCacheEnabled_{false};
    std::atomic<bool> directPresenterActive_{false};
    std::atomic<bool> shaderCacheReady_{false};
    std::atomic<bool> passiveWarmCacheReady_{false};
    std::atomic<bool> smartAnalogDpadActive_{false};
    std::atomic<int> precisionGovernorMode_{0};
    std::atomic<float> precisionGovernorConfidence_{0.0f};
    std::atomic<float> frameJitterMs_{0.0f};
    std::atomic<float> lastPresentMs_{0.0f};
    mutable std::mutex optionMutex_;
    std::unordered_map<std::string, std::string> options_;
    mutable std::mutex commandMutex_;
    CommandType pendingCommand_ = CommandType::NONE;
    std::string pendingStatePath_;
    std::uint64_t lastSaveRamHash_ = 0;
    abi::retro_hw_render_callback hwRender_{};
    bool hwRenderRequested_ = false;
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace omnicore::n64
