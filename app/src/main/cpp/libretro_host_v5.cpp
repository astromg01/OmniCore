#include "libretro_host.h"
#include "libretro_abi.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <sys/syscall.h>
#include <unistd.h>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {
constexpr const char* kLogTag = "OmniCoreNative";
constexpr std::size_t kAudioRingSamples = 8192;

std::mutex gCoreLogMutex;
std::string gLastCoreLog;

void rememberCoreLog(enum retro_log_level level, const char* text) {
    if (!text || level < RETRO_LOG_WARN) return;
    std::lock_guard<std::mutex> lock(gCoreLogMutex);
    gLastCoreLog = text;
    while (!gLastCoreLog.empty() &&
           (gLastCoreLog.back() == '\n' || gLastCoreLog.back() == '\r')) {
        gLastCoreLog.pop_back();
    }
    if (gLastCoreLog.size() > 260) gLastCoreLog.resize(260);
}

std::string lastCoreLog() {
    std::lock_guard<std::mutex> lock(gCoreLogMutex);
    return gLastCoreLog;
}

void clearCoreLog() {
    std::lock_guard<std::mutex> lock(gCoreLogMutex);
    gLastCoreLog.clear();
}

void coreLog(enum retro_log_level level, const char* fmt, ...) {
    int priority = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO: priority = ANDROID_LOG_INFO; break;
        case RETRO_LOG_WARN: priority = ANDROID_LOG_WARN; break;
        case RETRO_LOG_ERROR: priority = ANDROID_LOG_ERROR; break;
        default: break;
    }

    char buffer[1024]{};
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    __android_log_print(priority, kLogTag, "%s", buffer);
    rememberCoreLog(level, buffer);
}

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

std::string safeKey(std::string value) {
    for (char& c : value) {
        const bool ok = (c >= 'a' && c <= 'z') ||
                        (c >= 'A' && c <= 'Z') ||
                        (c >= '0' && c <= '9') || c == '-' || c == '_';
        if (!ok) c = '_';
    }
    return value.empty() ? "game" : value;
}

std::unordered_map<std::string, std::string> parseCoreOptions(const std::string& block) {
    std::unordered_map<std::string, std::string> out;
    std::size_t start = 0;
    while (start < block.size()) {
        const auto end = block.find('\n', start);
        const std::string line = trim(block.substr(start, end == std::string::npos ? std::string::npos : end - start));
        if (!line.empty() && line[0] != '#') {
            const auto equals = line.find('=');
            if (equals != std::string::npos) {
                const std::string key = trim(line.substr(0, equals));
                const std::string value = trim(line.substr(equals + 1));
                if (!key.empty() && !value.empty()) out[key] = value;
            }
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return out;
}

class AudioRing {
public:
    explicit AudioRing(std::size_t capacitySamples = kAudioRingSamples)
        : data_(capacitySamples, 0), capacity_(capacitySamples) {}

    void clear() {
        read_.store(0, std::memory_order_release);
        write_.store(0, std::memory_order_release);
        underruns_.store(0, std::memory_order_release);
        overruns_.store(0, std::memory_order_release);
    }

    std::size_t push(const std::int16_t* input, std::size_t samples) {
        if (!input || samples == 0) return 0;
        const std::uint64_t r = read_.load(std::memory_order_acquire);
        const std::uint64_t w = write_.load(std::memory_order_relaxed);
        const std::uint64_t used = w - r;
        if (used >= capacity_) {
            overruns_.fetch_add(1, std::memory_order_relaxed);
            return 0;
        }
        std::size_t count = std::min(samples, static_cast<std::size_t>(capacity_ - used));
        count &= ~static_cast<std::size_t>(1);
        if (count < samples) overruns_.fetch_add(1, std::memory_order_relaxed);
        if (count == 0) return 0;
        const std::size_t start = static_cast<std::size_t>(w % capacity_);
        const std::size_t first = std::min(count, capacity_ - start);
        std::memcpy(data_.data() + start, input, first * sizeof(std::int16_t));
        if (first < count) {
            std::memcpy(data_.data(), input + first, (count - first) * sizeof(std::int16_t));
        }
        write_.store(w + count, std::memory_order_release);
        return count;
    }

    std::size_t pop(std::int16_t* output, std::size_t samples) {
        if (!output || samples == 0) return 0;
        const std::uint64_t r = read_.load(std::memory_order_relaxed);
        const std::uint64_t w = write_.load(std::memory_order_acquire);
        const std::size_t count = static_cast<std::size_t>(std::min<std::uint64_t>(samples, w - r));
        if (count > 0) {
            const std::size_t start = static_cast<std::size_t>(r % capacity_);
            const std::size_t first = std::min(count, capacity_ - start);
            std::memcpy(output, data_.data() + start, first * sizeof(std::int16_t));
            if (first < count) {
                std::memcpy(output + first, data_.data(), (count - first) * sizeof(std::int16_t));
            }
        }
        if (count < samples) {
            std::fill(output + count, output + samples, 0);
            underruns_.fetch_add(1, std::memory_order_relaxed);
        }
        read_.store(r + count, std::memory_order_release);
        return count;
    }

    unsigned occupancyPercent() const {
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

private:
    std::vector<std::int16_t> data_;
    std::size_t capacity_;
    std::atomic<std::uint64_t> read_{0};
    std::atomic<std::uint64_t> write_{0};
    std::atomic<std::uint64_t> underruns_{0};
    std::atomic<std::uint64_t> overruns_{0};
};

class NativeWindowHints {
public:
    NativeWindowHints() {
        lib_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib_) return;
        setFrameRate_ = reinterpret_cast<SetFrameRateFn>(dlsym(lib_, "ANativeWindow_setFrameRate"));
        clearFrameRate_ = reinterpret_cast<ClearFrameRateFn>(dlsym(lib_, "ANativeWindow_clearFrameRate"));
        tryAllocateBuffers_ = reinterpret_cast<TryAllocateBuffersFn>(dlsym(lib_, "ANativeWindow_tryAllocateBuffers"));
    }
    ~NativeWindowHints() { if (lib_) dlclose(lib_); }

    void setFrameRate(ANativeWindow* window, double fps) {
        if (window && setFrameRate_ && fps > 1.0) setFrameRate_(window, static_cast<float>(fps), 0);
    }
    void clearFrameRate(ANativeWindow* window) {
        if (window && clearFrameRate_) clearFrameRate_(window);
    }
    void allocate(ANativeWindow* window) {
        if (window && tryAllocateBuffers_) tryAllocateBuffers_(window);
    }
private:
    using SetFrameRateFn = std::int32_t (*)(ANativeWindow*, float, std::int8_t);
    using ClearFrameRateFn = std::int32_t (*)(ANativeWindow*);
    using TryAllocateBuffersFn = void (*)(ANativeWindow*);
    void* lib_ = nullptr;
    SetFrameRateFn setFrameRate_ = nullptr;
    ClearFrameRateFn clearFrameRate_ = nullptr;
    TryAllocateBuffersFn tryAllocateBuffers_ = nullptr;
};

class PerformanceHintBridge {
public:
    PerformanceHintBridge() {
        lib_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib_) return;
        getManager_ = reinterpret_cast<GetManagerFn>(dlsym(lib_, "APerformanceHint_getManager"));
        createSession_ = reinterpret_cast<CreateSessionFn>(dlsym(lib_, "APerformanceHint_createSession"));
        reportActual_ = reinterpret_cast<ReportActualFn>(dlsym(lib_, "APerformanceHint_reportActualWorkDuration"));
        updateTarget_ = reinterpret_cast<UpdateTargetFn>(dlsym(lib_, "APerformanceHint_updateTargetWorkDuration"));
        closeSession_ = reinterpret_cast<CloseSessionFn>(dlsym(lib_, "APerformanceHint_closeSession"));
        setPowerEfficiency_ = reinterpret_cast<SetPowerEfficiencyFn>(dlsym(lib_, "APerformanceHint_setPreferPowerEfficiency"));
    }
    ~PerformanceHintBridge() { close(); if (lib_) dlclose(lib_); }

    bool start(std::int64_t targetNanos, bool preferPowerEfficiency) {
        if (!getManager_ || !createSession_ || !reportActual_ || !closeSession_ || targetNanos <= 0) return false;
        void* manager = getManager_();
        if (!manager) return false;
        const std::int32_t tid = static_cast<std::int32_t>(syscall(SYS_gettid));
        session_ = createSession_(manager, &tid, 1, targetNanos);
        if (!session_) return false;
        targetNanos_ = targetNanos;
        apply(targetNanos, preferPowerEfficiency);
        return true;
    }
    void apply(std::int64_t targetNanos, bool preferPowerEfficiency) {
        if (!session_) return;
        if (targetNanos > 0 && targetNanos != targetNanos_ && updateTarget_) {
            updateTarget_(session_, targetNanos);
            targetNanos_ = targetNanos;
        }
        if (setPowerEfficiency_) setPowerEfficiency_(session_, preferPowerEfficiency);
    }
    void report(std::int64_t actualNanos) {
        if (session_ && reportActual_ && actualNanos > 0) reportActual_(session_, actualNanos);
    }
    void close() {
        if (session_ && closeSession_) closeSession_(session_);
        session_ = nullptr;
    }
private:
    using GetManagerFn = void* (*)();
    using CreateSessionFn = void* (*)(void*, const std::int32_t*, std::size_t, std::int64_t);
    using ReportActualFn = int (*)(void*, std::int64_t);
    using UpdateTargetFn = int (*)(void*, std::int64_t);
    using CloseSessionFn = void (*)(void*);
    using SetPowerEfficiencyFn = int (*)(void*, bool);
    void* lib_ = nullptr;
    void* session_ = nullptr;
    std::int64_t targetNanos_ = 0;
    GetManagerFn getManager_ = nullptr;
    CreateSessionFn createSession_ = nullptr;
    ReportActualFn reportActual_ = nullptr;
    UpdateTargetFn updateTarget_ = nullptr;
    CloseSessionFn closeSession_ = nullptr;
    SetPowerEfficiencyFn setPowerEfficiency_ = nullptr;
};

struct CoreApi {
    void* handle = nullptr;
    using set_environment_t = void (*)(retro_environment_t);
    using set_video_refresh_t = void (*)(retro_video_refresh_t);
    using set_audio_sample_t = void (*)(retro_audio_sample_t);
    using set_audio_sample_batch_t = void (*)(retro_audio_sample_batch_t);
    using set_input_poll_t = void (*)(retro_input_poll_t);
    using set_input_state_t = void (*)(retro_input_state_t);
    using init_t = void (*)();
    using deinit_t = void (*)();
    using api_version_t = unsigned (*)();
    using get_system_info_t = void (*)(retro_system_info*);
    using get_system_av_info_t = void (*)(retro_system_av_info*);
    using set_controller_port_device_t = void (*)(unsigned, unsigned);
    using run_t = void (*)();
    using serialize_size_t = std::size_t (*)();
    using serialize_t = bool (*)(void*, std::size_t);
    using unserialize_t = bool (*)(const void*, std::size_t);
    using load_game_t = bool (*)(const retro_game_info*);
    using unload_game_t = void (*)();
    using get_memory_data_t = void* (*)(unsigned);
    using get_memory_size_t = std::size_t (*)(unsigned);

    set_environment_t setEnvironment = nullptr;
    set_video_refresh_t setVideoRefresh = nullptr;
    set_audio_sample_t setAudioSample = nullptr;
    set_audio_sample_batch_t setAudioSampleBatch = nullptr;
    set_input_poll_t setInputPoll = nullptr;
    set_input_state_t setInputState = nullptr;
    init_t init = nullptr;
    deinit_t deinit = nullptr;
    api_version_t apiVersion = nullptr;
    get_system_info_t getSystemInfo = nullptr;
    get_system_av_info_t getSystemAvInfo = nullptr;
    set_controller_port_device_t setControllerPortDevice = nullptr;
    run_t run = nullptr;
    serialize_size_t serializeSize = nullptr;
    serialize_t serialize = nullptr;
    unserialize_t unserialize = nullptr;
    load_game_t loadGame = nullptr;
    unload_game_t unloadGame = nullptr;
    get_memory_data_t getMemoryData = nullptr;
    get_memory_size_t getMemorySize = nullptr;
};

template <typename T>
bool symbol(void* handle, const char* name, T& target) {
    target = reinterpret_cast<T>(dlsym(handle, name));
    if (!target) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Missing libretro symbol: %s", name);
        return false;
    }
    return true;
}

bool loadCore(const std::string& library, CoreApi& api) {
    api.handle = dlopen(library.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!api.handle) {
        const char* error = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "dlopen(%s) failed: %s", library.c_str(), error ? error : "unknown");
        rememberCoreLog(RETRO_LOG_ERROR, error ? error : "dlopen failed");
        return false;
    }
    const bool ok =
        symbol(api.handle, "retro_set_environment", api.setEnvironment) &&
        symbol(api.handle, "retro_set_video_refresh", api.setVideoRefresh) &&
        symbol(api.handle, "retro_set_audio_sample", api.setAudioSample) &&
        symbol(api.handle, "retro_set_audio_sample_batch", api.setAudioSampleBatch) &&
        symbol(api.handle, "retro_set_input_poll", api.setInputPoll) &&
        symbol(api.handle, "retro_set_input_state", api.setInputState) &&
        symbol(api.handle, "retro_init", api.init) &&
        symbol(api.handle, "retro_deinit", api.deinit) &&
        symbol(api.handle, "retro_api_version", api.apiVersion) &&
        symbol(api.handle, "retro_get_system_info", api.getSystemInfo) &&
        symbol(api.handle, "retro_get_system_av_info", api.getSystemAvInfo) &&
        symbol(api.handle, "retro_set_controller_port_device", api.setControllerPortDevice) &&
        symbol(api.handle, "retro_run", api.run) &&
        symbol(api.handle, "retro_serialize_size", api.serializeSize) &&
        symbol(api.handle, "retro_serialize", api.serialize) &&
        symbol(api.handle, "retro_unserialize", api.unserialize) &&
        symbol(api.handle, "retro_load_game", api.loadGame) &&
        symbol(api.handle, "retro_unload_game", api.unloadGame) &&
        symbol(api.handle, "retro_get_memory_data", api.getMemoryData) &&
        symbol(api.handle, "retro_get_memory_size", api.getMemorySize);
    if (!ok) {
        dlclose(api.handle);
        api.handle = nullptr;
    }
    return ok;
}

void unloadCore(CoreApi& api) {
    if (api.handle) dlclose(api.handle);
    api.handle = nullptr;
}
} // namespace

class LibretroSession::Impl {
public:
    Impl(std::string coreLibrary,
         std::string gamePath,
         std::string gameKey,
         std::string systemDir,
         std::string saveDir,
         std::string stateDir,
         ANativeWindow* window,
         RuntimePerformanceConfig performance,
         std::string coreOptions,
         bool dualShock)
        : coreLibrary_(std::move(coreLibrary)),
          gamePath_(std::move(gamePath)),
          gameKey_(safeKey(std::move(gameKey))),
          systemDir_(std::move(systemDir)),
          saveDir_(std::move(saveDir)),
          stateDir_(std::move(stateDir)),
          contentDir_(std::filesystem::path(gamePath_).parent_path().string()),
          window_(window),
          performance_(sanitizePerformance(performance)),
          requestedOptions_(parseCoreOptions(coreOptions)),
          dualShock_(dualShock) {
        variables_ = requestedOptions_;
    }

    ~Impl() {
        stop();
        releaseDirectFramebuffer(false);
        if (window_) {
            windowHints_.clearFrameRate(window_);
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    bool start() {
        if (running_.load(std::memory_order_acquire)) return false;
        if (!probeLibretroCore(coreLibrary_.c_str())) {
            setStatus("BOOT E01 • core PCSX-ReARMed ausente ou inválido");
            return false;
        }
        stopRequested_.store(false, std::memory_order_release);
        running_.store(true, std::memory_order_release);
        worker_ = std::thread([this] { runLoop(); });
        return true;
    }

    void stop() {
        stopRequested_.store(true, std::memory_order_release);
        if (worker_.joinable()) worker_.join();
        running_.store(false, std::memory_order_release);
    }

    bool running() const { return running_.load(std::memory_order_acquire); }

    void setButton(unsigned id, bool pressed) {
        if (id >= 16) return;
        const std::uint32_t bit = 1u << id;
        if (pressed) buttons_.fetch_or(bit, std::memory_order_acq_rel);
        else buttons_.fetch_and(~bit, std::memory_order_acq_rel);
    }

    void setAnalog(unsigned stick, std::int16_t x, std::int16_t y) {
        if (stick == 0) {
            leftX_.store(x, std::memory_order_release);
            leftY_.store(y, std::memory_order_release);
        } else if (stick == 1) {
            rightX_.store(x, std::memory_order_release);
            rightY_.store(y, std::memory_order_release);
        }
    }

    void requestSaveState(int slot) { saveStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }
    void requestLoadState(int slot) { loadStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }

    void updatePerformanceConfig(RuntimePerformanceConfig config) {
        {
            std::lock_guard<std::mutex> lock(performanceMutex_);
            performance_ = sanitizePerformance(config);
        }
        audioReconfigureRequested_.store(true, std::memory_order_release);
    }

    std::string status() const {
        std::lock_guard<std::mutex> lock(statusMutex_);
        return status_;
    }

private:
    static Impl* active_;

    static RuntimePerformanceConfig sanitizePerformance(RuntimePerformanceConfig value) {
        value.policy = std::clamp(value.policy, 0, 2);
        value.audioBufferBursts = std::clamp(value.audioBufferBursts, 2, 8);
        return value;
    }

    RuntimePerformanceConfig performanceSnapshot() const {
        std::lock_guard<std::mutex> lock(performanceMutex_);
        return performance_;
    }

    static const char* policyLabel(int policy) {
        switch (policy) {
            case 0: return "sustentável";
            case 2: return "baixa latência";
            default: return "equilibrado";
        }
    }

    static std::int64_t performanceTargetNanos(double fps, const RuntimePerformanceConfig& config) {
        const double frame = 1'000'000'000.0 / std::max(1.0, fps);
        const double ratio = config.policy == 2 ? 0.82 : (config.policy == 0 ? 0.96 : 0.90);
        return static_cast<std::int64_t>(frame * ratio);
    }

    static bool environmentCallback(unsigned cmd, void* data) {
        return active_ ? active_->environment(cmd, data) : false;
    }
    static void videoCallback(const void* data, unsigned width, unsigned height, std::size_t pitch) {
        if (active_) active_->renderFrame(data, width, height, pitch);
    }
    static void audioSampleCallback(std::int16_t left, std::int16_t right) {
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
    static void inputPollCallback() {}
    static std::int16_t inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
        if (!active_ || port != 0) return 0;
        if (device == RETRO_DEVICE_JOYPAD) {
            const std::uint32_t mask = active_->buttons_.load(std::memory_order_acquire);
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<std::int16_t>(mask & 0xFFFFu);
            if (id >= 16) return 0;
            return (mask & (1u << id)) ? 1 : 0;
        }
        if (device == RETRO_DEVICE_ANALOG) {
            if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
                if (id == RETRO_DEVICE_ID_ANALOG_X) return static_cast<std::int16_t>(active_->leftX_.load(std::memory_order_acquire));
                if (id == RETRO_DEVICE_ID_ANALOG_Y) return static_cast<std::int16_t>(active_->leftY_.load(std::memory_order_acquire));
            }
            if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
                if (id == RETRO_DEVICE_ID_ANALOG_X) return static_cast<std::int16_t>(active_->rightX_.load(std::memory_order_acquire));
                if (id == RETRO_DEVICE_ID_ANALOG_Y) return static_cast<std::int16_t>(active_->rightY_.load(std::memory_order_acquire));
            }
        }
        return 0;
    }
    static aaudio_data_callback_result_t aaudioCallback(AAudioStream*, void* userData, void* audioData, std::int32_t numFrames) {
        auto* self = static_cast<Impl*>(userData);
        if (!self || !audioData || numFrames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
        self->audioRing_.pop(static_cast<std::int16_t*>(audioData), static_cast<std::size_t>(numFrames) * 2);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    bool environment(unsigned cmd, void* data) {
        switch (cmd) {
            case RETRO_ENVIRONMENT_GET_CAN_DUPE:
                if (data) *static_cast<bool*>(data) = true;
                return data != nullptr;
            case RETRO_ENVIRONMENT_SET_MESSAGE:
                if (data) {
                    const auto* msg = static_cast<const retro_message*>(data);
                    if (msg->msg) setStatus(std::string("CORE • ") + msg->msg);
                }
                return true;
            case RETRO_ENVIRONMENT_SHUTDOWN:
                stopRequested_.store(true, std::memory_order_release);
                return true;
            case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
            case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
            case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
                return true;
            case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
                if (!data) return false;
                *static_cast<const char**>(data) = systemDir_.c_str();
                return true;
            case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
                if (!data) return false;
                *static_cast<const char**>(data) = saveDir_.c_str();
                return true;
            case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
                if (!data) return false;
                *static_cast<const char**>(data) = contentDir_.c_str();
                return true;
            case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
                if (!data) return false;
                pixelFormat_ = *static_cast<const retro_pixel_format*>(data);
                return pixelFormat_ == RETRO_PIXEL_FORMAT_RGB565 ||
                       pixelFormat_ == RETRO_PIXEL_FORMAT_XRGB8888 ||
                       pixelFormat_ == RETRO_PIXEL_FORMAT_0RGB1555;
            case RETRO_ENVIRONMENT_GET_VARIABLE: {
                if (!data) return false;
                auto* var = static_cast<retro_variable*>(data);
                if (!var->key) return false;
                const auto it = variables_.find(var->key);
                var->value = it == variables_.end() ? nullptr : it->second.c_str();
                return var->value != nullptr;
            }
            case RETRO_ENVIRONMENT_SET_VARIABLES:
                registerLegacyVariables(static_cast<const retro_variable*>(data));
                return true;
            case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
                if (data) *static_cast<bool*>(data) = false;
                return data != nullptr;
            case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
                if (data) *static_cast<unsigned*>(data) = 0;
                return false;
            case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
            case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
            case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
            case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
                return false;
            case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
                if (!data) return false;
                static_cast<retro_log_callback*>(data)->log = coreLog;
                return true;
            case RETRO_ENVIRONMENT_GET_LANGUAGE:
                if (data) *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
                return data != nullptr;
            case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
                return true;
            case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS:
                if (data) *static_cast<unsigned*>(data) = 1;
                return data != nullptr;
            case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
                if (data) *static_cast<bool*>(data) = false;
                return data != nullptr;
            case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
                if (data) *static_cast<float*>(data) = static_cast<float>(fps_ > 1.0 ? fps_ : 60.0);
                return data != nullptr;
            case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
                if (data) *static_cast<int*>(data) = static_cast<int>(RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO);
                return data != nullptr;
            case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
                if (data) *static_cast<unsigned*>(data) = 0;
                return data != nullptr;
            case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
                if (data) {
                    const auto* info = static_cast<const retro_system_av_info*>(data);
                    if (info->timing.fps > 1.0) fps_ = info->timing.fps;
                }
                return true;
            case RETRO_ENVIRONMENT_SET_GEOMETRY:
                return true;
            case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
                // Compatibility mode: use the core-owned framebuffer and copy at
                // video refresh. Direct Surface buffers can block the emulation
                // thread on some Android devices/providers, starving audio and
                // producing a black screen. Re-enable only after device validation.
                return false;
            case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK:
                if (!data) {
                    audioStatusCallback_ = nullptr;
                    return true;
                }
                audioStatusCallback_ = static_cast<const retro_audio_buffer_status_callback*>(data)->callback;
                return true;
            case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
                if (data) minimumAudioLatencyMs_ = *static_cast<const unsigned*>(data);
                return true;
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
            case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
            case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
                return false;
            default:
                return false;
        }
    }

    void registerLegacyVariables(const retro_variable* vars) {
        if (!vars) return;
        for (const retro_variable* var = vars; var->key; ++var) {
            if (!var->value) continue;
            const std::string key(var->key);
            std::string definition(var->value);
            const auto semicolon = definition.find(';');
            if (semicolon == std::string::npos) continue;
            std::string values = trim(definition.substr(semicolon + 1));
            const auto pipe = values.find('|');
            const std::string fallback = trim(values.substr(0, pipe));
            const auto requested = requestedOptions_.find(key);
            variables_[key] = requested != requestedOptions_.end() ? requested->second : fallback;
        }
    }

    void setStatus(std::string value) {
        std::lock_guard<std::mutex> lock(statusMutex_);
        status_ = std::move(value);
    }

    bool acquireSoftwareFramebuffer(retro_framebuffer* fb) {
        if (!fb || !window_ || fb->width == 0 || fb->height == 0) return false;
        if (pixelFormat_ != RETRO_PIXEL_FORMAT_RGB565 && pixelFormat_ != RETRO_PIXEL_FORMAT_XRGB8888) return false;
        releaseDirectFramebuffer(false);

        const int format = pixelFormat_ == RETRO_PIXEL_FORMAT_RGB565 ? WINDOW_FORMAT_RGB_565 : WINDOW_FORMAT_RGBA_8888;
        if (bufferWidth_ != fb->width || bufferHeight_ != fb->height || bufferFormat_ != format) {
            if (ANativeWindow_setBuffersGeometry(window_, static_cast<int>(fb->width), static_cast<int>(fb->height), format) != 0) return false;
            bufferWidth_ = fb->width;
            bufferHeight_ = fb->height;
            bufferFormat_ = format;
            windowHints_.allocate(window_);
        }

        directBuffer_ = {};
        if (ANativeWindow_lock(window_, &directBuffer_, nullptr) != 0) return false;
        directLocked_ = true;
        directData_ = directBuffer_.bits;
        fb->data = directBuffer_.bits;
        fb->width = static_cast<unsigned>(directBuffer_.width);
        fb->height = static_cast<unsigned>(directBuffer_.height);
        fb->pitch = static_cast<std::size_t>(directBuffer_.stride) * (format == WINDOW_FORMAT_RGB_565 ? 2u : 4u);
        fb->format = pixelFormat_;
        fb->access_flags = RETRO_MEMORY_ACCESS_WRITE;
        fb->memory_flags = 0;
        return true;
    }

    void releaseDirectFramebuffer(bool post) {
        if (!directLocked_ || !window_) return;
        if (post) ANativeWindow_unlockAndPost(window_);
        else ANativeWindow_unlockAndPost(window_); // Never leave a Surface buffer locked across frames.
        directLocked_ = false;
        directData_ = nullptr;
        directBuffer_ = {};
    }

    void renderFrame(const void* data, unsigned width, unsigned height, std::size_t pitch) {
        if (!window_ || width == 0 || height == 0) return;
        if (directLocked_) {
            if (data == directData_) {
                releaseDirectFramebuffer(true);
                return;
            }
            releaseDirectFramebuffer(true);
        }
        if (!data || data == reinterpret_cast<const void*>(static_cast<std::intptr_t>(-1))) return;

        const int desired = pixelFormat_ == RETRO_PIXEL_FORMAT_RGB565 ? WINDOW_FORMAT_RGB_565 : WINDOW_FORMAT_RGBA_8888;
        if (bufferWidth_ != width || bufferHeight_ != height || bufferFormat_ != desired) {
            if (ANativeWindow_setBuffersGeometry(window_, static_cast<int>(width), static_cast<int>(height), desired) != 0) return;
            bufferWidth_ = width;
            bufferHeight_ = height;
            bufferFormat_ = desired;
            windowHints_.allocate(window_);
        }

        ANativeWindow_Buffer buffer{};
        if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) return;
        const unsigned drawWidth = std::min(width, static_cast<unsigned>(buffer.width));
        const unsigned drawHeight = std::min(height, static_cast<unsigned>(buffer.height));
        const auto* srcBase = static_cast<const std::uint8_t*>(data);
        auto* dstBase = static_cast<std::uint8_t*>(buffer.bits);

        if (pixelFormat_ == RETRO_PIXEL_FORMAT_RGB565 && buffer.format == WINDOW_FORMAT_RGB_565) {
            const std::size_t copyBytes = static_cast<std::size_t>(drawWidth) * 2;
            for (unsigned y = 0; y < drawHeight; ++y) {
                std::memcpy(dstBase + static_cast<std::size_t>(y) * buffer.stride * 2,
                            srcBase + static_cast<std::size_t>(y) * pitch,
                            copyBytes);
            }
        } else if (pixelFormat_ == RETRO_PIXEL_FORMAT_XRGB8888) {
            for (unsigned y = 0; y < drawHeight; ++y) {
                std::memcpy(dstBase + static_cast<std::size_t>(y) * buffer.stride * 4,
                            srcBase + static_cast<std::size_t>(y) * pitch,
                            static_cast<std::size_t>(drawWidth) * 4);
            }
        } else {
            for (unsigned y = 0; y < drawHeight; ++y) {
                auto* dst = reinterpret_cast<std::uint32_t*>(dstBase + static_cast<std::size_t>(y) * buffer.stride * 4);
                const auto* src = reinterpret_cast<const std::uint16_t*>(srcBase + static_cast<std::size_t>(y) * pitch);
                for (unsigned x = 0; x < drawWidth; ++x) {
                    const std::uint16_t p = src[x];
                    const std::uint32_t r = ((p >> 10) & 31u) * 255u / 31u;
                    const std::uint32_t g = ((p >> 5) & 31u) * 255u / 31u;
                    const std::uint32_t b = (p & 31u) * 255u / 31u;
                    dst[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        }
        ANativeWindow_unlockAndPost(window_);
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

    bool openAudio(double sampleRate) {
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

    std::filesystem::path saveRamPath() const { return std::filesystem::path(saveDir_) / (gameKey_ + ".srm"); }
    std::filesystem::path statePath(int slot) const { return std::filesystem::path(stateDir_) / (gameKey_ + ".state" + std::to_string(slot)); }

    void loadSaveRam(CoreApi& api) {
        void* memory = api.getMemoryData(RETRO_MEMORY_SAVE_RAM);
        const std::size_t size = api.getMemorySize(RETRO_MEMORY_SAVE_RAM);
        if (!memory || size == 0) return;
        std::ifstream in(saveRamPath(), std::ios::binary);
        if (in) in.read(static_cast<char*>(memory), static_cast<std::streamsize>(size));
    }

    void saveSaveRam(CoreApi& api) {
        void* memory = api.getMemoryData(RETRO_MEMORY_SAVE_RAM);
        const std::size_t size = api.getMemorySize(RETRO_MEMORY_SAVE_RAM);
        if (!memory || size == 0) return;
        const auto path = saveRamPath();
        const auto temp = path.string() + ".tmp";
        {
            std::ofstream out(temp, std::ios::binary | std::ios::trunc);
            if (!out) return;
            out.write(static_cast<const char*>(memory), static_cast<std::streamsize>(size));
            if (!out.good()) return;
        }
        std::error_code ec;
        std::filesystem::rename(temp, path, ec);
        if (ec) {
            std::filesystem::remove(path, ec);
            ec.clear();
            std::filesystem::rename(temp, path, ec);
        }
    }

    void saveState(CoreApi& api, int slot) {
        const std::size_t size = api.serializeSize();
        if (size == 0) { setStatus("SAVE • core não disponibilizou save state"); return; }
        std::vector<std::uint8_t> data(size);
        if (!api.serialize(data.data(), data.size())) { setStatus("SAVE • falha ao serializar estado"); return; }
        std::ofstream out(statePath(slot), std::ios::binary | std::ios::trunc);
        if (!out) { setStatus("SAVE • falha ao abrir destino"); return; }
        out.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
        setStatus("SAVE • slot " + std::to_string(slot) + " salvo");
    }

    void loadState(CoreApi& api, int slot) {
        std::ifstream in(statePath(slot), std::ios::binary | std::ios::ate);
        if (!in) { setStatus("SAVE • slot " + std::to_string(slot) + " vazio"); return; }
        const auto length = in.tellg();
        if (length <= 0) return;
        in.seekg(0, std::ios::beg);
        std::vector<std::uint8_t> data(static_cast<std::size_t>(length));
        if (!in.read(reinterpret_cast<char*>(data.data()), length) || !api.unserialize(data.data(), data.size())) {
            setStatus("SAVE • estado incompatível ou corrompido");
            return;
        }
        setStatus("SAVE • slot " + std::to_string(slot) + " carregado");
    }

    void runLoop() {
        active_ = this;
        clearCoreLog();
        bool fatalFailure = false;
        setStatus("BOOT 1/6 • validando conteúdo");

        std::error_code ec;
        std::filesystem::create_directories(systemDir_, ec);
        std::filesystem::create_directories(saveDir_, ec);
        std::filesystem::create_directories(stateDir_, ec);

        if (gamePath_.empty() || !std::filesystem::exists(gamePath_, ec)) {
            setStatus("BOOT E02 • arquivo preparado não existe: " + gamePath_);
            running_.store(false, std::memory_order_release);
            active_ = nullptr;
            return;
        }

        setStatus("BOOT 2/6 • carregando biblioteca do core");
        CoreApi api;
        bool initialized = false;
        bool gameLoaded = false;
        if (!loadCore(coreLibrary_, api)) {
            setStatus("BOOT E03 • falha ao carregar PCSX-ReARMed • " + lastCoreLog());
            running_.store(false, std::memory_order_release);
            active_ = nullptr;
            return;
        }
        if (api.apiVersion() != 1) {
            setStatus("BOOT E04 • ABI libretro incompatível");
            unloadCore(api);
            running_.store(false, std::memory_order_release);
            active_ = nullptr;
            return;
        }

        api.setEnvironment(environmentCallback);
        api.setVideoRefresh(videoCallback);
        api.setAudioSample(audioSampleCallback);
        api.setAudioSampleBatch(audioBatchCallback);
        api.setInputPoll(inputPollCallback);
        api.setInputState(inputStateCallback);

        retro_system_info systemInfo{};
        api.getSystemInfo(&systemInfo);
        setStatus("BOOT 3/6 • inicializando PCSX-ReARMed");
        api.init();
        initialized = true;
        api.setControllerPortDevice(0, dualShock_ ? RETRO_DEVICE_PSE_DUALSHOCK : RETRO_DEVICE_JOYPAD);

        setStatus("BOOT 4/6 • aplicando preset e BIOS");
        retro_game_info gameInfo{};
        gameInfo.path = gamePath_.c_str();
        gameInfo.data = nullptr;
        gameInfo.size = 0;
        gameInfo.meta = nullptr;

        setStatus("BOOT 5/6 • lendo imagem do disco");
        if (!api.loadGame(&gameInfo)) {
            fatalFailure = true;
            std::string detail = lastCoreLog();
            std::string message = "BOOT E05 • PCSX-ReARMed recusou o jogo";
            if (!detail.empty()) message += " • " + detail;
            setStatus(message);
        } else {
            gameLoaded = true;
            retro_system_av_info av{};
            api.getSystemAvInfo(&av);
            fps_ = av.timing.fps > 1.0 ? av.timing.fps : 60.0;
            windowHints_.setFrameRate(window_, fps_);
            const bool audioOk = openAudio(av.timing.sample_rate);
            loadSaveRam(api);

            const auto perf = performanceSnapshot();
            std::string label = "BOOT 6/6 • executando";
            if (systemInfo.library_name) label += std::string(" • ") + systemInfo.library_name;
            if (systemInfo.library_version) label += std::string(" ") + systemInfo.library_version;
            label += std::string(" • ") + policyLabel(perf.policy);
            label += dualShock_ ? " • DualShock" : " • Digital";
            if (!audioOk) label += " • sem áudio";
            setStatus(label);

            using clock = std::chrono::steady_clock;
            const auto frameStep = std::chrono::duration_cast<clock::duration>(std::chrono::duration<double>(1.0 / fps_));
            PerformanceHintBridge hint;
            hint.start(performanceTargetNanos(fps_, perf), perf.preferPowerEfficiency);
            auto nextFrame = clock::now();
            telemetryStart_ = nextFrame;
            telemetryEmuFrames_ = 0;
            telemetryUnderruns_ = audioRing_.underruns();
            presentedFrames_.store(0, std::memory_order_relaxed);
            std::uint64_t frames = 0;
            const auto autosaveFrames = static_cast<std::uint64_t>(std::max(60.0, fps_ * 5.0));
            const auto tuneFrames = static_cast<std::uint64_t>(std::max(30.0, fps_));
            std::uint64_t audioStatusUnderruns = audioRing_.underruns();

            while (!stopRequested_.load(std::memory_order_acquire)) {
                const int loadSlot = loadStateRequest_.exchange(-1, std::memory_order_acq_rel);
                if (loadSlot >= 0) loadState(api, loadSlot);
                const int saveSlot = saveStateRequest_.exchange(-1, std::memory_order_acq_rel);
                if (saveSlot >= 0) saveState(api, saveSlot);

                if (audioReconfigureRequested_.exchange(false, std::memory_order_acq_rel)) {
                    const auto changed = performanceSnapshot();
                    hint.apply(performanceTargetNanos(fps_, changed), changed.preferPowerEfficiency);
                    // Keep the live audio stream. Reopening it on every thermal
                    // policy transition creates gaps and can sound like slow audio.
                    adaptAudioBuffer();
                }

                const auto workStart = clock::now();
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
            }

            saveSaveRam(api);
            closeAudio();
        }

        releaseDirectFramebuffer(true);
        if (gameLoaded) api.unloadGame();
        if (initialized) api.deinit();
        unloadCore(api);
        active_ = nullptr;
        running_.store(false, std::memory_order_release);
        if (!fatalFailure) setStatus("Sessão encerrada");
    }

    std::string coreLibrary_;
    std::string gamePath_;
    std::string gameKey_;
    std::string systemDir_;
    std::string saveDir_;
    std::string stateDir_;
    std::string contentDir_;
    ANativeWindow* window_ = nullptr;

    mutable std::mutex statusMutex_;
    std::string status_ = "Preparando…";
    mutable std::mutex performanceMutex_;
    RuntimePerformanceConfig performance_;
    std::unordered_map<std::string, std::string> requestedOptions_;
    std::unordered_map<std::string, std::string> variables_;
    bool dualShock_ = true;

    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<std::uint32_t> buttons_{0};
    std::atomic<int> leftX_{0};
    std::atomic<int> leftY_{0};
    std::atomic<int> rightX_{0};
    std::atomic<int> rightY_{0};
    std::atomic<int> saveStateRequest_{-1};
    std::atomic<int> loadStateRequest_{-1};
    std::atomic<bool> audioReconfigureRequested_{false};

    retro_pixel_format pixelFormat_ = RETRO_PIXEL_FORMAT_0RGB1555;
    double fps_ = 60.0;
    unsigned bufferWidth_ = 0;
    unsigned bufferHeight_ = 0;
    int bufferFormat_ = 0;
    ANativeWindow_Buffer directBuffer_{};
    bool directLocked_ = false;
    void* directData_ = nullptr;
    NativeWindowHints windowHints_;

    AudioRing audioRing_;
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
};

LibretroSession::Impl* LibretroSession::Impl::active_ = nullptr;

LibretroSession::LibretroSession(
    std::string coreLibrary,
    std::string gamePath,
    std::string gameKey,
    std::string systemDir,
    std::string saveDir,
    std::string stateDir,
    ANativeWindow* window,
    RuntimePerformanceConfig performance,
    std::string coreOptions,
    bool dualShock
) : impl_(std::make_unique<Impl>(
        std::move(coreLibrary), std::move(gamePath), std::move(gameKey),
        std::move(systemDir), std::move(saveDir), std::move(stateDir), window,
        performance, std::move(coreOptions), dualShock)) {}

LibretroSession::~LibretroSession() = default;
bool LibretroSession::start() { return impl_->start(); }
void LibretroSession::stop() { impl_->stop(); }
bool LibretroSession::running() const { return impl_->running(); }
void LibretroSession::setButton(unsigned id, bool pressed) { impl_->setButton(id, pressed); }
void LibretroSession::setAnalog(unsigned stick, std::int16_t x, std::int16_t y) { impl_->setAnalog(stick, x, y); }
void LibretroSession::requestSaveState(int slot) { impl_->requestSaveState(slot); }
void LibretroSession::requestLoadState(int slot) { impl_->requestLoadState(slot); }
void LibretroSession::updatePerformanceConfig(RuntimePerformanceConfig performance) { impl_->updatePerformanceConfig(performance); }
std::string LibretroSession::status() const { return impl_->status(); }

bool probeLibretroCore(const char* libraryName) {
    if (!libraryName || !*libraryName) return false;
    void* handle = dlopen(libraryName, RTLD_NOW | RTLD_LOCAL);
    if (!handle) return false;
    const bool valid = dlsym(handle, "retro_api_version") != nullptr &&
                       dlsym(handle, "retro_run") != nullptr &&
                       dlsym(handle, "retro_load_game") != nullptr;
    dlclose(handle);
    return valid;
}
