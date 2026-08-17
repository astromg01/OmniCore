#pragma once

#include "libretro_abi.h"

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

class GlPresenter {
public:
    GlPresenter() = default;
    ~GlPresenter();

    GlPresenter(const GlPresenter&) = delete;
    GlPresenter& operator=(const GlPresenter&) = delete;

    bool initialize(ANativeWindow* window);
    bool present(const std::uint8_t* data,
                 unsigned width,
                 unsigned height,
                 std::size_t pitch,
                 retro_pixel_format format);
    void shutdown();

    const std::string& lastError() const { return lastError_; }
    const char* backendName() const { return "EGL/GLES2"; }

private:
    bool buildProgram();
    GLuint compileShader(GLenum type, const char* source);
    void setError(const std::string& message);
    bool checkGl(const char* operation);
    bool uploadFrame(const std::uint8_t* data,
                     unsigned width,
                     unsigned height,
                     std::size_t pitch,
                     retro_pixel_format format);

    ANativeWindow* window_ = nullptr; // Borrowed; LibretroSession owns the reference.
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLConfig config_ = nullptr;

    GLuint program_ = 0;
    GLuint texture_ = 0;
    GLint positionLocation_ = -1;
    GLint texCoordLocation_ = -1;
    GLint samplerLocation_ = -1;

    unsigned textureWidth_ = 0;
    unsigned textureHeight_ = 0;
    int textureKind_ = 0; // 1 = RGB565, 2 = RGBA8888.
    int viewportWidth_ = 0;
    int viewportHeight_ = 0;
    std::vector<std::uint8_t> rgbaScratch_;
    std::string lastError_;
};
