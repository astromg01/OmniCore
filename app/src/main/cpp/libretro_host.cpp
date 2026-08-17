#include "libretro_host.h"
#include "libretro_abi.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
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
constexpr std::size_t kAudioRingSamples = 32768; // 16384 stereo frames: bounded latency headroom.

void coreLog(enum retro_log_level level, const char* fmt, ...) {
    int priority = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO: priority = ANDROID_LOG_INFO; break;
        case RETRO_LOG_WARN: priority = ANDROID_LOG_WARN; break;
        case RETRO_LOG_ERROR: priority = ANDROID_LOG_ERROR; break;
        default: break;
    }
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priority, kLogTag, fmt, args);
    va_end(args);
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
    if (value.empty()) value = "game";
    return value;
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

    std::uint64_t underruns() const {
        return underruns_.load(std::memory_order_acquire);
    }

    std::uint64_t overruns() const {
        return overruns_.load(std::memory_order_acquire);
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
        count &= ~static_cast<std::size_t>(1); // Keep stereo pairs intact.
        if (count < samples) overruns_.fetch_add(1, std::memory_order_relaxed);
        if (count == 0) return 0;

        const std::size_t start = static_cast<std::size_t>(w % capacity_);
        const std::size_t first = std::min(count, static_cast<std::size_t>(capacity_ - start));
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
            const std::size_t first = std::min(count, static_cast<std::size_t>(capacity_ - start));
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

private:
    std::vector<std::int16_t> data_;
    std::uint64_t capacity_;
    std::atomic<std::uint64_t> read_{0};
    std::atomic<std::uint64_t> write_{0};
    std::atomic<std::uint64_t> underruns_{0};
    std::atomic<std::uint64_t> overruns_{0};
};

class NativeWindowHintBridge {
public:
    NativeWindowHintBridge() {
        lib_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib_) return;
        setFrameRate_ = reinterpret_cast<SetFrameRateFn>(dlsym(lib_, "ANativeWindow_setFrameRate"));
        clearFrameRate_ = reinterpret_cast<ClearFrameRateFn>(dlsym(lib_, "ANativeWindow_clearFrameRate"));
        tryAllocateBuffers_ = reinterpret_cast<TryAllocateBuffersFn>(dlsym(lib_, "ANativeWindow_tryAllocateBuffers"));
    }

    ~NativeWindowHintBridge() {
        if (lib_) dlclose(lib_);
    }

    void setFrameRate(ANativeWindow* window, double fps) {
        if (window && setFrameRate_ && fps > 1.0) {
            // DEFAULT compatibility is appropriate for game content: the system can
            // choose a compatible display refresh rate without forcing a fixed-source cadence.
            setFrameRate_(window, static_cast<float>(fps), 0);
        }
    }

    void clearFrameRate(ANativeWindow* window) {
        if (window && clearFrameRate_) clearFrameRate_(window);
    }

    void tryAllocateBuffers(ANativeWindow* window) {
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

    ~PerformanceHintBridge() {
        close();
        if (lib_) dlclose(lib_);
    }

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
        if (setPowerEfficiency_) {
            setPowerEfficiency_(session_, preferPowerEfficiency);
        }
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
    using reset_t = void (*)();
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
    reset_t reset = nullptr;
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
bool loadSymbol(void* handle, const char* name, T& target) {
    target = reinterpret_cast<T>(dlsym(handle, name));
    if (!target) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Missing libretro symbol: %s", name);
        return false;
    }
    return true;
}

bool loadCoreApi(const std::string& library, CoreApi& api) {
    api.handle = dlopen(library.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!api.handle) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "dlopen(%s) failed: %s", library.c_str(), dlerror());
        return false;
    }

    const bool ok =
        loadSymbol(api.handle, "retro_set_environment", api.setEnvironment) &&
        loadSymbol(api.handle, "retro_set_video_refresh", api.setVideoRefresh) &&
        loadSymbol(api.handle, "retro_set_audio_sample", api.setAudioSample) &&
        loadSymbol(api.handle, "retro_set_audio_sample_batch", api.setAudioSampleBatch) &&
        loadSymbol(api.handle, "retro_set_input_poll", api.setInputPoll) &&
        loadSymbol(api.handle, "retro_set_input_state", api.setInputState) &&
        loadSymbol(api.handle, "retro_init", api.init) &&
        loadSymbol(api.handle, "retro_deinit", api.deinit) &&
        loadSymbol(api.handle, "retro_api_version", api.apiVersion) &&
        loadSymbol(api.handle, "retro_get_system_info", api.getSystemInfo) &&
        loadSymbol(api.handle, "retro_get_system_av_info", api.getSystemAvInfo) &&
        loadSymbol(api.handle, "retro_set_controller_port_device", api.setControllerPortDevice) &&
        loadSymbol(api.handle, "retro_reset", api.reset) &&
        loadSymbol(api.handle, "retro_run", api.run) &&
        loadSymbol(api.handle, "retro_serialize_size", api.serializeSize) &&
        loadSymbol(api.handle, "retro_serialize", api.serialize) &&
        loadSymbol(api.handle, "retro_unserialize", api.unserialize) &&
        loadSymbol(api.handle, "retro_load_game", api.loadGame) &&
        loadSymbol(api.handle, "retro_unload_game", api.unloadGame) &&
        loadSymbol(api.handle, "retro_get_memory_data", api.getMemoryData) &&
        loadSymbol(api.handle, "retro_get_memory_size", api.getMemorySize);

    if (!ok) {
        dlclose(api.handle);
        api.handle = nullptr;
    }
    return ok;
}

void unloadCoreApi(CoreApi& api) {
    if (api.handle) {
        dlclose(api.handle);
        api.handle = nullptr;
    }
}
} // namespace

class LibretroSession::Impl {
public:
    Impl(
        std::string coreLibrary,
        std::string gamePath,
        std::string gameKey,
        std::string systemDir,
        std::string saveDir,
        std::string stateDir,
        ANativeWindow* window,
        RuntimePerformanceConfig performance
    ) : coreLibrary_(std::move(coreLibrary)),
        gamePath_(std::move(gamePath)),
        gameKey_(safeKey(std::move(gameKey))),
        systemDir_(std::move(systemDir)),
        saveDir_(std::move(saveDir)),
        stateDir_(std::move(stateDir)),
        contentDir_(std::filesystem::path(gamePath_).parent_path().string()),
        window_(window),
        performance_(sanitizePerformance(performance)) {}

    ~Impl() {
        stop();
        if (window_) {
            windowHints_.clearFrameRate(window_);
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    bool start() {
        if (running_.load(std::memory_order_acquire)) return false;
        if (!probeLibretroCore(coreLibrary_.c_str())) {
            setStatus("Core PCSX-ReARMed não encontrado neste build.");
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
        if (pressed) {
            buttons_.fetch_or(bit, std::memory_order_acq_rel);
        } else {
            buttons_.fetch_and(~bit, std::memory_order_acq_rel);
        }
    }

    void requestSaveState(int slot) { saveStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }
    void requestLoadState(int slot) { loadStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }

    void updatePerformanceConfig(RuntimePerformanceConfig performance) {
        performance = sanitizePerformance(performance);
        {
            std::lock_guard<std::mutex> lock(performanceMutex_);
            performance_ = performance;
        }
        audioReconfigureRequested_.store(true, std::memory_order_release);
        setStatus(std::string("Otimizador: ") + policyLabel(performance.policy));
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
        const double frameNanos = 1'000'000'000.0 / std::max(1.0, fps);
        const double budget = config.policy == 2 ? 0.82 : (config.policy == 0 ? 0.96 : 0.90);
        return static_cast<std::int64_t>(frameNanos * budget);
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
        active_->audioRing_.push(stereo, 2);
    }

    static std::size_t audioBatchCallback(const std::int16_t* data, std::size_t frames) {
        if (!active_ || !data) return 0;
        // libretro expects the number of stereo frames actually accepted. Returning
        // the requested count after a saturated ring hides backpressure from the core.
        return active_->audioRing_.push(data, frames * 2) / 2;
    }

    static void inputPollCallback() {}

    static std::int16_t inputStateCallback(unsigned port, unsigned device, unsigned /* index */, unsigned id) {
        if (!active_ || port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
        const std::uint32_t mask = active_->buttons_.load(std::memory_order_acquire);
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<std::int16_t>(mask & 0xFFFFu);
        if (id >= 16) return 0;
        return (mask & (1u << id)) ? 1 : 0;
    }

    static aaudio_data_callback_result_t aaudioDataCallback(
        AAudioStream* /* stream */, void* userData, void* audioData, std::int32_t numFrames) {
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
                    if (msg->msg) setStatus(msg->msg);
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
            case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
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
                return pixelFormat_ == RETRO_PIXEL_FORMAT_0RGB1555 ||
                       pixelFormat_ == RETRO_PIXEL_FORMAT_XRGB8888 ||
                       pixelFormat_ == RETRO_PIXEL_FORMAT_RGB565;

            case RETRO_ENVIRONMENT_GET_VARIABLE: {
                if (!data) return false;
                auto* variable = static_cast<retro_variable*>(data);
                if (!variable->key) return false;
                const auto it = variables_.find(variable->key);
                variable->value = it == variables_.end() ? nullptr : it->second.c_str();
                return variable->value != nullptr;
            }

            case RETRO_ENVIRONMENT_SET_VARIABLES:
                registerLegacyVariables(static_cast<const retro_variable*>(data));
                return true;

            case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
                if (data) *static_cast<bool*>(data) = false;
                return data != nullptr;

            // Force cores to use the stable legacy variable interface. This keeps
            // the first frontend milestone intentionally small and deterministic.
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
                    fps_ = info->timing.fps > 1.0 ? info->timing.fps : fps_;
                }
                return true;

            case RETRO_ENVIRONMENT_SET_GEOMETRY:
                return true;

            // Disk swapping and VFS are intentionally deferred. The Android core
            // is compiled with USE_LIBRETRO_VFS=0, and v0.2 targets single-file games.
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
            case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
            case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:
            case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK:
                return false;

            default:
                return false;
        }
    }

    void registerLegacyVariables(const retro_variable* vars) {
        if (!vars) return;
        variables_.clear();
        for (const retro_variable* var = vars; var->key; ++var) {
            if (!var->value) continue;
            std::string definition(var->value);
            const auto semicolon = definition.find(';');
            if (semicolon == std::string::npos) continue;
            std::string values = trim(definition.substr(semicolon + 1));
            const auto pipe = values.find('|');
            std::string first = trim(values.substr(0, pipe));
            if (!first.empty()) variables_[var->key] = first;
        }
    }

    void setStatus(std::string value) {
        std::lock_guard<std::mutex> lock(statusMutex_);
        status_ = std::move(value);
    }

    bool openAudio(double sampleRate) {
        closeAudio();
        audioRing_.clear();
        audioSampleRate_ = sampleRate;
        const RuntimePerformanceConfig config = performanceSnapshot();

        auto tryOpen = [&](aaudio_sharing_mode_t sharingMode) -> bool {
            AAudioStreamBuilder* builder = nullptr;
            if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) return false;

            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSharingMode(builder, sharingMode);
            AAudioStreamBuilder_setPerformanceMode(
                builder,
                config.policy == 0 ? AAUDIO_PERFORMANCE_MODE_POWER_SAVING : AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
            );
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            if (sampleRate > 1000.0) {
                AAudioStreamBuilder_setSampleRate(builder, static_cast<std::int32_t>(sampleRate));
            }
            AAudioStreamBuilder_setDataCallback(builder, aaudioDataCallback, this);

            const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &audioStream_);
            AAudioStreamBuilder_delete(builder);
            if (result != AAUDIO_OK || !audioStream_) {
                audioStream_ = nullptr;
                return false;
            }

            const std::int32_t burst = AAudioStream_getFramesPerBurst(audioStream_);
            if (burst > 0) {
                appliedAudioBufferBursts_ = config.audioBufferBursts;
                const std::int32_t requested = burst * appliedAudioBufferBursts_;
                AAudioStream_setBufferSizeInFrames(audioStream_, requested);
            }
            lastXRunCount_ = std::max<std::int32_t>(0, AAudioStream_getXRunCount(audioStream_));
            stableAudioChecks_ = 0;

            if (AAudioStream_requestStart(audioStream_) != AAUDIO_OK) {
                AAudioStream_close(audioStream_);
                audioStream_ = nullptr;
                return false;
            }
            return true;
        };

        // Exclusive can lower latency on some devices but is never mandatory.
        // Fall back to shared mode immediately to avoid device-specific failures.
        if (config.tryExclusiveAudio && tryOpen(AAUDIO_SHARING_MODE_EXCLUSIVE)) return true;
        return tryOpen(AAUDIO_SHARING_MODE_SHARED);
    }

    void closeAudio() {
        if (!audioStream_) return;
        AAudioStream_requestStop(audioStream_);
        AAudioStream_close(audioStream_);
        audioStream_ = nullptr;
        appliedAudioBufferBursts_ = 0;
        lastXRunCount_ = 0;
        lastRingUnderruns_ = 0;
        stableAudioChecks_ = 0;
        audioRing_.clear();
    }

    void adaptAudioBuffer() {
        if (!audioStream_) return;
        const std::int32_t burst = AAudioStream_getFramesPerBurst(audioStream_);
        if (burst <= 0) return;

        const RuntimePerformanceConfig config = performanceSnapshot();
        const std::int32_t currentXRuns = std::max<std::int32_t>(0, AAudioStream_getXRunCount(audioStream_));
        const std::uint64_t currentRingUnderruns = audioRing_.underruns();
        int nextBursts = appliedAudioBufferBursts_ > 0 ? appliedAudioBufferBursts_ : config.audioBufferBursts;

        if (currentXRuns > lastXRunCount_ || currentRingUnderruns > lastRingUnderruns_) {
            // Prioritize continuity when either Android reports an xrun or our own
            // callback had to inject silence because the emulation side starved it.
            // The adjustment is local to this stream and shrinks after stability.
            nextBursts = std::min(8, std::max(config.audioBufferBursts, nextBursts + 1));
            stableAudioChecks_ = 0;
        } else if (nextBursts > config.audioBufferBursts) {
            ++stableAudioChecks_;
            if (stableAudioChecks_ >= 8) {
                --nextBursts;
                stableAudioChecks_ = 0;
            }
        } else {
            stableAudioChecks_ = 0;
            nextBursts = config.audioBufferBursts;
        }

        if (nextBursts != appliedAudioBufferBursts_) {
            AAudioStream_setBufferSizeInFrames(audioStream_, burst * nextBursts);
            appliedAudioBufferBursts_ = nextBursts;
        }
        lastXRunCount_ = currentXRuns;
        lastRingUnderruns_ = currentRingUnderruns;
    }

    void renderFrame(const void* data, unsigned width, unsigned height, std::size_t pitch) {
        if (!data || !window_ || width == 0 || height == 0) return;
        if (data == reinterpret_cast<const void*>(static_cast<std::intptr_t>(-1))) return;

        const int desiredFormat = pixelFormat_ == RETRO_PIXEL_FORMAT_RGB565
            ? WINDOW_FORMAT_RGB_565
            : WINDOW_FORMAT_RGBA_8888;

        if (bufferWidth_ != width || bufferHeight_ != height || bufferFormat_ != desiredFormat) {
            if (ANativeWindow_setBuffersGeometry(
                    window_, static_cast<int>(width), static_cast<int>(height), desiredFormat) != 0) {
                return;
            }
            bufferWidth_ = width;
            bufferHeight_ = height;
            bufferFormat_ = desiredFormat;
            windowHints_.tryAllocateBuffers(window_);
        }

        ANativeWindow_Buffer buffer{};
        if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) return;

        const unsigned drawWidth = std::min(width, static_cast<unsigned>(buffer.width));
        const unsigned drawHeight = std::min(height, static_cast<unsigned>(buffer.height));
        const auto* srcBase = static_cast<const std::uint8_t*>(data);

        if (pixelFormat_ == RETRO_PIXEL_FORMAT_RGB565 && buffer.format == WINDOW_FORMAT_RGB_565) {
            // Fast path: PCSX-ReARMed commonly outputs RGB565. Copy complete rows
            // directly instead of converting every pixel to RGBA on the CPU.
            auto* dstBase = static_cast<std::uint8_t*>(buffer.bits);
            const std::size_t copyBytes = static_cast<std::size_t>(drawWidth) * 2;
            for (unsigned y = 0; y < drawHeight; ++y) {
                std::memcpy(
                    dstBase + static_cast<std::size_t>(y) * buffer.stride * 2,
                    srcBase + static_cast<std::size_t>(y) * pitch,
                    copyBytes
                );
            }
        } else {
            auto* dstBase = static_cast<std::uint8_t*>(buffer.bits);
            for (unsigned y = 0; y < drawHeight; ++y) {
                std::uint8_t* dst = dstBase + static_cast<std::size_t>(y) * buffer.stride * 4;
                const auto* src8 = srcBase + static_cast<std::size_t>(y) * pitch;

                if (pixelFormat_ == RETRO_PIXEL_FORMAT_XRGB8888) {
                    const auto* src = reinterpret_cast<const std::uint32_t*>(src8);
                    for (unsigned x = 0; x < drawWidth; ++x) {
                        const std::uint32_t p = src[x];
                        dst[x * 4 + 0] = static_cast<std::uint8_t>((p >> 16) & 0xFF);
                        dst[x * 4 + 1] = static_cast<std::uint8_t>((p >> 8) & 0xFF);
                        dst[x * 4 + 2] = static_cast<std::uint8_t>(p & 0xFF);
                        dst[x * 4 + 3] = 0xFF;
                    }
                } else {
                    const auto* src = reinterpret_cast<const std::uint16_t*>(src8);
                    for (unsigned x = 0; x < drawWidth; ++x) {
                        const std::uint16_t p = src[x];
                        const unsigned r5 = (p >> 10) & 0x1F;
                        const unsigned g5 = (p >> 5) & 0x1F;
                        const unsigned b5 = p & 0x1F;
                        dst[x * 4 + 0] = static_cast<std::uint8_t>((r5 << 3) | (r5 >> 2));
                        dst[x * 4 + 1] = static_cast<std::uint8_t>((g5 << 3) | (g5 >> 2));
                        dst[x * 4 + 2] = static_cast<std::uint8_t>((b5 << 3) | (b5 >> 2));
                        dst[x * 4 + 3] = 0xFF;
                    }
                }
            }
        }

        ANativeWindow_unlockAndPost(window_);
    }

    std::filesystem::path saveRamPath() const {
        return std::filesystem::path(saveDir_) / (gameKey_ + ".srm");
    }

    std::filesystem::path statePath(int slot) const {
        return std::filesystem::path(stateDir_) / (gameKey_ + ".state" + std::to_string(slot));
    }

    void loadSaveRam(CoreApi& api) {
        void* memory = api.getMemoryData(RETRO_MEMORY_SAVE_RAM);
        const std::size_t size = api.getMemorySize(RETRO_MEMORY_SAVE_RAM);
        if (!memory || size == 0) return;

        std::ifstream in(saveRamPath(), std::ios::binary);
        if (!in) return;
        in.read(static_cast<char*>(memory), static_cast<std::streamsize>(size));
    }

    void saveSaveRam(CoreApi& api) {
        void* memory = api.getMemoryData(RETRO_MEMORY_SAVE_RAM);
        const std::size_t size = api.getMemorySize(RETRO_MEMORY_SAVE_RAM);
        if (!memory || size == 0) return;

        const auto path = saveRamPath();
        const auto tmp = path.string() + ".tmp";
        {
            std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
            if (!out) return;
            out.write(static_cast<const char*>(memory), static_cast<std::streamsize>(size));
            if (!out.good()) return;
        }
        std::error_code ec;
        std::filesystem::rename(tmp, path, ec);
        if (ec) {
            std::filesystem::remove(path, ec);
            ec.clear();
            std::filesystem::rename(tmp, path, ec);
        }
    }

    void saveState(CoreApi& api, int slot) {
        const std::size_t size = api.serializeSize();
        if (size == 0) {
            setStatus("Este core não disponibilizou save state.");
            return;
        }
        std::vector<std::uint8_t> buffer(size);
        if (!api.serialize(buffer.data(), buffer.size())) {
            setStatus("Falha ao criar save state.");
            return;
        }
        std::ofstream out(statePath(slot), std::ios::binary | std::ios::trunc);
        if (!out) {
            setStatus("Não consegui gravar o save state.");
            return;
        }
        out.write(reinterpret_cast<const char*>(buffer.data()), static_cast<std::streamsize>(buffer.size()));
        setStatus("Save state salvo no slot " + std::to_string(slot) + ".");
    }

    void loadState(CoreApi& api, int slot) {
        const auto path = statePath(slot);
        std::ifstream in(path, std::ios::binary | std::ios::ate);
        if (!in) {
            setStatus("Nenhum save state no slot " + std::to_string(slot) + ".");
            return;
        }
        const std::streamsize length = in.tellg();
        if (length <= 0) {
            setStatus("Save state inválido.");
            return;
        }
        in.seekg(0, std::ios::beg);
        std::vector<std::uint8_t> buffer(static_cast<std::size_t>(length));
        if (!in.read(reinterpret_cast<char*>(buffer.data()), length)) {
            setStatus("Falha ao ler save state.");
            return;
        }
        if (!api.unserialize(buffer.data(), buffer.size())) {
            setStatus("O core recusou o save state.");
            return;
        }
        setStatus("Save state carregado do slot " + std::to_string(slot) + ".");
    }

    void runLoop() {
        active_ = this;
        setStatus("Carregando PCSX-ReARMed…");

        std::error_code ec;
        std::filesystem::create_directories(systemDir_, ec);
        std::filesystem::create_directories(saveDir_, ec);
        std::filesystem::create_directories(stateDir_, ec);

        CoreApi api;
        bool initialized = false;
        bool gameLoaded = false;

        if (!loadCoreApi(coreLibrary_, api)) {
            setStatus("Falha ao abrir o core PCSX-ReARMed.");
            running_.store(false, std::memory_order_release);
            active_ = nullptr;
            return;
        }

        if (api.apiVersion() != 1) {
            setStatus("Versão da API libretro incompatível.");
            unloadCoreApi(api);
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

        api.init();
        initialized = true;
        api.setControllerPortDevice(0, RETRO_DEVICE_JOYPAD);

        retro_game_info gameInfo{};
        gameInfo.path = gamePath_.c_str();
        gameInfo.data = nullptr;
        gameInfo.size = 0;
        gameInfo.meta = nullptr;

        if (!api.loadGame(&gameInfo)) {
            setStatus("PCSX-ReARMed não conseguiu carregar este arquivo.");
        } else {
            gameLoaded = true;
            retro_system_av_info av{};
            api.getSystemAvInfo(&av);
            fps_ = av.timing.fps > 1.0 ? av.timing.fps : 60.0;
            windowHints_.setFrameRate(window_, fps_);
            const bool audioOk = openAudio(av.timing.sample_rate);
            loadSaveRam(api);

            const RuntimePerformanceConfig initialPerformance = performanceSnapshot();
            std::string label = std::string("PS1 executando • ") + policyLabel(initialPerformance.policy);
            if (systemInfo.library_name) label += std::string(" • ") + systemInfo.library_name;
            if (systemInfo.library_version) label += std::string(" ") + systemInfo.library_version;
            if (!audioOk) label += " • áudio indisponível";
            setStatus(label);

            using clock = std::chrono::steady_clock;
            const auto frameStep = std::chrono::duration_cast<clock::duration>(std::chrono::duration<double>(1.0 / fps_));
            PerformanceHintBridge performanceHint;
            performanceHint.start(
                performanceTargetNanos(fps_, initialPerformance),
                initialPerformance.preferPowerEfficiency
            );
            auto nextFrame = clock::now();
            std::uint64_t frameCounter = 0;
            double workRatioEwma = 0.0;
            const double frameBudgetNanos = 1'000'000'000.0 / fps_;
            const std::uint64_t autosaveFrames = static_cast<std::uint64_t>(std::max(60.0, fps_ * 5.0));
            const std::uint64_t audioTuneFrames = static_cast<std::uint64_t>(std::max(30.0, fps_));

            while (!stopRequested_.load(std::memory_order_acquire)) {
                const int loadSlot = loadStateRequest_.exchange(-1, std::memory_order_acq_rel);
                if (loadSlot >= 0) loadState(api, loadSlot);

                const int saveSlot = saveStateRequest_.exchange(-1, std::memory_order_acq_rel);
                if (saveSlot >= 0) saveState(api, saveSlot);

                if (audioReconfigureRequested_.exchange(false, std::memory_order_acq_rel)) {
                    const RuntimePerformanceConfig changed = performanceSnapshot();
                    performanceHint.apply(
                        performanceTargetNanos(fps_, changed),
                        changed.preferPowerEfficiency
                    );
                    if (audioSampleRate_ > 1000.0) openAudio(audioSampleRate_);
                }

                const auto workStart = clock::now();
                api.run();
                const auto workEnd = clock::now();
                const auto actualWorkNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(workEnd - workStart).count();
                performanceHint.report(actualWorkNanos);
                const double workRatio = std::clamp(static_cast<double>(actualWorkNanos) / frameBudgetNanos, 0.0, 2.0);
                workRatioEwma = workRatioEwma == 0.0 ? workRatio : (workRatioEwma * 0.92 + workRatio * 0.08);
                ++frameCounter;
                if (frameCounter % autosaveFrames == 0) saveSaveRam(api);
                if (frameCounter % audioTuneFrames == 0) adaptAudioBuffer();

                nextFrame += frameStep;
                const auto now = clock::now();
                if (nextFrame > now) {
                    const RuntimePerformanceConfig config = performanceSnapshot();
                    if (config.aggressiveFramePacing && workRatioEwma < 0.86 &&
                        nextFrame - now > std::chrono::microseconds(350)) {
                        // Use a very short final yield only when there is CPU headroom.
                        // Heavy frames skip it so the pacer does not waste thermal budget.
                        std::this_thread::sleep_until(nextFrame - std::chrono::microseconds(140));
                        while (clock::now() < nextFrame) std::this_thread::yield();
                    } else {
                        std::this_thread::sleep_until(nextFrame);
                    }
                } else if (now - nextFrame > std::chrono::milliseconds(250)) {
                    // Reset rather than attempting a huge catch-up burst after a stall.
                    nextFrame = now;
                }

            }

            saveSaveRam(api);
            closeAudio();
        }

        if (gameLoaded) api.unloadGame();
        if (initialized) api.deinit();
        unloadCoreApi(api);

        active_ = nullptr;
        running_.store(false, std::memory_order_release);
        setStatus("Parado");
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
    std::atomic<bool> audioReconfigureRequested_{false};
    std::unordered_map<std::string, std::string> variables_;

    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<std::uint32_t> buttons_{0};
    std::atomic<int> saveStateRequest_{-1};
    std::atomic<int> loadStateRequest_{-1};

    retro_pixel_format pixelFormat_ = RETRO_PIXEL_FORMAT_0RGB1555;
    double fps_ = 60.0;
    unsigned bufferWidth_ = 0;
    unsigned bufferHeight_ = 0;
    int bufferFormat_ = 0;

    NativeWindowHintBridge windowHints_;
    AudioRing audioRing_;
    AAudioStream* audioStream_ = nullptr;
    double audioSampleRate_ = 0.0;
    int appliedAudioBufferBursts_ = 0;
    std::int32_t lastXRunCount_ = 0;
    std::uint64_t lastRingUnderruns_ = 0;
    int stableAudioChecks_ = 0;
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
    RuntimePerformanceConfig performance
) : impl_(std::make_unique<Impl>(
        std::move(coreLibrary),
        std::move(gamePath),
        std::move(gameKey),
        std::move(systemDir),
        std::move(saveDir),
        std::move(stateDir),
        window,
        performance)) {}

LibretroSession::~LibretroSession() = default;

bool LibretroSession::start() { return impl_->start(); }
void LibretroSession::stop() { impl_->stop(); }
bool LibretroSession::running() const { return impl_->running(); }
void LibretroSession::setButton(unsigned id, bool pressed) { impl_->setButton(id, pressed); }
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
