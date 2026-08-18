#include "n64_libretro_host.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

namespace omnicore::n64 {
namespace {
constexpr const char* kLogTag = "OmniCoreN64";
constexpr const char* kCoreSoname = "libmupen64plus_next_libretro.so";
constexpr std::size_t kAudioRingSamples = 65536;
constexpr std::uint64_t kFnvOffset = 1469598103934665603ull;
constexpr std::uint64_t kFnvPrime = 1099511628211ull;

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif

void logPrint(int priority, const char* fmt, ...) {
    char buffer[1024]{};
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    __android_log_print(priority, kLogTag, "%s", buffer);
}

void coreLog(enum retro_log_level level, const char* fmt, ...) {
    int priority = ANDROID_LOG_INFO;
    if (level == RETRO_LOG_DEBUG) priority = ANDROID_LOG_DEBUG;
    if (level == RETRO_LOG_WARN) priority = ANDROID_LOG_WARN;
    if (level == RETRO_LOG_ERROR) priority = ANDROID_LOG_ERROR;
    char buffer[1024]{};
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    __android_log_print(priority, kLogTag, "core: %s", buffer);
}

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

std::string boolOption(bool value) { return value ? "True" : "False"; }

struct AnalogVector final {
    float x = 0.0f;
    float y = 0.0f;
};

AnalogVector shapeAnalog(float x, float y, int deadzonePercent, int sensitivityPercent, bool precision, const std::string& profile) {
    x = std::clamp(x, -1.0f, 1.0f);
    y = std::clamp(y, -1.0f, 1.0f);
    if (!precision) return {x, y};
    const float magnitude = std::hypot(x, y);
    if (magnitude <= 0.00001f) return {};
    const float deadzone = std::clamp(static_cast<float>(deadzonePercent) / 100.0f, 0.0f, 0.30f);
    if (magnitude <= deadzone) return {};
    const float sourceMagnitude = std::min(1.0f, magnitude);
    float normalized = (sourceMagnitude - deadzone) / std::max(0.01f, 1.0f - deadzone);
    normalized = std::clamp(normalized, 0.0f, 1.0f);

    const float userSensitivity = std::clamp(
        static_cast<float>(sensitivityPercent) / 100.0f, 0.70f, 1.30f);
    if (profile == "racing") {
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
        // Generic ComfortAnalog keeps the Alpha 19 behavior that tested well
        // for Zelda and normal analog titles.
        constexpr float kFineZone = 0.28f;
        if (normalized < kFineZone) {
            const float local = normalized / kFineZone;
            normalized = std::pow(local, 1.08f) * kFineZone;
        }
        normalized = std::min(1.0f, normalized / 0.985f);
        normalized *= userSensitivity;
    }
    normalized = std::clamp(normalized, 0.0f, 1.0f);
    return {x / magnitude * normalized, y / magnitude * normalized};
}

bool ensureDirectoryTree(const std::string& path) {
    if (path.empty()) return false;
    for (std::size_t i = 1; i <= path.size(); ++i) {
        if (i != path.size() && path[i] != '/') continue;
        const std::string part = path.substr(0, i);
        if (part.empty()) continue;
        if (::mkdir(part.c_str(), 0700) != 0 && errno != EEXIST) return false;
    }
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode) && ::access(path.c_str(), W_OK) == 0;
}

std::size_t warmDirectoryPages(const std::string& path, std::size_t budgetBytes) {
    if (path.empty() || budgetBytes == 0) return 0;
    DIR* dir = ::opendir(path.c_str());
    if (!dir) return 0;

    struct WarmFile final {
        std::string path;
        std::size_t size = 0;
        std::int64_t modified = 0;
    };
    std::vector<WarmFile> files;
    while (dirent* entry = ::readdir(dir)) {
        if (entry->d_name[0] == '.') continue;
        const std::string filePath = path + "/" + entry->d_name;
        struct stat st {};
        if (::stat(filePath.c_str(), &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0) continue;
        files.push_back({
            filePath,
            static_cast<std::size_t>(st.st_size),
            static_cast<std::int64_t>(st.st_mtime)
        });
    }
    ::closedir(dir);
    std::sort(files.begin(), files.end(), [](const WarmFile& a, const WarmFile& b) {
        if (a.modified != b.modified) return a.modified > b.modified;
        return a.size > b.size;
    });

    const long pageSize = std::max<long>(4096, ::sysconf(_SC_PAGESIZE));
    std::size_t warmed = 0;
    for (const auto& file : files) {
        if (warmed >= budgetBytes) break;
        const int fd = ::open(file.path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        const std::size_t remaining = budgetBytes - warmed;
        const std::size_t fileBudget = std::min(file.size, remaining);
#ifdef POSIX_FADV_WILLNEED
        ::posix_fadvise(fd, 0, static_cast<off_t>(fileBudget), POSIX_FADV_WILLNEED);
#endif
        std::uint8_t byte = 0;
        for (std::size_t offset = 0; offset < fileBudget; offset += static_cast<std::size_t>(pageSize)) {
            if (::pread(fd, &byte, 1, static_cast<off_t>(offset)) != 1) break;
            warmed += std::min<std::size_t>(static_cast<std::size_t>(pageSize), fileBudget - offset);
        }
        ::close(fd);
    }
    return warmed;
}

std::int16_t axisFromFloat(float value) {
    return static_cast<std::int16_t>(std::lround(std::clamp(value, -1.0f, 1.0f) * 32767.0f));
}

class PerformanceHintSession final {
public:
    using GetManagerFn = void* (*)();
    using CreateSessionFn = void* (*)(void*, const std::int32_t*, std::size_t, std::int64_t);
    using ReportFn = int (*)(void*, std::int64_t);
    using UpdateTargetFn = int (*)(void*, std::int64_t);
    using NotifyWorkloadFn = int (*)(void*, bool, bool, const char*);
    using SetNativeSurfacesFn = int (*)(void*, ANativeWindow* const*, std::size_t, void* const*, std::size_t);
    using CloseFn = void (*)(void*);

    bool open(double fps) {
        close();
        library_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!library_) return false;
        getManager_ = reinterpret_cast<GetManagerFn>(dlsym(library_, "APerformanceHint_getManager"));
        createSession_ = reinterpret_cast<CreateSessionFn>(dlsym(library_, "APerformanceHint_createSession"));
        report_ = reinterpret_cast<ReportFn>(dlsym(library_, "APerformanceHint_reportActualWorkDuration"));
        updateTarget_ = reinterpret_cast<UpdateTargetFn>(dlsym(library_, "APerformanceHint_updateTargetWorkDuration"));
        notifySpike_ = reinterpret_cast<NotifyWorkloadFn>(dlsym(library_, "APerformanceHint_notifyWorkloadSpike"));
        notifyIncrease_ = reinterpret_cast<NotifyWorkloadFn>(dlsym(library_, "APerformanceHint_notifyWorkloadIncrease"));
        notifyReset_ = reinterpret_cast<NotifyWorkloadFn>(dlsym(library_, "APerformanceHint_notifyWorkloadReset"));
        setNativeSurfaces_ = reinterpret_cast<SetNativeSurfacesFn>(dlsym(library_, "APerformanceHint_setNativeSurfaces"));
        closeSession_ = reinterpret_cast<CloseFn>(dlsym(library_, "APerformanceHint_closeSession"));
        if (!getManager_ || !createSession_ || !report_ || !closeSession_) { close(); return false; }
        manager_ = getManager_();
        if (!manager_) { close(); return false; }
        const std::int32_t tid = static_cast<std::int32_t>(syscall(__NR_gettid));
        baseTargetNs_ = static_cast<std::int64_t>(1.0e9 / std::clamp(fps, 40.0, 75.0));
        targetNs_ = baseTargetNs_;
        session_ = createSession_(manager_, &tid, 1u, targetNs_);
        return session_ != nullptr;
    }

    void report(std::int64_t actualNs) {
        if (session_ && report_ && actualNs > 0) report_(session_, actualNs);
    }

    void setTargetScale(double scale) {
        if (!session_ || !updateTarget_ || baseTargetNs_ <= 0) return;
        scale = std::clamp(scale, 0.70, 1.00);
        const auto requested = std::max<std::int64_t>(
            1, static_cast<std::int64_t>(std::llround(static_cast<double>(baseTargetNs_) * scale)));
        const auto tolerance = std::max<std::int64_t>(1, baseTargetNs_ / 100);
        if (std::llabs(requested - targetNs_) <= tolerance) return;
        if (updateTarget_(session_, requested) == 0) targetNs_ = requested;
    }

    bool bindSurface(ANativeWindow* window) {
        if (!session_ || !setNativeSurfaces_ || !window) return false;
        ANativeWindow* windows[] = {window};
        return setNativeSurfaces_(session_, windows, 1u, nullptr, 0u) == 0;
    }

    void notifyReset(bool cpu, bool gpu, const char* identifier) {
        if (session_ && notifyReset_ && identifier) notifyReset_(session_, cpu, gpu, identifier);
    }

    void notifySpike(bool cpu, bool gpu, const char* identifier) {
        if (!session_ || !notifySpike_ || !identifier) return;
        const auto now = std::chrono::steady_clock::now();
        if (lastSpikeAt_.time_since_epoch().count() != 0 &&
            now - lastSpikeAt_ < std::chrono::milliseconds(700)) return;
        notifySpike_(session_, cpu, gpu, identifier);
        lastSpikeAt_ = now;
    }

    void notifyIncrease(bool cpu, bool gpu, const char* identifier) {
        if (!session_ || !notifyIncrease_ || !identifier) return;
        const auto now = std::chrono::steady_clock::now();
        if (lastIncreaseAt_.time_since_epoch().count() != 0 &&
            now - lastIncreaseAt_ < std::chrono::seconds(10)) return;
        notifyIncrease_(session_, cpu, gpu, identifier);
        lastIncreaseAt_ = now;
    }

    void close() {
        if (session_ && closeSession_) closeSession_(session_);
        session_ = nullptr;
        manager_ = nullptr;
        getManager_ = nullptr;
        createSession_ = nullptr;
        report_ = nullptr;
        updateTarget_ = nullptr;
        notifySpike_ = nullptr;
        notifyIncrease_ = nullptr;
        notifyReset_ = nullptr;
        setNativeSurfaces_ = nullptr;
        closeSession_ = nullptr;
        if (library_) dlclose(library_);
        library_ = nullptr;
        targetNs_ = 0;
        baseTargetNs_ = 0;
        lastSpikeAt_ = {};
        lastIncreaseAt_ = {};
    }

    bool active() const { return session_ != nullptr; }
    bool burstCapable() const { return session_ != nullptr && notifySpike_ != nullptr; }
    ~PerformanceHintSession() { close(); }

private:
    void* library_ = nullptr;
    void* manager_ = nullptr;
    void* session_ = nullptr;
    GetManagerFn getManager_ = nullptr;
    CreateSessionFn createSession_ = nullptr;
    ReportFn report_ = nullptr;
    UpdateTargetFn updateTarget_ = nullptr;
    NotifyWorkloadFn notifySpike_ = nullptr;
    NotifyWorkloadFn notifyIncrease_ = nullptr;
    NotifyWorkloadFn notifyReset_ = nullptr;
    SetNativeSurfacesFn setNativeSurfaces_ = nullptr;
    CloseFn closeSession_ = nullptr;
    std::int64_t targetNs_ = 0;
    std::int64_t baseTargetNs_ = 0;
    std::chrono::steady_clock::time_point lastSpikeAt_{};
    std::chrono::steady_clock::time_point lastIncreaseAt_{};
};

std::uint64_t hashBytes(const void* data, std::size_t size) {
    if (!data || size == 0) return 0;
    const auto* bytes = static_cast<const std::uint8_t*>(data);
    std::uint64_t hash = kFnvOffset;
    for (std::size_t i = 0; i < size; ++i) {
        hash ^= bytes[i];
        hash *= kFnvPrime;
    }
    return hash;
}

bool readWholeFile(const std::string& path, std::vector<std::uint8_t>& output) {
    output.clear();
    if (path.empty()) return false;
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        ::close(fd);
        return false;
    }
    output.resize(static_cast<std::size_t>(st.st_size));
    std::size_t done = 0;
    while (done < output.size()) {
        const ssize_t count = ::read(fd, output.data() + done, output.size() - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            ::close(fd);
            output.clear();
            return false;
        }
        done += static_cast<std::size_t>(count);
    }
    ::close(fd);
    return true;
}

bool writeWholeFileAtomic(const std::string& path, const void* data, std::size_t size) {
    if (path.empty() || !data || size == 0) return false;
    const std::string temp = path + ".tmp";
    const int fd = ::open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    const auto* bytes = static_cast<const std::uint8_t*>(data);
    std::size_t done = 0;
    bool ok = true;
    while (done < size) {
        const ssize_t count = ::write(fd, bytes + done, size - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            ok = false;
            break;
        }
        done += static_cast<std::size_t>(count);
    }
    if (ok && ::fsync(fd) != 0) ok = false;
    if (::close(fd) != 0) ok = false;
    if (ok) {
        if (::rename(temp.c_str(), path.c_str()) != 0) ok = false;
    }
    if (!ok) ::unlink(temp.c_str());
    return ok;
}

class AudioRing final {
public:
    void clear() {
        read_.store(0, std::memory_order_release);
        write_.store(0, std::memory_order_release);
        underruns_.store(0, std::memory_order_release);
        overruns_.store(0, std::memory_order_release);
        data_.fill(0);
    }

    std::size_t push(const std::int16_t* input, std::size_t samples) {
        if (!input || samples < 2) return 0;
        samples &= ~static_cast<std::size_t>(1);
        const std::uint64_t read = read_.load(std::memory_order_acquire);
        const std::uint64_t write = write_.load(std::memory_order_relaxed);
        const std::uint64_t used = write - read;
        if (used >= data_.size()) {
            overruns_.fetch_add(1, std::memory_order_relaxed);
            return 0;
        }
        std::size_t count = std::min(samples, data_.size() - static_cast<std::size_t>(used));
        count &= ~static_cast<std::size_t>(1);
        if (count < samples) overruns_.fetch_add(1, std::memory_order_relaxed);
        if (count == 0) return 0;
        const std::size_t start = static_cast<std::size_t>(write % data_.size());
        const std::size_t first = std::min(count, data_.size() - start);
        std::memcpy(data_.data() + start, input, first * sizeof(std::int16_t));
        if (first < count) {
            std::memcpy(data_.data(), input + first, (count - first) * sizeof(std::int16_t));
        }
        write_.store(write + count, std::memory_order_release);
        return count;
    }

    std::size_t pop(std::int16_t* output, std::size_t samples) {
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

    std::size_t availableSamples() const {
        const std::uint64_t read = read_.load(std::memory_order_acquire);
        const std::uint64_t write = write_.load(std::memory_order_acquire);
        return static_cast<std::size_t>(
            std::min<std::uint64_t>(data_.size(), write - read));
    }

    std::uint64_t underruns() const { return underruns_.load(std::memory_order_acquire); }
    std::uint64_t overruns() const { return overruns_.load(std::memory_order_acquire); }
    constexpr std::size_t capacitySamples() const { return kAudioRingSamples; }

private:
    std::array<std::int16_t, kAudioRingSamples> data_{};
    std::atomic<std::uint64_t> read_{0};
    std::atomic<std::uint64_t> write_{0};
    std::atomic<std::uint64_t> underruns_{0};
    std::atomic<std::uint64_t> overruns_{0};
};

class MappedFile final {
public:
    bool openReadOnly(const std::string& path) {
        reset();
        fd_ = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd_ < 0) return false;
        struct stat st {};
        if (fstat(fd_, &st) != 0 || st.st_size <= 0) {
            reset();
            return false;
        }
        size_ = static_cast<std::size_t>(st.st_size);
        data_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
        if (data_ == MAP_FAILED) {
            data_ = nullptr;
            reset();
            return false;
        }
#ifdef POSIX_FADV_WILLNEED
        posix_fadvise(fd_, 0, 0, POSIX_FADV_WILLNEED);
#endif
#ifdef MADV_WILLNEED
        madvise(data_, size_, MADV_WILLNEED);
#endif
        // N64 ROM fetches are not a sequential media stream. Warm one byte
        // per OS page before retro_load_game so random first-touch faults do
        // not become visible gameplay micro-stutters later.
        const long page = std::max<long>(4096, sysconf(_SC_PAGESIZE));
        const auto* bytes = static_cast<const std::uint8_t*>(data_);
        volatile std::uint8_t warm = 0;
        for (std::size_t offset = 0; offset < size_; offset += static_cast<std::size_t>(page)) warm ^= bytes[offset];
        if (size_ > 0) warm ^= bytes[size_ - 1];
        (void)warm;
        return true;
    }

    ~MappedFile() { reset(); }
    const void* data() const { return data_; }
    std::size_t size() const { return size_; }

private:
    void reset() {
        if (data_) munmap(data_, size_);
        if (fd_ >= 0) ::close(fd_);
        data_ = nullptr;
        fd_ = -1;
        size_ = 0;
    }
    int fd_ = -1;
    void* data_ = nullptr;
    std::size_t size_ = 0;
};

struct CoreApi final {
    using retro_api_version_t = unsigned (*)();
    using retro_set_environment_t = void (*)(retro_environment_t);
    using retro_set_video_refresh_t = void (*)(retro_video_refresh_t);
    using retro_set_audio_sample_t = void (*)(retro_audio_sample_t);
    using retro_set_audio_sample_batch_t = void (*)(retro_audio_sample_batch_t);
    using retro_set_input_poll_t = void (*)(retro_input_poll_t);
    using retro_set_input_state_t = void (*)(retro_input_state_t);
    using retro_init_t = void (*)();
    using retro_deinit_t = void (*)();
    using retro_reset_t = void (*)();
    using retro_get_system_info_t = void (*)(retro_system_info*);
    using retro_get_system_av_info_t = void (*)(retro_system_av_info*);
    using retro_load_game_t = bool (*)(const retro_game_info*);
    using retro_unload_game_t = void (*)();
    using retro_run_t = void (*)();
    using retro_set_controller_port_device_t = void (*)(unsigned, unsigned);
    using retro_serialize_size_t = std::size_t (*)();
    using retro_serialize_t = bool (*)(void*, std::size_t);
    using retro_unserialize_t = bool (*)(const void*, std::size_t);
    using retro_get_memory_data_t = void* (*)(unsigned);
    using retro_get_memory_size_t = std::size_t (*)(unsigned);

    void* handle = nullptr;
    retro_api_version_t apiVersion = nullptr;
    retro_set_environment_t setEnvironment = nullptr;
    retro_set_video_refresh_t setVideoRefresh = nullptr;
    retro_set_audio_sample_t setAudioSample = nullptr;
    retro_set_audio_sample_batch_t setAudioSampleBatch = nullptr;
    retro_set_input_poll_t setInputPoll = nullptr;
    retro_set_input_state_t setInputState = nullptr;
    retro_init_t init = nullptr;
    retro_deinit_t deinit = nullptr;
    retro_reset_t reset = nullptr;
    retro_get_system_info_t getSystemInfo = nullptr;
    retro_get_system_av_info_t getSystemAvInfo = nullptr;
    retro_load_game_t loadGame = nullptr;
    retro_unload_game_t unloadGame = nullptr;
    retro_run_t run = nullptr;
    retro_set_controller_port_device_t setControllerPortDevice = nullptr;
    retro_serialize_size_t serializeSize = nullptr;
    retro_serialize_t serialize = nullptr;
    retro_unserialize_t unserialize = nullptr;
    retro_get_memory_data_t getMemoryData = nullptr;
    retro_get_memory_size_t getMemorySize = nullptr;

    template <typename T>
    bool required(T& out, const char* name) {
        out = reinterpret_cast<T>(dlsym(handle, name));
        if (!out) logPrint(ANDROID_LOG_ERROR, "missing libretro symbol %s", name);
        return out != nullptr;
    }

    template <typename T>
    void optional(T& out, const char* name) {
        out = reinterpret_cast<T>(dlsym(handle, name));
    }

    bool load() {
        close();
        handle = dlopen(kCoreSoname, RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            logPrint(ANDROID_LOG_ERROR, "dlopen failed: %s", dlerror());
            return false;
        }
        bool ok = true;
        ok &= required(apiVersion, "retro_api_version");
        ok &= required(setEnvironment, "retro_set_environment");
        ok &= required(setVideoRefresh, "retro_set_video_refresh");
        ok &= required(setAudioSample, "retro_set_audio_sample");
        ok &= required(setAudioSampleBatch, "retro_set_audio_sample_batch");
        ok &= required(setInputPoll, "retro_set_input_poll");
        ok &= required(setInputState, "retro_set_input_state");
        ok &= required(init, "retro_init");
        ok &= required(deinit, "retro_deinit");
        ok &= required(getSystemInfo, "retro_get_system_info");
        ok &= required(getSystemAvInfo, "retro_get_system_av_info");
        ok &= required(loadGame, "retro_load_game");
        ok &= required(unloadGame, "retro_unload_game");
        ok &= required(run, "retro_run");
        ok &= required(setControllerPortDevice, "retro_set_controller_port_device");
        optional(reset, "retro_reset");
        optional(serializeSize, "retro_serialize_size");
        optional(serialize, "retro_serialize");
        optional(unserialize, "retro_unserialize");
        optional(getMemoryData, "retro_get_memory_data");
        optional(getMemorySize, "retro_get_memory_size");
        if (!ok || apiVersion() != 1u) {
            close();
            return false;
        }
        return true;
    }

    void close() {
        if (handle) dlclose(handle);
        handle = nullptr;
        apiVersion = nullptr;
        setEnvironment = nullptr;
        setVideoRefresh = nullptr;
        setAudioSample = nullptr;
        setAudioSampleBatch = nullptr;
        setInputPoll = nullptr;
        setInputState = nullptr;
        init = nullptr;
        deinit = nullptr;
        reset = nullptr;
        getSystemInfo = nullptr;
        getSystemAvInfo = nullptr;
        loadGame = nullptr;
        unloadGame = nullptr;
        run = nullptr;
        setControllerPortDevice = nullptr;
        serializeSize = nullptr;
        serialize = nullptr;
        unserialize = nullptr;
        getMemoryData = nullptr;
        getMemorySize = nullptr;
    }

    ~CoreApi() { close(); }
};
}  // namespace

struct LibretroHost::Impl {
    explicit Impl(LibretroHost* owner) : owner(owner) {}

    LibretroHost* owner = nullptr;
    CoreApi core;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLConfig eglConfig = nullptr;
    int surfaceWidth = 0;
    int surfaceHeight = 0;
    int renderWidth = 640;
    int renderHeight = 480;
    GLuint frontFbo = 0;
    GLuint colorTexture = 0;
    GLuint depthBuffer = 0;
    bool directPresent = false;
    std::uint64_t presentedFrames = 0;
    bool coreInitialized = false;
    bool gameLoaded = false;
    double targetFps = 60.0;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTimeFn = nullptr;
    std::int64_t presentationTargetNs = 0;
    PerformanceHintSession perfHint;

    AudioRing audioRing;
    AAudioStream* audioStream = nullptr;
    bool audioStarted = false;
    double coreSampleRate = 44100.0;
    int outputSampleRate = 48000;
    int framesPerBurst = 0;
    int appliedAudioBursts = 4;
    int audioPrimeFrames = 0;
    int minimumAudioLatencyMs = 0;
    int lastXRunCount = 0;
    int stableAudioChecks = 0;
    int audioBufferFrames = 0;
    std::uint64_t lastRingUnderruns = 0;
    std::chrono::steady_clock::time_point transitionAudioShieldUntil{};
    std::int16_t lastAudioLeft = 0;
    std::int16_t lastAudioRight = 0;
    // Alpha 23 SmoothAudioResampler: keep one continuous source timeline so
    // tiny pacing corrections never duplicate/drop whole PCM samples.
    double audioSyncScaleSmoothed = 1.0;
    double resampleNextOutputPos = 0.0;
    std::uint64_t resampleInputFramesSeen = 0;
    std::int16_t resamplePrevLeft = 0;
    std::int16_t resamplePrevRight = 0;
    bool resampleHavePrev = false;
    std::array<std::int16_t, 8192> resampleScratch{};
    // Callback-only fixed storage: no allocation, locks or I/O on AAudio's
    // real-time thread. Keeps one recent output tail for very short source gaps.
    std::array<std::int16_t, 2048> callbackHistory{};
    std::size_t callbackHistorySamples = 0;
    int consecutiveStarvedCallbacks = 0;
    int audioPrimeStableFrames = 0;

    static aaudio_data_callback_result_t audioCallback(
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

    bool createEgl(ANativeWindow* window, int preferredWidth, int preferredHeight) {
        renderWidth = std::max(320, preferredWidth);
        renderHeight = std::max(240, preferredHeight);
        const int geometryStatus = ANativeWindow_setBuffersGeometry(window, renderWidth, renderHeight, 0);
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return false;
        if (!eglBindAPI(EGL_OPENGL_ES_API)) return false;
        const EGLint attrs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 0, EGL_NONE
        };
        EGLint count = 0;
        if (!eglChooseConfig(display, attrs, &eglConfig, 1, &count) || count < 1) return false;
        surface = eglCreateWindowSurface(display, eglConfig, window, nullptr);
        if (surface == EGL_NO_SURFACE) return false;
        const EGLint contextAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, eglConfig, EGL_NO_CONTEXT, contextAttrs);
        if (context == EGL_NO_CONTEXT || !eglMakeCurrent(display, surface, surface, context)) return false;
        // OmniCore owns pacing. Blocking on EGL VSync and then sleeping again in
        // the emulation loop caused the old double-pacing path and severe jitter.
        eglSwapInterval(display, 0);
        presentationTimeFn = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
            eglGetProcAddress("eglPresentationTimeANDROID"));
        eglQuerySurface(display, surface, EGL_WIDTH, &surfaceWidth);
        eglQuerySurface(display, surface, EGL_HEIGHT, &surfaceHeight);
        directPresent = geometryStatus == 0 && surfaceWidth == renderWidth && surfaceHeight == renderHeight;
        if (directPresent) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, renderWidth, renderHeight);
        }
        return surfaceWidth > 0 && surfaceHeight > 0;
    }

    bool createFrontendFramebuffer(int width, int height) {
        renderWidth = std::max(320, width);
        renderHeight = std::max(240, height);
        glGenTextures(1, &colorTexture);
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, renderWidth, renderHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glGenRenderbuffers(1, &depthBuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, renderWidth, renderHeight);
        glGenFramebuffers(1, &frontFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, frontFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
        const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            logPrint(ANDROID_LOG_ERROR, "N64 frontend FBO incomplete: 0x%x", status);
            return false;
        }
        glViewport(0, 0, renderWidth, renderHeight);
        return true;
    }

    void destroyGlObjects() {
        if (frontFbo) glDeleteFramebuffers(1, &frontFbo);
        if (colorTexture) glDeleteTextures(1, &colorTexture);
        if (depthBuffer) glDeleteRenderbuffers(1, &depthBuffer);
        frontFbo = colorTexture = depthBuffer = 0;
    }

    void destroyEgl() {
        if (display != EGL_NO_DISPLAY) {
            if (context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE)
                eglMakeCurrent(display, surface, surface, context);
            destroyGlObjects();
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            eglTerminate(display);
        }
        context = EGL_NO_CONTEXT;
        surface = EGL_NO_SURFACE;
        display = EGL_NO_DISPLAY;
        eglConfig = nullptr;
        presentationTimeFn = nullptr;
        presentationTargetNs = 0;
    }

    void armTransitionAudioShield(std::chrono::milliseconds duration) {
        const auto requestedUntil = std::chrono::steady_clock::now() + duration;
        if (requestedUntil > transitionAudioShieldUntil) transitionAudioShieldUntil = requestedUntil;
        stableAudioChecks = 0;
    }

    bool transitionAudioShieldActive() const {
        return std::chrono::steady_clock::now() < transitionAudioShieldUntil;
    }

    void updateAudioTelemetry() {
        if (!owner || outputSampleRate <= 0) return;
        const float fillMs = static_cast<float>(audioRing.availableSamples() / 2u) * 1000.0f /
            static_cast<float>(outputSampleRate);
        const int bufferFrames = std::max(0, audioBufferFrames);
        const float bufferMs = static_cast<float>(bufferFrames) * 1000.0f /
            static_cast<float>(outputSampleRate);
        owner->audioFillMs_.store(fillMs, std::memory_order_release);
        owner->audioBufferMs_.store(bufferMs, std::memory_order_release);
    }

    bool openAudio(double sampleRate, int requestedBursts) {
        closeAudio();
        audioRing.clear();
        callbackHistorySamples = 0;
        consecutiveStarvedCallbacks = 0;
        audioPrimeStableFrames = 0;
        coreSampleRate = sampleRate > 1000.0 ? sampleRate : 44100.0;
        requestedBursts = std::clamp(requestedBursts, 2, 8);
        audioSyncScaleSmoothed = 1.0;
        resampleNextOutputPos = 0.0;
        resampleInputFramesSeen = 0;
        resamplePrevLeft = 0;
        resamplePrevRight = 0;
        resampleHavePrev = false;

        auto tryMode = [&](aaudio_sharing_mode_t sharing) -> bool {
            AAudioStreamBuilder* builder = nullptr;
            if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) return false;
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSharingMode(builder, sharing);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            // Do not request the core sample rate here. AAudio can then open the
            // device's natural low-latency rate (typically 48 kHz), while the
            // frontend performs the small conversion itself.
            AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);
            const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &audioStream);
            AAudioStreamBuilder_delete(builder);
            if (result != AAUDIO_OK || !audioStream) {
                audioStream = nullptr;
                return false;
            }
            outputSampleRate = std::max(1, AAudioStream_getSampleRate(audioStream));
            framesPerBurst = std::max(1, AAudioStream_getFramesPerBurst(audioStream));
            int minBursts = requestedBursts;
            if (minimumAudioLatencyMs > 0) {
                const int latencyFrames = outputSampleRate * minimumAudioLatencyMs / 1000;
                minBursts = std::max(minBursts, (latencyFrames + framesPerBurst - 1) / framesPerBurst);
            }
            appliedAudioBursts = std::clamp(minBursts, 2, 8);
            const int requestedFrames = framesPerBurst * appliedAudioBursts;
            const int appliedFrames = AAudioStream_setBufferSizeInFrames(audioStream, requestedFrames);
            audioBufferFrames = appliedFrames > 0 ? appliedFrames : requestedFrames;
            // StartupAudioGate: Alpha 21 started AAudio around 50 ms while the
            // transition controller immediately targeted ~76 ms. That meant the
            // stream could begin already below its own safe reserve. Prime to a
            // bounded ~90 ms floor (or enough for the actual device buffer plus
            // four bursts) before the callback is allowed to consume anything.
            const int startupFloorFrames = std::max(1, outputSampleRate * 90 / 1000);
            const int startupCeilingFrames = std::max(startupFloorFrames, outputSampleRate * 120 / 1000);
            const int deviceSafetyFrames = std::max(
                framesPerBurst * 8, audioBufferFrames + framesPerBurst * 4);
            audioPrimeFrames = std::clamp(
                std::max(startupFloorFrames, deviceSafetyFrames),
                startupFloorFrames,
                std::min(startupCeilingFrames, static_cast<int>(audioRing.capacitySamples() / 2u)));
            armTransitionAudioShield(std::chrono::milliseconds(6000));
            lastXRunCount = std::max(0, AAudioStream_getXRunCount(audioStream));
            lastRingUnderruns = audioRing.underruns();
            stableAudioChecks = 0;
            audioStarted = false;
            updateAudioTelemetry();
            return true;
        };

        if (tryMode(AAUDIO_SHARING_MODE_EXCLUSIVE)) return true;
        return tryMode(AAUDIO_SHARING_MODE_SHARED);
    }

    void closeAudio() {
        if (audioStream) {
            if (audioStarted) AAudioStream_requestStop(audioStream);
            AAudioStream_close(audioStream);
            audioStream = nullptr;
        }
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

    void reprimeAudio() {
        if (audioStream && audioStarted) AAudioStream_requestPause(audioStream);
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
        armTransitionAudioShield(std::chrono::milliseconds(2200));
        updateAudioTelemetry();
    }

    void startAudioIfReady() {
        if (!audioStream || audioStarted) return;
        const std::size_t availableFrames = audioRing.availableSamples() / 2u;
        if (availableFrames < static_cast<std::size_t>(std::max(1, audioPrimeFrames))) {
            audioPrimeStableFrames = 0;
            return;
        }
        // Only open the real-time consumer at an emulation frame boundary.
        // Requiring two complete frames above the threshold prevents a single
        // unusually large libretro audio batch from starting AAudio mid-batch.
        if (++audioPrimeStableFrames < 2) return;
        if (AAudioStream_requestStart(audioStream) == AAUDIO_OK) {
            audioStarted = true;
            audioPrimeStableFrames = 0;
            logPrint(ANDROID_LOG_INFO, "StartupAudioGate opened with %zu frames queued (target=%d)",
                     availableFrames, audioPrimeFrames);
        }
    }

    double audioSyncScale() {
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

    void adaptAudio(int requestedBursts) {
        if (!audioStream || framesPerBurst <= 0) return;
        requestedBursts = std::clamp(requestedBursts, 2, 8);
        if (transitionAudioShieldActive()) requestedBursts = std::max(requestedBursts, 7);
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
        lastXRunCount = xruns;
        lastRingUnderruns = underruns;
        updateAudioTelemetry();
    }
};

LibretroHost& LibretroHost::instance() {
    static LibretroHost host;
    return host;
}

LibretroHost::~LibretroHost() { stop(); }

bool LibretroHost::start(ANativeWindow* window, RuntimeConfig config) {
    if (!window || running_.exchange(true, std::memory_order_acq_rel)) return false;
    if (thread_.joinable()) thread_.join();
    ANativeWindow_acquire(window);
    window_ = window;
    config_ = std::move(config);
    config_.audioBufferBursts = std::clamp(config_.audioBufferBursts, 2, 8);
    audioTargetBursts_.store(config_.audioBufferBursts, std::memory_order_release);
    stopRequested_.store(false, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    buttonMask_.store(0, std::memory_order_release);
    smartDpadMask_.store(0, std::memory_order_release);
    smartAnalogDpadActive_.store(false, std::memory_order_release);
    interactionTransitionBoost_.store(false, std::memory_order_release);
    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);
    {
        std::lock_guard<std::mutex> lock(telemetryMutex_);
        frameWindow_.fill(0.0f);
        frameWindowCount_ = 0;
        frameWindowWrite_ = 0;
        presentWindow_.fill(0.0f);
        presentWindowCount_ = 0;
        presentWindowWrite_ = 0;
    }
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        pendingCommand_ = CommandType::NONE;
        pendingStatePath_.clear();
    }
    lastSaveRamHash_ = 0;
    audioUnderruns_.store(0, std::memory_order_release);
    audioRescues_.store(0, std::memory_order_release);
    audioFillMs_.store(0.0f, std::memory_order_release);
    audioBufferMs_.store(0.0f, std::memory_order_release);
    targetFps_.store(60.0f, std::memory_order_release);
    pacingCorrectionPct_.store(0.0f, std::memory_order_release);
    targetFrameMs_.store(1000.0f / 60.0f, std::memory_order_release);
    adpfActive_.store(false, std::memory_order_release);
    burstShieldActive_.store(false, std::memory_order_release);
    warmStartActive_.store(false, std::memory_order_release);
    shaderCacheEnabled_.store(false, std::memory_order_release);
    shaderCacheReady_.store(false, std::memory_order_release);
    passiveWarmCacheReady_.store(false, std::memory_order_release);
    directPresenterActive_.store(false, std::memory_order_release);
    smartAnalogDpadActive_.store(false, std::memory_order_release);
    lastPresentMs_.store(0.0f, std::memory_order_release);
    hwRenderRequested_ = false;
    hwRender_ = {};
    precisionGovernorMode_.store(0, std::memory_order_release);
    setMessage("N64 BOOT 1/6 • Alpha 22 StartupAudioGate + ElasticAudioBridge…");
    try {
        thread_ = std::thread(&LibretroHost::run, this);
    } catch (...) {
        ANativeWindow_release(window_);
        window_ = nullptr;
        running_.store(false, std::memory_order_release);
        return false;
    }
    return true;
}

void LibretroHost::stop() {
    stopRequested_.store(true, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) thread_.join();
}

void LibretroHost::setPaused(bool paused) { paused_.store(paused, std::memory_order_release); }

void LibretroHost::setAudioTargetBursts(int bursts) {
    audioTargetBursts_.store(std::clamp(bursts, 2, 8), std::memory_order_release);
}

bool LibretroHost::requestSaveState(std::string path) {
    if (!running() || path.empty()) return false;
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (pendingCommand_ != CommandType::NONE) return false;
    pendingCommand_ = CommandType::SAVE_STATE;
    pendingStatePath_ = std::move(path);
    return true;
}

bool LibretroHost::requestLoadState(std::string path) {
    if (!running() || path.empty()) return false;
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (pendingCommand_ != CommandType::NONE) return false;
    pendingCommand_ = CommandType::LOAD_STATE;
    pendingStatePath_ = std::move(path);
    return true;
}

bool LibretroHost::requestReset() {
    if (!running()) return false;
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (pendingCommand_ != CommandType::NONE) return false;
    pendingCommand_ = CommandType::RESET;
    pendingStatePath_.clear();
    return true;
}

std::string LibretroHost::lastMessage() const {
    std::lock_guard<std::mutex> lock(messageMutex_);
    return message_;
}

void LibretroHost::setMessage(std::string message) {
    std::lock_guard<std::mutex> lock(messageMutex_);
    message_ = std::move(message);
}

void LibretroHost::setButton(unsigned retroPadId, bool pressed) {
    if (retroPadId > RETRO_DEVICE_ID_JOYPAD_R3) return;
    if (pressed && retroPadId == RETRO_DEVICE_ID_JOYPAD_START) {
        // Pause/menu screens often trigger the first expensive framebuffer
        // copy. Ask the emulation thread for GPU headroom before the core
        // consumes the Start press.
        menuTransitionBoost_.store(true, std::memory_order_release);
    }
    if (pressed && (
            retroPadId == RETRO_DEVICE_ID_JOYPAD_B ||
            retroPadId == RETRO_DEVICE_ID_JOYPAD_Y ||
            retroPadId == RETRO_DEVICE_ID_JOYPAD_L2)) {
        // A/B/Z in OmniCore's N64 mapping are the most common action/attack
        // inputs. Collisions and hit effects frequently activate fresh CPU/RDP
        // work immediately after these presses. Signal a tiny predictive burst;
        // PerformanceHintSession bounds it to at most one notification / 700 ms.
        interactionTransitionBoost_.store(true, std::memory_order_release);
    }
    const auto bit = static_cast<std::uint16_t>(1u << retroPadId);
    if (pressed) buttonMask_.fetch_or(bit, std::memory_order_acq_rel);
    else buttonMask_.fetch_and(static_cast<std::uint16_t>(~bit), std::memory_order_acq_rel);
}

void LibretroHost::setAnalog(float x, float y, float cX, float cY) {
    const AnalogVector shaped = shapeAnalog(
        x, y, config_.analogDeadzonePercent, config_.analogSensitivityPercent,
        config_.precisionAnalog, config_.analogProfile);
    analogX_.store(axisFromFloat(shaped.x), std::memory_order_release);
    analogY_.store(axisFromFloat(shaped.y), std::memory_order_release);
    cX_.store(axisFromFloat(cX), std::memory_order_release);
    cY_.store(axisFromFloat(cY), std::memory_order_release);

    const bool dpadOnly = config_.smartAnalogMode == "dpad_only";
    const bool allowSmartDpad = dpadOnly ||
        (config_.smartAnalogMode == "auto" && config_.smartAnalogAutoDpad);
    std::uint16_t nextMask = 0;
    if (allowSmartDpad) {
        const float magnitude = std::hypot(shaped.x, shaped.y);
        const bool alreadyActive = smartDpadMask_.load(std::memory_order_acquire) != 0;
        const float engageMagnitude = alreadyActive ? 0.42f : 0.58f;
        if (magnitude >= engageMagnitude) {
            constexpr float kAxisThreshold = 0.34f;
            if (shaped.y <= -kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_UP);
            if (shaped.y >= kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_DOWN);
            if (shaped.x <= -kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_LEFT);
            if (shaped.x >= kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_RIGHT);
        }
    }
    smartDpadMask_.store(nextMask, std::memory_order_release);
    smartAnalogDpadActive_.store(nextMask != 0, std::memory_order_release);
}

void LibretroHost::buildCoreOptions() {
    std::lock_guard<std::mutex> lock(optionMutex_);
    options_.clear();
    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";
    const bool framebuffer = config_.framebufferEmulation;
    const bool leanGraphics = config_.leanGraphics;

    options_["mupen64plus-rdp-plugin"] = "gliden64";
    options_["mupen64plus-rsp-plugin"] = "hle";
    options_["mupen64plus-cpucore"] = config_.cpuMode == "cached_interpreter" ? "cached_interpreter" : "dynamic_recompiler";
    const char* screen43 = config_.internalResolution >= 20 ? "1280x960" :
        (config_.internalResolution >= 15 ? "960x720" : "640x480");
    const char* screen169 = config_.internalResolution >= 20 ? "1280x720" :
        (config_.internalResolution >= 15 ? "960x540" : "640x360");
    options_["mupen64plus-43screensize"] = screen43;
    options_["mupen64plus-169screensize"] = screen169;
    options_["mupen64plus-aspect"] = wide ? config_.aspectRatio : "4:3";
    options_["mupen64plus-ThreadedRenderer"] = boolOption(config_.threadedRenderer);
    options_["mupen64plus-EnableFBEmulation"] = boolOption(framebuffer);
    // Triple-buffered color copies reduce the first heavy framebuffer transition
    // (notably Zelda pause screens) without disabling compatibility. Under
    // measured GPU pressure we fall back to the cheaper double-buffered path.
    options_["mupen64plus-EnableCopyColorToRDRAM"] = framebuffer
        ? (leanGraphics ? "Async" : "TripleBuffer")
        : "Off";
    options_["mupen64plus-EnableCopyDepthToRDRAM"] = framebuffer ? "Software" : "Off";
    options_["mupen64plus-EnableCopyColorFromRDRAM"] = "False";
    options_["mupen64plus-EnableCopyAuxToRDRAM"] = "False";
    // Readability floor: LOD is part of normal N64 texture selection and was
    // too visually destructive to disable merely for performance.
    options_["mupen64plus-EnableLODEmulation"] = "True";
    options_["mupen64plus-EnableLegacyBlending"] = leanGraphics ? "True" : "False";
    options_["mupen64plus-EnableFragmentDepthWrite"] = "False";
    // Pinned GLideN64 resolves its cache as <systemDir>/Mupen64plus/shaders.
    // Enable persistent shader binaries only after the exact directory is writable;
    // otherwise fall back cleanly instead of pretending cache is active.
    const std::string shaderDir = config_.systemDir + "/Mupen64plus/shaders";
    const bool shaderReady = ensureDirectoryTree(shaderDir);
    options_["mupen64plus-EnableShadersStorage"] = boolOption(shaderReady);
    options_["mupen64plus-EnableTextureCache"] = "False";
    shaderCacheEnabled_.store(shaderReady, std::memory_order_release);
    shaderCacheReady_.store(shaderReady, std::memory_order_release);
    options_["mupen64plus-BackgroundMode"] = "OnePiece";
    options_["mupen64plus-CorrectTexrectCoords"] = "Auto";
    options_["mupen64plus-BilinearMode"] = "3point";
    options_["mupen64plus-EnableNativeResFactor"] = "0";
    options_["mupen64plus-EnableNativeResTexrects"] = "Optimized";
    options_["mupen64plus-EnableHWLighting"] = "False";
    options_["mupen64plus-DitheringPattern"] = "False";
    options_["mupen64plus-DitheringQuantization"] = "False";
    options_["mupen64plus-RDRAMImageDitheringMode"] = "False";
    options_["mupen64plus-FXAA"] = "0";
    options_["mupen64plus-MultiSampling"] = "0";
    options_["mupen64plus-HybridFilter"] = "False";
    options_["mupen64plus-txHiresEnable"] = "False";
    options_["mupen64plus-txFilterMode"] = "None";
    options_["mupen64plus-txEnhancementMode"] = "None";
    options_["mupen64plus-alt-map"] = "True";
    // Precision mode owns the calibration in one radial stage. Avoid applying
    // the same deadzone/sensitivity twice inside the core afterwards.
    options_["mupen64plus-astick-deadzone"] = config_.precisionAnalog
        ? "0" : std::to_string(std::clamp(config_.analogDeadzonePercent, 4, 30));
    options_["mupen64plus-astick-sensitivity"] = config_.precisionAnalog
        ? "100" : std::to_string(std::clamp(config_.analogSensitivityPercent, 70, 130));
    if (config_.pakMode == "rumble") options_["mupen64plus-pak1"] = "rumble";
    else if (config_.pakMode == "none") options_["mupen64plus-pak1"] = "none";
    else options_["mupen64plus-pak1"] = "memory";
}

bool LibretroHost::environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return data != nullptr;
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            if (data) {
                const auto* message = static_cast<const retro_message*>(data);
                if (message->msg) setMessage(std::string("N64 • ") + message->msg);
            }
            return true;
        case RETRO_ENVIRONMENT_SHUTDOWN:
            stopRequested_.store(true, std::memory_order_release);
            return true;
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (data) *static_cast<const char**>(data) = config_.systemDir.c_str();
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data) *static_cast<const char**>(data) = config_.saveDir.c_str();
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY: {
            static thread_local std::string contentDir;
            const auto slash = config_.romPath.find_last_of('/');
            contentDir = slash == std::string::npos ? config_.romPath : config_.romPath.substr(0, slash);
            if (data) *static_cast<const char**>(data) = contentDir.c_str();
            return data != nullptr;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return data && *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
            return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            if (data && impl_) {
                const auto* info = static_cast<const retro_system_av_info*>(data);
                if (info->timing.fps > 1.0) impl_->targetFps = info->timing.fps;
                if (info->timing.sample_rate > 1000.0) impl_->coreSampleRate = info->timing.sample_rate;
            }
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (!data) return false;
            auto* variable = static_cast<retro_variable*>(data);
            if (!variable->key) return false;
            std::lock_guard<std::mutex> lock(optionMutex_);
            const auto it = options_.find(variable->key);
            variable->value = it == options_.end() ? nullptr : it->second.c_str();
            return variable->value != nullptr;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            if (!data) return true;
            auto* variables = static_cast<retro_variable*>(data);
            std::lock_guard<std::mutex> lock(optionMutex_);
            for (std::size_t i = 0; variables[i].key; ++i) {
                if (options_.contains(variables[i].key) || !variables[i].value) continue;
                std::string spec = variables[i].value;
                const auto semicolon = spec.find(';');
                if (semicolon != std::string::npos) spec = spec.substr(semicolon + 1);
                const auto pipe = spec.find('|');
                if (pipe != std::string::npos) spec.resize(pipe);
                spec = trim(spec);
                if (!spec.empty()) options_[variables[i].key] = std::move(spec);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data) *static_cast<bool*>(data) = false;
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data) static_cast<retro_log_callback*>(data)->log = coreLog;
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (data) *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 0u;
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<unsigned*>(data) = RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO;
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            if (data) *static_cast<float*>(data) = targetFps_.load(std::memory_order_acquire);
            return data != nullptr;
        case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS:
            if (data) *static_cast<unsigned*>(data) = 1u;
            return data != nullptr;
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
            if (data && impl_) impl_->minimumAudioLatencyMs = static_cast<int>(*static_cast<unsigned*>(data));
            return true;
        case abi::RETRO_ENVIRONMENT_SET_HW_RENDER: {
            if (!data || !impl_ || impl_->context == EGL_NO_CONTEXT) return false;
            auto* requested = static_cast<abi::retro_hw_render_callback*>(data);
            const bool supported = requested->context_type == abi::RETRO_HW_CONTEXT_OPENGLES3 ||
                (requested->context_type == abi::RETRO_HW_CONTEXT_OPENGLES_VERSION &&
                 requested->version_major == 3u && requested->version_minor <= 1u);
            if (!supported) {
                setMessage("N64 BOOT E03 • core pediu contexto gráfico não suportado");
                return false;
            }
            requested->get_current_framebuffer = currentFramebufferCallback;
            requested->get_proc_address = procAddressCallback;
            hwRender_ = *requested;
            hwRenderRequested_ = true;
            return true;
        }
        case abi::RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
        case abi::RETRO_ENVIRONMENT_GET_PERF_INTERFACE:
            return false;
        case abi::RETRO_ENVIRONMENT_GET_CLEAR_ALL_THREAD_WAITS_CB:
            if (data) *reinterpret_cast<retro_environment_t*>(data) = clearThreadWaitsCallback;
            return data != nullptr;
        case abi::RETRO_ENVIRONMENT_POLL_TYPE_OVERRIDE:
            return true;
        default:
            return false;
    }
}

void LibretroHost::videoRefresh(const void* data, unsigned, unsigned, std::size_t) {
    if (!impl_ || impl_->display == EGL_NO_DISPLAY) return;
    if (data != abi::RETRO_HW_FRAME_BUFFER_VALID) return;
    const auto presentBegin = std::chrono::steady_clock::now();
    if (!impl_->directPresent) {
        if (impl_->frontFbo == 0) return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, impl_->frontFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        // Fallback preserves the proven frontend path on devices that reject
        // internal-size native buffers. DirectPresenter skips this full-frame copy.
        const GLenum presentFilter = config_.internalResolution >= 15 ? GL_LINEAR : GL_NEAREST;
        glBlitFramebuffer(0, 0, impl_->renderWidth, impl_->renderHeight,
                          0, 0, impl_->surfaceWidth, impl_->surfaceHeight,
                          GL_COLOR_BUFFER_BIT, presentFilter);
    }
    if (impl_->presentationTimeFn && impl_->presentationTargetNs > 0) {
        impl_->presentationTimeFn(impl_->display, impl_->surface, impl_->presentationTargetNs);
    }
    if (!eglSwapBuffers(impl_->display, impl_->surface)) {
        setMessage("N64 RUNTIME E04 • falha ao apresentar frame GLES3");
        stopRequested_.store(true, std::memory_order_release);
        return;
    }
    const auto presentEnd = std::chrono::steady_clock::now();
    recordPresent(std::chrono::duration<float, std::milli>(presentEnd - presentBegin).count());
    glBindFramebuffer(GL_FRAMEBUFFER, impl_->directPresent ? 0 : impl_->frontFbo);
    glViewport(0, 0, impl_->renderWidth, impl_->renderHeight);
    ++impl_->presentedFrames;
    if (impl_->presentedFrames == 1) {
        setMessage(impl_->audioStream
            ? (impl_->directPresent
                ? "N64 RUN OK • DirectPresenter GLES3 • AAudio nativo pronto"
                : "N64 RUN OK • RenderBridge fallback GLES3 • AAudio nativo pronto")
            : (impl_->directPresent
                ? "N64 RUN OK • DirectPresenter GLES3 • áudio indisponível"
                : "N64 RUN OK • RenderBridge fallback GLES3 • áudio indisponível"));
    }
}

void LibretroHost::audioSample(std::int16_t left, std::int16_t right) {
    if (!impl_) return;
    const std::int16_t stereo[2] = {left, right};
    impl_->pushAudio(stereo, 1);
}

std::size_t LibretroHost::audioBatch(const std::int16_t* data, std::size_t frames) {
    if (impl_) impl_->pushAudio(data, frames);
    return frames;
}

std::int16_t LibretroHost::inputState(unsigned port, unsigned device, unsigned index, unsigned id) const {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        const auto mask = static_cast<std::uint16_t>(
            buttonMask_.load(std::memory_order_acquire) | smartDpadMask_.load(std::memory_order_acquire));
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<std::int16_t>(mask);
        if (id <= RETRO_DEVICE_ID_JOYPAD_R3) return (mask & (1u << id)) ? 1 : 0;
        return 0;
    }
    if (device == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
            if (config_.smartAnalogMode == "dpad_only") return 0;
            if (id == RETRO_DEVICE_ID_ANALOG_X) return analogX_.load(std::memory_order_acquire);
            if (id == RETRO_DEVICE_ID_ANALOG_Y) return analogY_.load(std::memory_order_acquire);
        }
        if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
            if (id == RETRO_DEVICE_ID_ANALOG_X) return cX_.load(std::memory_order_acquire);
            if (id == RETRO_DEVICE_ID_ANALOG_Y) return cY_.load(std::memory_order_acquire);
        }
    }
    return 0;
}

void LibretroHost::recordFrame(float frameMs, float targetMs) {
    targetFrameMs_.store(targetMs, std::memory_order_release);
    // Telemetry is observational. If the UI is copying the diagnostic window,
    // skipping one sample is always preferable to blocking an emulation frame.
    std::unique_lock<std::mutex> lock(telemetryMutex_, std::try_to_lock);
    if (!lock.owns_lock()) return;
    frameWindow_[frameWindowWrite_] = frameMs;
    frameWindowWrite_ = (frameWindowWrite_ + 1) % kTelemetryCapacity;
    frameWindowCount_ = std::min(frameWindowCount_ + 1, kTelemetryCapacity);
}

void LibretroHost::recordPresent(float presentMs) {
    lastPresentMs_.store(presentMs, std::memory_order_release);
    // Same rule for presentation telemetry: diagnostics must never become a
    // source of periodic micro-stutter.
    std::unique_lock<std::mutex> lock(telemetryMutex_, std::try_to_lock);
    if (!lock.owns_lock()) return;
    presentWindow_[presentWindowWrite_] = presentMs;
    presentWindowWrite_ = (presentWindowWrite_ + 1) % kTelemetryCapacity;
    presentWindowCount_ = std::min(presentWindowCount_ + 1, kTelemetryCapacity);
}

Telemetry LibretroHost::telemetry() const {
    std::array<float, kTelemetryCapacity> snapshot{};
    std::array<float, kTelemetryCapacity> presentSnapshot{};
    std::size_t count = 0;
    std::size_t presentCount = 0;
    {
        std::lock_guard<std::mutex> lock(telemetryMutex_);
        count = frameWindowCount_;
        presentCount = presentWindowCount_;
        for (std::size_t i = 0; i < count; ++i) snapshot[i] = frameWindow_[i];
        for (std::size_t i = 0; i < presentCount; ++i) presentSnapshot[i] = presentWindow_[i];
    }
    Telemetry out;
    out.sampleWindowFrames = static_cast<int>(count);
    out.audioUnderruns = audioUnderruns_.load(std::memory_order_acquire);
    out.audioRescues = audioRescues_.load(std::memory_order_acquire);
    out.audioFillMs = audioFillMs_.load(std::memory_order_acquire);
    out.audioBufferMs = audioBufferMs_.load(std::memory_order_acquire);
    out.targetFps = targetFps_.load(std::memory_order_acquire);
    out.pacingCorrectionPct = pacingCorrectionPct_.load(std::memory_order_acquire);
    out.adpfActive = adpfActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.burstShieldActive = burstShieldActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.warmStartActive = warmStartActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.shaderCacheEnabled = shaderCacheEnabled_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.directPresenterActive = directPresenterActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.shaderCacheReady = shaderCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.smartAnalogDpadActive = smartAnalogDpadActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.passiveWarmCacheReady = passiveWarmCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.precisionGovernorMode = static_cast<float>(precisionGovernorMode_.load(std::memory_order_acquire));
    out.precisionGovernorConfidence = precisionGovernorConfidence_.load(std::memory_order_acquire);
    out.frameJitterMs = frameJitterMs_.load(std::memory_order_acquire);
    if (presentCount > 0) {
        float totalPresent = 0.0f;
        for (std::size_t i = 0; i < presentCount; ++i) totalPresent += presentSnapshot[i];
        out.presentAverageMs = totalPresent / static_cast<float>(presentCount);
        std::sort(presentSnapshot.begin(), presentSnapshot.begin() + static_cast<std::ptrdiff_t>(presentCount));
        const std::size_t pi = std::min(presentCount - 1, static_cast<std::size_t>(std::floor((presentCount - 1) * 0.95)));
        out.presentP95Ms = presentSnapshot[pi];
    }
    if (count == 0) return out;
    float total = 0.0f;
    for (std::size_t i = 0; i < count; ++i) total += snapshot[i];
    out.averageFrameMs = total / static_cast<float>(count);
    std::sort(snapshot.begin(), snapshot.begin() + static_cast<std::ptrdiff_t>(count));
    const std::size_t p95Index = std::min(
        count - 1,
        static_cast<std::size_t>(std::floor((count - 1) * 0.95)));
    out.p95FrameMs = snapshot[p95Index];
    const float targetMs = targetFrameMs_.load(std::memory_order_acquire);
    for (std::size_t i = 0; i < count; ++i) {
        if (snapshot[i] > targetMs * 1.35f) ++out.droppedFrames;
    }
    return out;
}

void LibretroHost::loadSaveRam() {
    if (!impl_ || !impl_->core.getMemoryData || !impl_->core.getMemorySize || config_.saveRamPath.empty()) return;
    void* memory = impl_->core.getMemoryData(RETRO_MEMORY_SAVE_RAM);
    const std::size_t size = impl_->core.getMemorySize(RETRO_MEMORY_SAVE_RAM);
    if (!memory || size == 0) return;
    std::vector<std::uint8_t> saved;
    if (readWholeFile(config_.saveRamPath, saved) && !saved.empty()) {
        std::memcpy(memory, saved.data(), std::min(size, saved.size()));
        setMessage("N64 SAVE • SRAM/Controller Pak carregado");
    }
    lastSaveRamHash_ = hashBytes(memory, size);
}

void LibretroHost::persistSaveRam(bool force) {
    if (!impl_ || !impl_->core.getMemoryData || !impl_->core.getMemorySize || config_.saveRamPath.empty()) return;
    void* memory = impl_->core.getMemoryData(RETRO_MEMORY_SAVE_RAM);
    const std::size_t size = impl_->core.getMemorySize(RETRO_MEMORY_SAVE_RAM);
    if (!memory || size == 0) return;
    const std::uint64_t currentHash = hashBytes(memory, size);
    if (!force && currentHash == lastSaveRamHash_) return;
    if (writeWholeFileAtomic(config_.saveRamPath, memory, size)) {
        lastSaveRamHash_ = currentHash;
    }
}

bool LibretroHost::processPendingCommand() {
    CommandType command = CommandType::NONE;
    std::string path;
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        command = pendingCommand_;
        if (command == CommandType::NONE) return false;
        path = std::move(pendingStatePath_);
        pendingStatePath_.clear();
        pendingCommand_ = CommandType::NONE;
    }
    if (!impl_ || !impl_->gameLoaded) return false;

    if (command == CommandType::RESET) {
        if (!impl_->core.reset) {
            setMessage("N64 STATE E01 • reset não suportado pelo core");
            return true;
        }
        impl_->core.reset();
        impl_->reprimeAudio();
        setMessage("N64 STATE • jogo reiniciado");
        return true;
    }

    if (command == CommandType::SAVE_STATE) {
        if (!impl_->core.serializeSize || !impl_->core.serialize) {
            setMessage("N64 STATE E02 • serialização indisponível");
            return true;
        }
        const std::size_t size = impl_->core.serializeSize();
        if (size == 0 || size > 256u * 1024u * 1024u) {
            setMessage("N64 STATE E03 • tamanho de estado inválido");
            return true;
        }
        std::vector<std::uint8_t> state(size);
        if (!impl_->core.serialize(state.data(), state.size())) {
            setMessage("N64 STATE E04 • core recusou salvar estado");
            return true;
        }
        if (!writeWholeFileAtomic(path, state.data(), state.size())) {
            setMessage("N64 STATE E05 • falha ao gravar estado");
            return true;
        }
        persistSaveRam(false);
        setMessage("N64 STATE • estado salvo");
        return true;
    }

    if (command == CommandType::LOAD_STATE) {
        if (!impl_->core.unserialize) {
            setMessage("N64 STATE E06 • carregamento de estado indisponível");
            return true;
        }
        std::vector<std::uint8_t> state;
        if (!readWholeFile(path, state)) {
            setMessage("N64 STATE E07 • slot vazio ou ilegível");
            return true;
        }
        if (!impl_->core.unserialize(state.data(), state.size())) {
            setMessage("N64 STATE E08 • core recusou carregar estado");
            return true;
        }
        impl_->reprimeAudio();
        setMessage("N64 STATE • estado carregado");
        return true;
    }
    return false;
}

void LibretroHost::run() {
    // App-owned emulation thread only; no governor/clock/system mutation.
    // Android may reject the priority request, in which case ADPF/default
    // scheduling remains in effect.
    setpriority(PRIO_PROCESS, 0, -4);
    impl_ = std::make_unique<Impl>(this);
    bool callContextDestroy = false;
    auto cleanup = [&]() {
        if (impl_) {
            if (impl_->gameLoaded) persistSaveRam(true);
            impl_->perfHint.close();
            adpfActive_.store(false, std::memory_order_release);
            burstShieldActive_.store(false, std::memory_order_release);
            warmStartActive_.store(false, std::memory_order_release);
            precisionGovernorMode_.store(0, std::memory_order_release);
            precisionGovernorConfidence_.store(0.0f, std::memory_order_release);
            frameJitterMs_.store(0.0f, std::memory_order_release);
            impl_->closeAudio();
            if (impl_->gameLoaded && impl_->core.unloadGame) {
                impl_->core.unloadGame();
                impl_->gameLoaded = false;
            }
            if (callContextDestroy && hwRender_.context_destroy) hwRender_.context_destroy();
            if (impl_->coreInitialized && impl_->core.deinit) {
                impl_->core.deinit();
                impl_->coreInitialized = false;
            }
            impl_->core.close();
            impl_->destroyEgl();
        }
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
        impl_.reset();
        running_.store(false, std::memory_order_release);
    };

    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";
    const int renderWidth = config_.internalResolution >= 20 ? 1280 :
        (config_.internalResolution >= 15 ? 960 : 640);
    const int renderHeight = wide
        ? (config_.internalResolution >= 20 ? 720 : (config_.internalResolution >= 15 ? 540 : 360))
        : (config_.internalResolution >= 20 ? 960 : (config_.internalResolution >= 15 ? 720 : 480));
    if (!impl_->createEgl(window_, renderWidth, renderHeight)) {
        setMessage("N64 BOOT E01 • não consegui criar EGL/GLES3");
        cleanup();
        return;
    }
    directPresenterActive_.store(impl_->directPresent, std::memory_order_release);
    if (!impl_->directPresent && !impl_->createFrontendFramebuffer(renderWidth, renderHeight)) {
        setMessage("N64 BOOT E02 • framebuffer GLES3 fallback inválido");
        cleanup();
        return;
    }
    setMessage(impl_->directPresent
        ? "N64 BOOT 2/6 • DirectPresenter GLES3, carregando Mupen64Plus-Next…"
        : "N64 BOOT 2/6 • RenderBridge fallback GLES3, carregando Mupen64Plus-Next…");
    if (!impl_->core.load()) {
        setMessage("N64 BOOT E03 • Mupen64Plus-Next não carregou");
        cleanup();
        return;
    }

    buildCoreOptions();
    impl_->core.setEnvironment(environmentCallback);
    impl_->core.setVideoRefresh(videoCallback);
    impl_->core.setAudioSample(audioSampleCallback);
    impl_->core.setAudioSampleBatch(audioBatchCallback);
    impl_->core.setInputPoll(inputPollCallback);
    impl_->core.setInputState(inputStateCallback);
    setMessage("N64 BOOT 3/6 • inicializando core isolado…");
    impl_->core.init();
    impl_->coreInitialized = true;
    impl_->core.setControllerPortDevice(0, RETRO_DEVICE_JOYPAD);

    retro_system_info systemInfo{};
    impl_->core.getSystemInfo(&systemInfo);
    if (!systemInfo.library_name || std::strstr(systemInfo.library_name, "Mupen64Plus") == nullptr) {
        setMessage("N64 BOOT E04 • identidade inesperada do core");
        cleanup();
        return;
    }

    MappedFile rom;
    retro_game_info gameInfo{};
    gameInfo.path = config_.romPath.c_str();
    if (!systemInfo.need_fullpath) {
        if (!rom.openReadOnly(config_.romPath)) {
            setMessage("N64 BOOT E05 • não consegui mapear a ROM preparada");
            cleanup();
            return;
        }
        gameInfo.data = rom.data();
        gameInfo.size = rom.size();
        setMessage("N64 BOOT 4/6 • ROM mapeada sem cópia extra do frontend…");
    } else {
        setMessage("N64 BOOT 4/6 • core usando ROM preparada por caminho…");
    }
    if (!impl_->core.loadGame(&gameInfo)) {
        setMessage("N64 BOOT E06 • Mupen recusou a ROM/contexto gráfico");
        cleanup();
        return;
    }
    impl_->gameLoaded = true;
    if (!hwRenderRequested_ || !hwRender_.context_reset) {
        setMessage("N64 BOOT E07 • GLideN64 não negociou contexto GLES3");
        cleanup();
        return;
    }

    if (shaderCacheReady_.load(std::memory_order_acquire)) {
        const std::string shaderDir = config_.systemDir + "/Mupen64plus/shaders";
        const std::size_t warmed = warmDirectoryPages(shaderDir, 2u * 1024u * 1024u);
        passiveWarmCacheReady_.store(warmed > 0, std::memory_order_release);
        if (warmed > 0) logPrint(ANDROID_LOG_INFO, "Passive shader cache warmed %zu bytes", warmed);
    }
    hwRender_.context_reset();
    callContextDestroy = true;
    loadSaveRam();

    retro_system_av_info avInfo{};
    impl_->core.getSystemAvInfo(&avInfo);
    impl_->targetFps = (avInfo.timing.fps >= 40.0 && avInfo.timing.fps <= 75.0)
        ? avInfo.timing.fps : 60.0;
    impl_->coreSampleRate = avInfo.timing.sample_rate > 1000.0 ? avInfo.timing.sample_rate : 44100.0;
    targetFps_.store(static_cast<float>(impl_->targetFps), std::memory_order_release);
    const bool adpfReady = impl_->perfHint.open(impl_->targetFps);
    adpfActive_.store(adpfReady, std::memory_order_release);
    if (adpfReady) {
        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");
        impl_->perfHint.bindSurface(window_);
        impl_->perfHint.setTargetScale(0.96);
    }

    const bool audioReady = impl_->openAudio(
        impl_->coreSampleRate,
        audioTargetBursts_.load(std::memory_order_acquire));
    if (!audioReady) logPrint(ANDROID_LOG_WARN, "N64 AAudio unavailable; continuing without audio output");

    const auto target = std::chrono::duration<double>(1.0 / impl_->targetFps);
    const auto targetDuration = std::chrono::duration_cast<std::chrono::steady_clock::duration>(target);
    const auto lateResetThreshold = std::chrono::duration_cast<std::chrono::steady_clock::duration>(target * 0.55);
    const float targetMs = static_cast<float>(1000.0 / impl_->targetFps);
    targetFrameMs_.store(targetMs, std::memory_order_release);
    burstShieldActive_.store(adpfReady && impl_->perfHint.burstCapable(), std::memory_order_release);
    warmStartActive_.store(true, std::memory_order_release);
    const bool passiveCache = passiveWarmCacheReady_.load(std::memory_order_acquire);
    setMessage(audioReady
        ? (passiveCache
            ? "N64 BOOT 5/6 • WarmCache ✓ • GLideN64 + AAudio, primeiro frame…"
            : "N64 BOOT 5/6 • GLideN64 + AAudio, primeiro frame…")
        : (passiveCache
            ? "N64 BOOT 5/6 • WarmCache ✓ • GLideN64, primeiro frame…"
            : "N64 BOOT 5/6 • GLideN64 pronto, primeiro frame…"));

    auto nextFrame = std::chrono::steady_clock::now() + targetDuration;
    std::uint32_t adaptationCounter = 0;
    std::uint64_t observedAudioUnderruns = 0;
    int stableStreak = 0;
    int warmStableFrames = 0;
    int candidateMode = 0;
    int candidateStreak = 0;
    float fastFrameEwma = targetMs;
    float slowFrameEwma = targetMs;
    float fastPresentEwma = 0.0f;
    float slowPresentEwma = 0.0f;
    float jitterEwma = 0.0f;
    float previousFrameMs = targetMs;
    float pressureDebt = 0.0f;
    const auto warmStartBegan = std::chrono::steady_clock::now();
    auto lastGovernorChange = warmStartBegan - std::chrono::seconds(5);
    auto governorHeadroomUntil = std::chrono::steady_clock::time_point{};
    auto governorBoostBegan = warmStartBegan;
    int governorMode = 0;  // 0 stable, 1 CPU, 2 GPU/present, 3 mixed.
    bool cruiseRelaxed = false;
    bool wasPaused = false;
    while (!stopRequested_.load(std::memory_order_acquire)) {
        const bool commandRan = processPendingCommand();
        const bool paused = paused_.load(std::memory_order_acquire);
        if (paused) {
            if (!wasPaused) {
                persistSaveRam(false);
                if (impl_->audioStream) impl_->reprimeAudio();
            }
            wasPaused = true;
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            nextFrame = std::chrono::steady_clock::now() + targetDuration;
            continue;
        }
        if (wasPaused || commandRan) {
            wasPaused = false;
            nextFrame = std::chrono::steady_clock::now() + targetDuration;
        }

        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            // Menus are commonly framebuffer-heavy. Give the renderer bounded
            // headroom and temporarily protect the existing audio reserve.
            impl_->armTransitionAudioShield(std::chrono::milliseconds(2400));
            impl_->adaptAudio(std::max(audioTargetBursts_.load(std::memory_order_acquire), 7));
            impl_->perfHint.notifySpike(false, true, "omnicore-n64-menu-present-spike");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil,
                std::chrono::steady_clock::now() + std::chrono::milliseconds(800));
        }
        if (interactionTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            // Predictive, bounded action burst. It is intentionally a workload
            // hint only: no clock mutation, no resolution change and no audio
            // buffer growth. This helps the frames immediately following
            // attacks/collisions where CPU logic and a new RDP effect often meet.
            impl_->armTransitionAudioShield(std::chrono::milliseconds(1200));
            impl_->perfHint.notifySpike(true, true, "omnicore-n64-action-microburst");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil,
                std::chrono::steady_clock::now() + std::chrono::milliseconds(420));
        }

        impl_->presentationTargetNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
            nextFrame.time_since_epoch()).count();
        const auto begin = std::chrono::steady_clock::now();
        impl_->core.run();
        // StartupAudioGate is evaluated only after the whole libretro frame has
        // delivered its PCM. This keeps AAudio from racing a partially produced
        // first/menu frame and also applies after pause/load-state reprimes.
        impl_->startAudioIfReady();
        const auto afterRun = std::chrono::steady_clock::now();
        const auto workNs = std::chrono::duration_cast<std::chrono::nanoseconds>(afterRun - begin).count();
        const float frameMs = std::chrono::duration<float, std::milli>(afterRun - begin).count();
        impl_->perfHint.report(workNs);
        recordFrame(frameMs, targetMs);

        // Audio underruns are handled as episodes immediately after the next
        // emulation slice, not delayed until the 60-frame adaptation cadence.
        const std::uint64_t ringUnderrunsNow = impl_->audioRing.underruns();
        if (ringUnderrunsNow > observedAudioUnderruns) {
            observedAudioUnderruns = ringUnderrunsNow;
            impl_->armTransitionAudioShield(std::chrono::milliseconds(2600));
            impl_->adaptAudio(std::max(audioTargetBursts_.load(std::memory_order_acquire), 7));
        }

        const auto controlNow = std::chrono::steady_clock::now();
        const float presentMs = lastPresentMs_.load(std::memory_order_acquire);

        // PrecisionGovernor v2 uses a fast signal for responsiveness and a slow
        // signal for confidence. A mode change requires both sustained pressure
        // and a stable bottleneck identity, so scene transitions do not make the
        // governor bounce between CPU/GPU/MIX.
        fastFrameEwma += (frameMs - fastFrameEwma) * 0.12f;
        slowFrameEwma += (frameMs - slowFrameEwma) * 0.025f;
        fastPresentEwma += (presentMs - fastPresentEwma) * 0.14f;
        slowPresentEwma += (presentMs - slowPresentEwma) * 0.030f;
        const float instantJitter = std::abs(frameMs - previousFrameMs);
        previousFrameMs = frameMs;
        jitterEwma += (instantJitter - jitterEwma) * 0.08f;
        frameJitterMs_.store(jitterEwma, std::memory_order_release);

        const float fastRatio = fastFrameEwma / std::max(1.0f, targetMs);
        const float slowRatio = slowFrameEwma / std::max(1.0f, targetMs);
        const bool framePressure = fastRatio > 1.10f || slowRatio > 1.065f;
        const float severity = std::max(fastRatio - 1.08f, slowRatio - 1.045f);
        if (framePressure) {
            pressureDebt = std::min(1.0f, pressureDebt + 0.018f + std::max(0.0f, severity) * 0.10f);
            stableStreak = 0;
            warmStableFrames = 0;
        } else {
            pressureDebt = std::max(0.0f, pressureDebt - (slowRatio <= 1.03f ? 0.018f : 0.008f));
            if (slowRatio <= 1.045f && jitterEwma <= targetMs * 0.16f) {
                stableStreak = std::min(stableStreak + 1, 300);
                warmStableFrames = std::min(warmStableFrames + 1, 240);
            } else {
                stableStreak = std::max(0, stableStreak - 1);
                warmStableFrames = std::max(0, warmStableFrames - 1);
            }
        }

        const float presentShare = slowPresentEwma / std::max(1.0f, slowFrameEwma);
        const bool presentHeavy = slowPresentEwma >= std::max(3.2f, targetMs * 0.20f) &&
            presentShare >= 0.235f;
        const float cpuSideMs = std::max(0.0f, slowFrameEwma - slowPresentEwma);
        const bool cpuHeavy = cpuSideMs >= targetMs * 0.74f;
        const int observedMode = presentHeavy ? (cpuHeavy ? 3 : 2) : 1;

        if (framePressure || pressureDebt >= 0.20f) {
            if (candidateMode == observedMode) {
                candidateStreak = std::min(candidateStreak + 1, 180);
            } else {
                // A new diagnosis starts with low confidence instead of replacing
                // the active mode immediately.
                candidateMode = observedMode;
                candidateStreak = 1;
            }
        } else if (candidateStreak > 0) {
            candidateStreak = std::max(0, candidateStreak - 2);
            if (candidateStreak == 0) candidateMode = 0;
        }

        const float classifierConfidence = candidateMode == 0
            ? 0.0f
            : std::clamp(static_cast<float>(candidateStreak) / 45.0f, 0.0f, 1.0f);
        precisionGovernorConfidence_.store(classifierConfidence, std::memory_order_release);

        // MicroBurstShield catches short sudden hitches that are visible but
        // too small to justify a governor mode change. This is especially useful
        // for collision/hit effects and first-use scene work. The hint is bounded
        // by PerformanceHintSession's cooldown and never changes fidelity.
        const float transientBaseline = std::max(targetMs, slowFrameEwma);
        const bool suddenMicroSpike = frameMs > targetMs * 1.30f &&
            frameMs > transientBaseline * 1.16f && pressureDebt < 0.55f;
        const bool catastrophicSpike = frameMs > targetMs * 1.85f;
        if (suddenMicroSpike || catastrophicSpike) {
            impl_->armTransitionAudioShield(std::chrono::milliseconds(1800));
            const bool spikeGpu = presentMs >= std::max(3.6f, targetMs * 0.22f);
            impl_->perfHint.notifySpike(
                !spikeGpu,
                spikeGpu,
                spikeGpu ? "omnicore-n64-v21-transient-gpu" : "omnicore-n64-v21-transient-cpu");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil, controlNow + std::chrono::milliseconds(520));
        }

        const auto modeDwell = controlNow - lastGovernorChange;
        const bool canEnter = governorMode == 0 && modeDwell >= std::chrono::milliseconds(2500);
        const bool canSwitch = governorMode != 0 && modeDwell >= std::chrono::seconds(4);
        const bool confidentPressure = pressureDebt >= 0.42f && candidateStreak >= 24;
        const bool confidentSwitch = pressureDebt >= 0.50f && candidateStreak >= 45;
        const bool shouldApply = candidateMode != 0 && candidateMode != governorMode &&
            ((canEnter && confidentPressure) || (canSwitch && confidentSwitch));

        if (shouldApply) {
            const int nextMode = candidateMode;
            const bool cpuPressure = nextMode == 1 || nextMode == 3;
            const bool gpuPressure = nextMode == 2 || nextMode == 3;
            const char* id = nextMode == 1 ? "omnicore-n64-precision-v2-cpu" :
                (nextMode == 2 ? "omnicore-n64-precision-v2-gpu" : "omnicore-n64-precision-v2-mixed");
            impl_->perfHint.notifyIncrease(cpuPressure, gpuPressure, id);
            if (adpfReady) {
                // Smaller target changes than Alpha 15 reduce thermal oscillation.
                impl_->perfHint.setTargetScale(nextMode == 1 ? 0.92 : (nextMode == 2 ? 0.94 : 0.90));
            }
            governorMode = nextMode;
            precisionGovernorMode_.store(nextMode, std::memory_order_release);
            governorHeadroomUntil = controlNow + std::chrono::milliseconds(1800);
            governorBoostBegan = controlNow;
            cruiseRelaxed = false;
            lastGovernorChange = controlNow;
            stableStreak = 0;
        }

        // CruiseGuard prevents a modest, already-controlled workload from
        // holding the strictest ADPF target forever. After several seconds of
        // contained pressure it relaxes slightly to reduce long-session thermal
        // oscillation. Any renewed pressure immediately restores full headroom.
        if (governorMode != 0 && !cruiseRelaxed &&
            controlNow - governorBoostBegan >= std::chrono::seconds(7) &&
            controlNow >= governorHeadroomUntil &&
            slowRatio <= 1.09f && pressureDebt <= 0.56f &&
            jitterEwma <= targetMs * 0.20f) {
            if (adpfReady) {
                impl_->perfHint.setTargetScale(governorMode == 3 ? 0.95 : 0.97);
            }
            cruiseRelaxed = true;
        }
        if (governorMode != 0 && cruiseRelaxed &&
            (fastRatio > 1.14f || pressureDebt >= 0.62f || jitterEwma > targetMs * 0.27f)) {
            const bool cpuPressure = governorMode == 1 || governorMode == 3;
            const bool gpuPressure = governorMode == 2 || governorMode == 3;
            impl_->perfHint.notifySpike(cpuPressure, gpuPressure, "omnicore-n64-v21-cruise-reengage");
            if (adpfReady) {
                impl_->perfHint.setTargetScale(
                    governorMode == 1 ? 0.92 : (governorMode == 2 ? 0.94 : 0.90));
            }
            cruiseRelaxed = false;
            governorBoostBegan = controlNow;
            governorHeadroomUntil = controlNow + std::chrono::milliseconds(900);
        }

        const bool recoveryConfidence = slowRatio <= 1.045f && pressureDebt <= 0.12f &&
            jitterEwma <= targetMs * 0.15f;
        if (governorMode != 0 && recoveryConfidence && stableStreak >= 120 &&
            controlNow >= governorHeadroomUntil && modeDwell >= std::chrono::seconds(3)) {
            governorMode = 0;
            precisionGovernorMode_.store(0, std::memory_order_release);
            precisionGovernorConfidence_.store(0.0f, std::memory_order_release);
            impl_->perfHint.notifyReset(true, true, "omnicore-n64-precision-v2-recovery");
            if (adpfReady) {
                impl_->perfHint.setTargetScale(
                    warmStartActive_.load(std::memory_order_acquire) ? 0.96 : 1.0);
            }
            stableStreak = 0;
            candidateStreak = 0;
            candidateMode = 0;
            cruiseRelaxed = false;
            governorBoostBegan = controlNow;
            lastGovernorChange = controlNow;
        }

        if (warmStartActive_.load(std::memory_order_acquire)) {
            const auto warmElapsed = controlNow - warmStartBegan;
            const bool minimumWarmupDone = warmElapsed >= std::chrono::seconds(4);
            const bool stableEnough = warmStableFrames >= 90;
            const bool maximumWarmupDone = warmElapsed >= std::chrono::seconds(10);
            if ((minimumWarmupDone && stableEnough) || maximumWarmupDone) {
                warmStartActive_.store(false, std::memory_order_release);
                if (governorMode == 0) {
                    impl_->perfHint.notifyReset(true, true, "omnicore-n64-v2-steady-state");
                    if (adpfReady) impl_->perfHint.setTargetScale(1.0);
                }
            }
        }

        // Audio is controlled only by actual AAudio/ring evidence. Frame spikes
        // no longer force 7/8-burst buffers and therefore cannot create latency
        // or extra memory pressure as a side effect of renderer stress.
        if (++adaptationCounter >= 60u) {
            adaptationCounter = 0;
            int requestedBursts = audioTargetBursts_.load(std::memory_order_acquire);
            if (warmStartActive_.load(std::memory_order_acquire)) requestedBursts = std::max(requestedBursts, 6);
            impl_->adaptAudio(requestedBursts);
        }
        // Single pacing owner: EGL swap interval is zero, so this is the only
        // explicit frame scheduler. If emulation itself is slower than target,
        // no extra sleep is added. Old debt is discarded rather than repaid with
        // a burst of back-to-back frames.
        const auto now = std::chrono::steady_clock::now();
        if (nextFrame > now) {
            std::this_thread::sleep_until(nextFrame);
            nextFrame += targetDuration;
        } else if (now - nextFrame > lateResetThreshold) {
            nextFrame = now + targetDuration;
        } else {
            nextFrame += targetDuration;
        }
    }
    setMessage("N64 STOP • persistindo saves e encerrando sessão…");
    cleanup();
}

bool LibretroHost::environmentCallback(unsigned cmd, void* data) {
    return instance().environment(cmd, data);
}
void LibretroHost::videoCallback(const void* data, unsigned width, unsigned height, std::size_t pitch) {
    instance().videoRefresh(data, width, height, pitch);
}
void LibretroHost::audioSampleCallback(std::int16_t left, std::int16_t right) {
    instance().audioSample(left, right);
}
std::size_t LibretroHost::audioBatchCallback(const std::int16_t* data, std::size_t frames) {
    return instance().audioBatch(data, frames);
}
void LibretroHost::inputPollCallback() {}
std::int16_t LibretroHost::inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
    return instance().inputState(port, device, index, id);
}
bool LibretroHost::clearThreadWaitsCallback(unsigned, void*) { return true; }
std::uintptr_t LibretroHost::currentFramebufferCallback() {
    const auto& host = instance();
    if (!host.impl_) return 0u;
    return host.impl_->directPresent ? 0u : static_cast<std::uintptr_t>(host.impl_->frontFbo);
}
abi::retro_proc_address_t LibretroHost::procAddressCallback(const char* symbol) {
    if (!symbol || !*symbol) return nullptr;
    const auto eglProc = eglGetProcAddress(symbol);
    if (eglProc) return reinterpret_cast<abi::retro_proc_address_t>(eglProc);
    return reinterpret_cast<abi::retro_proc_address_t>(dlsym(RTLD_DEFAULT, symbol));
}

}  // namespace omnicore::n64
