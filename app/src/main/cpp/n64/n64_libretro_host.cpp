#include "n64_libretro_host.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <numeric>

namespace omnicore::n64 {
namespace {
constexpr const char* kLogTag = "OmniCoreN64";
constexpr const char* kCoreSoname = "libmupen64plus_next_libretro.so";

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
    using retro_get_system_info_t = void (*)(retro_system_info*);
    using retro_get_system_av_info_t = void (*)(retro_system_av_info*);
    using retro_load_game_t = bool (*)(const retro_game_info*);
    using retro_unload_game_t = void (*)();
    using retro_run_t = void (*)();
    using retro_set_controller_port_device_t = void (*)(unsigned, unsigned);

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
    retro_get_system_info_t getSystemInfo = nullptr;
    retro_get_system_av_info_t getSystemAvInfo = nullptr;
    retro_load_game_t loadGame = nullptr;
    retro_unload_game_t unloadGame = nullptr;
    retro_run_t run = nullptr;
    retro_set_controller_port_device_t setControllerPortDevice = nullptr;

    template <typename T>
    bool symbol(T& out, const char* name) {
        out = reinterpret_cast<T>(dlsym(handle, name));
        if (!out) logPrint(ANDROID_LOG_ERROR, "missing libretro symbol %s", name);
        return out != nullptr;
    }

    bool load() {
        close();
        handle = dlopen(kCoreSoname, RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            logPrint(ANDROID_LOG_ERROR, "dlopen failed: %s", dlerror());
            return false;
        }
        bool ok = true;
        ok &= symbol(apiVersion, "retro_api_version");
        ok &= symbol(setEnvironment, "retro_set_environment");
        ok &= symbol(setVideoRefresh, "retro_set_video_refresh");
        ok &= symbol(setAudioSample, "retro_set_audio_sample");
        ok &= symbol(setAudioSampleBatch, "retro_set_audio_sample_batch");
        ok &= symbol(setInputPoll, "retro_set_input_poll");
        ok &= symbol(setInputState, "retro_set_input_state");
        ok &= symbol(init, "retro_init");
        ok &= symbol(deinit, "retro_deinit");
        ok &= symbol(getSystemInfo, "retro_get_system_info");
        ok &= symbol(getSystemAvInfo, "retro_get_system_av_info");
        ok &= symbol(loadGame, "retro_load_game");
        ok &= symbol(unloadGame, "retro_unload_game");
        ok &= symbol(run, "retro_run");
        ok &= symbol(setControllerPortDevice, "retro_set_controller_port_device");
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
        getSystemInfo = nullptr;
        getSystemAvInfo = nullptr;
        loadGame = nullptr;
        unloadGame = nullptr;
        run = nullptr;
        setControllerPortDevice = nullptr;
    }

    ~CoreApi() { close(); }
};
}  // namespace

struct LibretroHost::Impl {
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
        eglSwapInterval(display, 1);
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
    stopRequested_.store(false, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    buttonMask_.store(0, std::memory_order_release);
    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);
    {
        std::lock_guard<std::mutex> lock(telemetryMutex_);
        frameWindow_.fill(0.0f);
        frameWindowCount_ = 0;
        frameWindowWrite_ = 0;
        audioUnderruns_ = 0;
    }
    targetFrameMs_.store(1000.0f / 60.0f, std::memory_order_release);
    hwRenderRequested_ = false;
    hwRender_ = {};
    setMessage("N64 BOOT 1/6 • iniciando runtime isolado…");
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
    options_["mupen64plus-rdp-plugin"] = "gliden64";
    options_["mupen64plus-rsp-plugin"] = "hle"; // LLE is not compiled in this Android core yet.
    options_["mupen64plus-cpucore"] = config_.cpuMode == "cached_interpreter" ? "cached_interpreter" : "dynamic_recompiler";
    options_["mupen64plus-43screensize"] = config_.internalResolution >= 2 ? "1280x960" : "640x480";
    options_["mupen64plus-aspect"] = "4:3";
    options_["mupen64plus-ThreadedRenderer"] = boolOption(config_.threadedRenderer);
    options_["mupen64plus-EnableFBEmulation"] = boolOption(config_.framebufferEmulation);
    options_["mupen64plus-FXAA"] = "0";
    options_["mupen64plus-MultiSampling"] = "0";
    options_["mupen64plus-HybridFilter"] = "False";
    options_["mupen64plus-txHiresEnable"] = "False";
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
            return true;
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
            if (data) *reinterpret_cast<char**>(data) = const_cast<char*>(config_.systemDir.c_str());
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data) *reinterpret_cast<const char**>(data) = config_.saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY: {
            static thread_local std::string contentDir;
            const auto slash = config_.romPath.find_last_of('/');
            contentDir = slash == std::string::npos ? config_.romPath : config_.romPath.substr(0, slash);
            if (data) *reinterpret_cast<const char**>(data) = contentDir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            return data && *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
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
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data) static_cast<retro_log_callback*>(data)->log = coreLog;
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (data) *static_cast<unsigned*>(data) = 7u;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 0u;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<unsigned*>(data) = RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO;
            return true;
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            if (data) *static_cast<float*>(data) = 60.0f;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS:
            if (data) *static_cast<unsigned*>(data) = 1u;
            return true;
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
            return true;
        case abi::RETRO_ENVIRONMENT_SET_HW_RENDER: {
            if (!data || !impl_ || impl_->context == EGL_NO_CONTEXT) return false;
            auto* requested = static_cast<abi::retro_hw_render_callback*>(data);
            const bool supported = requested->context_type == abi::RETRO_HW_CONTEXT_OPENGLES3 ||
                (requested->context_type == abi::RETRO_HW_CONTEXT_OPENGLES_VERSION && requested->version_major == 3u && requested->version_minor <= 1u);
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
            return true;
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
    if (impl_->presentedFrames == 1) setMessage("N64 RUN OK • primeiro frame GLES3 recebido");
}

std::size_t LibretroHost::audioBatch(const std::int16_t*, std::size_t frames) {
    return frames; // Non-blocking bootstrap; dedicated N64 AAudio comes next.
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
    int audioUnderruns = 0;
    {
        std::lock_guard<std::mutex> lock(telemetryMutex_);
        count = frameWindowCount_;
        audioUnderruns = audioUnderruns_;
        for (std::size_t i = 0; i < count; ++i) snapshot[i] = frameWindow_[i];
    }
    Telemetry out;
    out.sampleWindowFrames = static_cast<int>(count);
    out.audioUnderruns = audioUnderruns;
    if (count == 0) return out;
    float total = 0.0f;
    for (std::size_t i = 0; i < count; ++i) total += snapshot[i];
    out.averageFrameMs = total / static_cast<float>(count);
    std::sort(snapshot.begin(), snapshot.begin() + static_cast<std::ptrdiff_t>(count));
    const std::size_t p95Index = std::min(count - 1, static_cast<std::size_t>(std::floor((count - 1) * 0.95)));
    out.p95FrameMs = snapshot[p95Index];
    const float targetMs = targetFrameMs_.load(std::memory_order_acquire);
    for (std::size_t i = 0; i < count; ++i) if (snapshot[i] > targetMs * 1.35f) ++out.droppedFrames;
    return out;
}

void LibretroHost::run() {
    impl_ = std::make_unique<Impl>();
    bool callContextDestroy = false;
    auto cleanup = [&]() {
        if (impl_) {
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
    const int renderWidth = config_.internalResolution >= 2 ? 1280 : 640;
    const int renderHeight = config_.internalResolution >= 2 ? 960 : 480;
    if (!impl_->createFrontendFramebuffer(renderWidth, renderHeight)) {
        setMessage("N64 BOOT E02 • framebuffer GLES3 inválido");
        cleanup();
        return;
    }
    setMessage("N64 BOOT 2/6 • GLES3 pronto, carregando Mupen64Plus-Next…");
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

    bool loadOk = false;
    {
        MappedFile rom;
        if (!rom.openReadOnly(config_.romPath)) {
            setMessage("N64 BOOT E05 • não consegui mapear a ROM preparada");
            cleanup();
            return;
        }
        setMessage("N64 BOOT 4/6 • ROM mapeada sem cópia extra do frontend…");
        retro_game_info gameInfo{};
        gameInfo.path = config_.romPath.c_str();
        gameInfo.data = rom.data();
        gameInfo.size = rom.size();
        loadOk = impl_->core.loadGame(&gameInfo);
    }
    if (!loadOk) {
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
    retro_system_av_info avInfo{};
    impl_->core.getSystemAvInfo(&avInfo);
    impl_->targetFps = (avInfo.timing.fps >= 40.0 && avInfo.timing.fps <= 75.0) ? avInfo.timing.fps : 60.0;
    const auto target = std::chrono::duration<double>(1.0 / impl_->targetFps);
    const float targetMs = static_cast<float>(1000.0 / impl_->targetFps);
    targetFrameMs_.store(targetMs, std::memory_order_release);
    setMessage("N64 BOOT 5/6 • GLideN64 inicializado, aguardando primeiro frame…");

    auto nextFrame = std::chrono::steady_clock::now();
    while (!stopRequested_.load(std::memory_order_acquire)) {
        if (paused_.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            nextFrame = std::chrono::steady_clock::now();
            continue;
        }
        const auto begin = std::chrono::steady_clock::now();
        impl_->core.run();
        const auto afterRun = std::chrono::steady_clock::now();
        recordFrame(std::chrono::duration<float, std::milli>(afterRun - begin).count(), targetMs);
        nextFrame += std::chrono::duration_cast<std::chrono::steady_clock::duration>(target);
        const auto now = std::chrono::steady_clock::now();
        if (nextFrame > now) std::this_thread::sleep_until(nextFrame);
        else if (now - nextFrame > std::chrono::milliseconds(80)) nextFrame = now;
    }
    setMessage("N64 STOP • encerrando sessão…");
    cleanup();
}

bool LibretroHost::environmentCallback(unsigned cmd, void* data) { return instance().environment(cmd, data); }
void LibretroHost::videoCallback(const void* data, unsigned width, unsigned height, std::size_t pitch) { instance().videoRefresh(data, width, height, pitch); }
void LibretroHost::audioSampleCallback(std::int16_t, std::int16_t) {}
std::size_t LibretroHost::audioBatchCallback(const std::int16_t* data, std::size_t frames) { return instance().audioBatch(data, frames); }
void LibretroHost::inputPollCallback() {}
std::int16_t LibretroHost::inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) { return instance().inputState(port, device, index, id); }
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
