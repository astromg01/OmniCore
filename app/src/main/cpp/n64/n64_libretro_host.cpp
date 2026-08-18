#include "n64_libretro_host.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
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

std::int16_t axisFromFloat(float value) {
    return static_cast<std::int16_t>(std::lround(std::clamp(value, -1.0f, 1.0f) * 32767.0f));
}

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
        const std::size_t count = static_cast<std::size_t>(
            std::min<std::uint64_t>(samples, write - read));
        if (count > 0) {
            const std::size_t start = static_cast<std::size_t>(read % data_.size());
            const std::size_t first = std::min(count, data_.size() - start);
            std::memcpy(output, data_.data() + start, first * sizeof(std::int16_t));
            if (first < count) {
                std::memcpy(output + first, data_.data(), (count - first) * sizeof(std::int16_t));
            }
        }
        if (count < samples) {
            std::fill(output + count, output + samples, 0);
            underruns_.fetch_add(1, std::memory_order_relaxed);
        }
        read_.store(read + count, std::memory_order_release);
        return count;
    }

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
#ifdef MADV_SEQUENTIAL
        madvise(data_, size_, MADV_SEQUENTIAL);
#endif
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
    std::uint64_t presentedFrames = 0;
    bool coreInitialized = false;
    bool gameLoaded = false;
    double targetFps = 60.0;

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
    std::uint64_t lastRingUnderruns = 0;
    double resampleAccumulator = 0.0;
    std::array<std::int16_t, 8192> resampleScratch{};

    static aaudio_data_callback_result_t audioCallback(
        AAudioStream*, void* userData, void* audioData, std::int32_t numFrames) {
        auto* self = static_cast<Impl*>(userData);
        if (!self || !audioData || numFrames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
        const std::size_t requested = static_cast<std::size_t>(numFrames) * 2u;
        const std::size_t read = self->audioRing.pop(static_cast<std::int16_t*>(audioData), requested);
        if (read < requested && self->owner) {
            self->owner->audioUnderruns_.fetch_add(1, std::memory_order_relaxed);
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    bool createEgl(ANativeWindow* window) {
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
        eglQuerySurface(display, surface, EGL_WIDTH, &surfaceWidth);
        eglQuerySurface(display, surface, EGL_HEIGHT, &surfaceHeight);
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
    }

    void updateAudioTelemetry() {
        if (!owner || outputSampleRate <= 0) return;
        const float fillMs = static_cast<float>(audioRing.availableSamples() / 2u) * 1000.0f /
            static_cast<float>(outputSampleRate);
        int bufferFrames = 0;
        if (audioStream) bufferFrames = std::max(0, AAudioStream_getBufferSizeInFrames(audioStream));
        const float bufferMs = static_cast<float>(bufferFrames) * 1000.0f /
            static_cast<float>(outputSampleRate);
        owner->audioFillMs_.store(fillMs, std::memory_order_release);
        owner->audioBufferMs_.store(bufferMs, std::memory_order_release);
    }

    bool openAudio(double sampleRate, int requestedBursts) {
        closeAudio();
        audioRing.clear();
        coreSampleRate = sampleRate > 1000.0 ? sampleRate : 44100.0;
        requestedBursts = std::clamp(requestedBursts, 2, 8);
        resampleAccumulator = 0.0;

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
            AAudioStream_setBufferSizeInFrames(audioStream, framesPerBurst * appliedAudioBursts);
            // Start with enough PCM queued to survive scheduler jitter, then let
            // SmartPerf reduce latency only after the stream proves stable.
            audioPrimeFrames = std::min<int>(
                static_cast<int>(audioRing.capacitySamples() / 4u),
                std::max(framesPerBurst * 4, outputSampleRate / 30));
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
        audioPrimeFrames = 0;
        resampleAccumulator = 0.0;
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
        resampleAccumulator = 0.0;
        lastRingUnderruns = 0;
        stableAudioChecks = 0;
        updateAudioTelemetry();
    }

    void startAudioIfReady() {
        if (!audioStream || audioStarted) return;
        if (audioRing.availableSamples() / 2u < static_cast<std::size_t>(std::max(1, audioPrimeFrames))) return;
        if (AAudioStream_requestStart(audioStream) == AAUDIO_OK) audioStarted = true;
    }

    double audioSyncScale() {
        if (!audioStream || outputSampleRate <= 0) return 1.0;
        updateAudioTelemetry();
        const float fillMs = owner ? owner->audioFillMs_.load(std::memory_order_acquire) : 0.0f;
        const float bufferMs = owner ? owner->audioBufferMs_.load(std::memory_order_acquire) : 0.0f;
        const float targetFillMs = std::max(34.0f, bufferMs * 1.55f);
        double scale = 1.0;
        if (fillMs < targetFillMs * 0.55f) scale = 1.0075;
        else if (fillMs < targetFillMs * 0.80f) scale = 1.0035;
        else if (fillMs > targetFillMs * 1.75f) scale = 0.9945;
        else if (fillMs > targetFillMs * 1.40f) scale = 0.9975;
        if (owner) {
            owner->pacingCorrectionPct_.store(
                static_cast<float>((scale - 1.0) * 100.0), std::memory_order_release);
        }
        return scale;
    }

    void pushAudio(const std::int16_t* data, std::size_t frames) {
        if (!data || frames == 0 || !audioStream) return;
        const double syncScale = audioSyncScale();
        const double desiredOutRate = static_cast<double>(std::max(1, outputSampleRate)) * syncScale;
        if (std::abs(desiredOutRate - coreSampleRate) < 1.0) {
            audioRing.push(data, frames * 2u);
            startAudioIfReady();
            updateAudioTelemetry();
            return;
        }

        std::size_t scratchCount = 0;
        auto flush = [&]() {
            if (scratchCount > 0) {
                audioRing.push(resampleScratch.data(), scratchCount);
                scratchCount = 0;
            }
        };
        for (std::size_t i = 0; i < frames; ++i) {
            resampleAccumulator += desiredOutRate;
            while (resampleAccumulator >= coreSampleRate) {
                if (scratchCount + 2 > resampleScratch.size()) flush();
                resampleScratch[scratchCount++] = data[i * 2u];
                resampleScratch[scratchCount++] = data[i * 2u + 1u];
                resampleAccumulator -= coreSampleRate;
            }
        }
        flush();
        startAudioIfReady();
        updateAudioTelemetry();
    }

    void adaptAudio(int requestedBursts) {
        if (!audioStream || framesPerBurst <= 0) return;
        requestedBursts = std::clamp(requestedBursts, 2, 8);
        const int xruns = std::max(0, AAudioStream_getXRunCount(audioStream));
        const auto underruns = audioRing.underruns();
        int next = appliedAudioBursts;
        if (xruns > lastXRunCount || underruns > lastRingUnderruns) {
            next = std::min(8, std::max(requestedBursts, appliedAudioBursts + 2));
            stableAudioChecks = 0;
        } else if (next < requestedBursts) {
            next = requestedBursts;
            stableAudioChecks = 0;
        } else if (next > requestedBursts && ++stableAudioChecks >= 12) {
            --next;
            stableAudioChecks = 0;
        }
        if (next != appliedAudioBursts) {
            AAudioStream_setBufferSizeInFrames(audioStream, framesPerBurst * next);
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
    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);
    {
        std::lock_guard<std::mutex> lock(telemetryMutex_);
        frameWindow_.fill(0.0f);
        frameWindowCount_ = 0;
        frameWindowWrite_ = 0;
    }
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        pendingCommand_ = CommandType::NONE;
        pendingStatePath_.clear();
    }
    lastSaveRamHash_ = 0;
    audioUnderruns_.store(0, std::memory_order_release);
    audioFillMs_.store(0.0f, std::memory_order_release);
    audioBufferMs_.store(0.0f, std::memory_order_release);
    targetFps_.store(60.0f, std::memory_order_release);
    pacingCorrectionPct_.store(0.0f, std::memory_order_release);
    targetFrameMs_.store(1000.0f / 60.0f, std::memory_order_release);
    hwRenderRequested_ = false;
    hwRender_ = {};
    setMessage("N64 BOOT 1/6 • runtime Alpha 7 single-pacer…");
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
    const auto bit = static_cast<std::uint16_t>(1u << retroPadId);
    if (pressed) buttonMask_.fetch_or(bit, std::memory_order_acq_rel);
    else buttonMask_.fetch_and(static_cast<std::uint16_t>(~bit), std::memory_order_acq_rel);
}

void LibretroHost::setAnalog(float x, float y, float cX, float cY) {
    analogX_.store(axisFromFloat(x), std::memory_order_release);
    analogY_.store(axisFromFloat(y), std::memory_order_release);
    cX_.store(axisFromFloat(cX), std::memory_order_release);
    cY_.store(axisFromFloat(cY), std::memory_order_release);
}

void LibretroHost::buildCoreOptions() {
    std::lock_guard<std::mutex> lock(optionMutex_);
    options_.clear();
    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";
    const bool leanGraphics = !config_.framebufferEmulation;

    options_["mupen64plus-rdp-plugin"] = "gliden64";
    options_["mupen64plus-rsp-plugin"] = "hle";
    options_["mupen64plus-cpucore"] = config_.cpuMode == "cached_interpreter" ? "cached_interpreter" : "dynamic_recompiler";
    options_["mupen64plus-43screensize"] = config_.internalResolution >= 2 ? "1280x960" : "640x480";
    options_["mupen64plus-169screensize"] = config_.internalResolution >= 2 ? "1280x720" : "640x360";
    options_["mupen64plus-aspect"] = wide ? config_.aspectRatio : "4:3";
    options_["mupen64plus-ThreadedRenderer"] = boolOption(config_.threadedRenderer);
    options_["mupen64plus-EnableFBEmulation"] = boolOption(config_.framebufferEmulation);
    options_["mupen64plus-EnableCopyColorToRDRAM"] = leanGraphics ? "Off" : "Async";
    options_["mupen64plus-EnableCopyDepthToRDRAM"] = leanGraphics ? "Off" : "Software";
    options_["mupen64plus-EnableCopyColorFromRDRAM"] = "False";
    options_["mupen64plus-EnableCopyAuxToRDRAM"] = "False";
    options_["mupen64plus-EnableLODEmulation"] = leanGraphics ? "False" : "True";
    options_["mupen64plus-EnableLegacyBlending"] = leanGraphics ? "True" : "False";
    options_["mupen64plus-EnableFragmentDepthWrite"] = "False";
    options_["mupen64plus-BackgroundMode"] = "OnePiece";
    options_["mupen64plus-BilinearMode"] = "3point";
    options_["mupen64plus-EnableNativeResFactor"] = "0";
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
    options_["mupen64plus-astick-deadzone"] = std::to_string(std::clamp(config_.analogDeadzonePercent, 4, 30));
    options_["mupen64plus-astick-sensitivity"] = std::to_string(std::clamp(config_.analogSensitivityPercent, 70, 130));
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
    if (!impl_ || impl_->display == EGL_NO_DISPLAY || impl_->frontFbo == 0) return;
    if (data != abi::RETRO_HW_FRAME_BUFFER_VALID) return;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, impl_->frontFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glBlitFramebuffer(0, 0, impl_->renderWidth, impl_->renderHeight,
                      0, 0, impl_->surfaceWidth, impl_->surfaceHeight,
                      GL_COLOR_BUFFER_BIT, GL_LINEAR);
    if (!eglSwapBuffers(impl_->display, impl_->surface)) {
        setMessage("N64 RUNTIME E04 • falha ao apresentar frame GLES3");
        stopRequested_.store(true, std::memory_order_release);
        return;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, impl_->frontFbo);
    glViewport(0, 0, impl_->renderWidth, impl_->renderHeight);
    ++impl_->presentedFrames;
    if (impl_->presentedFrames == 1) {
        setMessage(impl_->audioStream
            ? "N64 RUN OK • single-pacer GLES3 • AAudio nativo pronto"
            : "N64 RUN OK • single-pacer GLES3 • áudio indisponível");
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
        const auto mask = buttonMask_.load(std::memory_order_acquire);
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<std::int16_t>(mask);
        if (id <= RETRO_DEVICE_ID_JOYPAD_R3) return (mask & (1u << id)) ? 1 : 0;
        return 0;
    }
    if (device == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
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
    std::lock_guard<std::mutex> lock(telemetryMutex_);
    frameWindow_[frameWindowWrite_] = frameMs;
    frameWindowWrite_ = (frameWindowWrite_ + 1) % kTelemetryCapacity;
    frameWindowCount_ = std::min(frameWindowCount_ + 1, kTelemetryCapacity);
}

Telemetry LibretroHost::telemetry() const {
    std::array<float, kTelemetryCapacity> snapshot{};
    std::size_t count = 0;
    {
        std::lock_guard<std::mutex> lock(telemetryMutex_);
        count = frameWindowCount_;
        for (std::size_t i = 0; i < count; ++i) snapshot[i] = frameWindow_[i];
    }
    Telemetry out;
    out.sampleWindowFrames = static_cast<int>(count);
    out.audioUnderruns = audioUnderruns_.load(std::memory_order_acquire);
    out.audioFillMs = audioFillMs_.load(std::memory_order_acquire);
    out.audioBufferMs = audioBufferMs_.load(std::memory_order_acquire);
    out.targetFps = targetFps_.load(std::memory_order_acquire);
    out.pacingCorrectionPct = pacingCorrectionPct_.load(std::memory_order_acquire);
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
    impl_ = std::make_unique<Impl>(this);
    bool callContextDestroy = false;
    auto cleanup = [&]() {
        if (impl_) {
            if (impl_->gameLoaded) persistSaveRam(true);
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

    if (!impl_->createEgl(window_)) {
        setMessage("N64 BOOT E01 • não consegui criar EGL/GLES3");
        cleanup();
        return;
    }
    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";
    const int renderWidth = config_.internalResolution >= 2 ? 1280 : 640;
    const int renderHeight = wide
        ? (config_.internalResolution >= 2 ? 720 : 360)
        : (config_.internalResolution >= 2 ? 960 : 480);
    if (!impl_->createFrontendFramebuffer(renderWidth, renderHeight)) {
        setMessage("N64 BOOT E02 • framebuffer GLES3 inválido");
        cleanup();
        return;
    }
    setMessage("N64 BOOT 2/6 • GLES3 single-pacer, carregando Mupen64Plus-Next…");
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

    hwRender_.context_reset();
    callContextDestroy = true;
    loadSaveRam();

    retro_system_av_info avInfo{};
    impl_->core.getSystemAvInfo(&avInfo);
    impl_->targetFps = (avInfo.timing.fps >= 40.0 && avInfo.timing.fps <= 75.0)
        ? avInfo.timing.fps : 60.0;
    impl_->coreSampleRate = avInfo.timing.sample_rate > 1000.0 ? avInfo.timing.sample_rate : 44100.0;
    targetFps_.store(static_cast<float>(impl_->targetFps), std::memory_order_release);
    const bool audioReady = impl_->openAudio(
        impl_->coreSampleRate,
        audioTargetBursts_.load(std::memory_order_acquire));
    if (!audioReady) logPrint(ANDROID_LOG_WARN, "N64 AAudio unavailable; continuing without audio output");

    const auto target = std::chrono::duration<double>(1.0 / impl_->targetFps);
    const auto targetDuration = std::chrono::duration_cast<std::chrono::steady_clock::duration>(target);
    const auto lateResetThreshold = std::chrono::duration_cast<std::chrono::steady_clock::duration>(target * 0.55);
    const float targetMs = static_cast<float>(1000.0 / impl_->targetFps);
    targetFrameMs_.store(targetMs, std::memory_order_release);
    setMessage(audioReady
        ? "N64 BOOT 5/6 • GLideN64 + AAudio nativo, aguardando primeiro frame…"
        : "N64 BOOT 5/6 • GLideN64 pronto, aguardando primeiro frame…");

    auto nextFrame = std::chrono::steady_clock::now();
    std::uint32_t adaptationCounter = 0;
    std::uint32_t saveCounter = 0;
    bool wasPaused = false;
    while (!stopRequested_.load(std::memory_order_acquire)) {
        const bool commandRan = processPendingCommand();
        const bool paused = paused_.load(std::memory_order_acquire);
        if (paused) {
            if (!wasPaused && impl_->audioStream) impl_->reprimeAudio();
            wasPaused = true;
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            nextFrame = std::chrono::steady_clock::now();
            continue;
        }
        if (wasPaused || commandRan) {
            wasPaused = false;
            nextFrame = std::chrono::steady_clock::now();
        }

        const auto begin = std::chrono::steady_clock::now();
        impl_->core.run();
        const auto afterRun = std::chrono::steady_clock::now();
        recordFrame(std::chrono::duration<float, std::milli>(afterRun - begin).count(), targetMs);

        if (++adaptationCounter >= 60u) {
            adaptationCounter = 0;
            impl_->adaptAudio(audioTargetBursts_.load(std::memory_order_acquire));
        }
        if (++saveCounter >= 600u) {
            saveCounter = 0;
            persistSaveRam(false);
        }

        // Single pacing owner: EGL swap interval is zero, so this is the only
        // explicit frame scheduler. If emulation itself is slower than target,
        // no extra sleep is added. Old debt is discarded rather than repaid with
        // a burst of back-to-back frames.
        nextFrame += targetDuration;
        const auto now = std::chrono::steady_clock::now();
        if (nextFrame > now) {
            std::this_thread::sleep_until(nextFrame);
        } else if (now - nextFrame > lateResetThreshold) {
            nextFrame = now;
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
    return host.impl_ ? static_cast<std::uintptr_t>(host.impl_->frontFbo) : 0u;
}
abi::retro_proc_address_t LibretroHost::procAddressCallback(const char* symbol) {
    if (!symbol || !*symbol) return nullptr;
    const auto eglProc = eglGetProcAddress(symbol);
    if (eglProc) return reinterpret_cast<abi::retro_proc_address_t>(eglProc);
    return reinterpret_cast<abi::retro_proc_address_t>(dlsym(RTLD_DEFAULT, symbol));
}

}  // namespace omnicore::n64
