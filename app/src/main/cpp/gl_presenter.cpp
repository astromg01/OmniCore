#include "gl_presenter.h"

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>

namespace {
std::string eglError(const char* where) {
    char text[96]{};
    std::snprintf(text, sizeof(text), "%s (EGL 0x%04x)", where,
                  static_cast<unsigned>(eglGetError()));
    return text;
}

constexpr char kVertexShader[] = R"(
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
)";

constexpr char kFragmentShader[] = R"(
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
)";

// Texture coordinates are vertically flipped because libretro software frames
// arrive top-to-bottom while OpenGL's texture origin is bottom-left.
constexpr std::array<GLfloat, 16> kQuad = {
    -1.0f, -1.0f,  0.0f, 1.0f,
     1.0f, -1.0f,  1.0f, 1.0f,
    -1.0f,  1.0f,  0.0f, 0.0f,
     1.0f,  1.0f,  1.0f, 0.0f,
};
} // namespace

GlPresenter::~GlPresenter() {
    shutdown();
}

void GlPresenter::setError(const std::string& message) {
    lastError_ = message;
}

GLuint GlPresenter::compileShader(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    if (!shader) {
        setError("glCreateShader falhou");
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<std::size_t>(std::max(1, length)), 0);
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        setError(std::string("shader GLES falhou: ") + log.data());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool GlPresenter::buildProgram() {
    const GLuint vertex = compileShader(GL_VERTEX_SHADER, kVertexShader);
    if (!vertex) return false;
    const GLuint fragment = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (!fragment) {
        glDeleteShader(vertex);
        return false;
    }

    program_ = glCreateProgram();
    glAttachShader(program_, vertex);
    glAttachShader(program_, fragment);
    glLinkProgram(program_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint length = 0;
        glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &length);
        std::vector<char> log(static_cast<std::size_t>(std::max(1, length)), 0);
        glGetProgramInfoLog(program_, length, nullptr, log.data());
        setError(std::string("programa GLES falhou: ") + log.data());
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }

    positionLocation_ = glGetAttribLocation(program_, "aPosition");
    texCoordLocation_ = glGetAttribLocation(program_, "aTexCoord");
    samplerLocation_ = glGetUniformLocation(program_, "uTexture");
    if (positionLocation_ < 0 || texCoordLocation_ < 0 || samplerLocation_ < 0) {
        setError("atributos do shader GLES não encontrados");
        return false;
    }
    return true;
}

bool GlPresenter::checkGl(const char* operation) {
    const GLenum error = glGetError();
    if (error == GL_NO_ERROR) return true;
    char text[96]{};
    std::snprintf(text, sizeof(text), "%s (GL 0x%04x)", operation,
                  static_cast<unsigned>(error));
    setError(text);
    return false;
}

bool GlPresenter::initialize(ANativeWindow* window) {
    shutdown();
    lastError_.clear();
    if (!window) {
        setError("Surface Android ausente");
        return false;
    }
    window_ = window;

    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        setError(eglError("eglGetDisplay falhou"));
        shutdown();
        return false;
    }
    if (eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
        setError(eglError("eglInitialize falhou"));
        shutdown();
        return false;
    }

    const EGLint attributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint count = 0;
    if (eglChooseConfig(display_, attributes, &config_, 1, &count) != EGL_TRUE || count < 1) {
        setError(eglError("eglChooseConfig falhou"));
        shutdown();
        return false;
    }

    EGLint visualId = 0;
    if (eglGetConfigAttrib(display_, config_, EGL_NATIVE_VISUAL_ID, &visualId) == EGL_TRUE) {
        // Width/height 0 keeps the SurfaceView's native size; only match the
        // native pixel format expected by EGL.
        ANativeWindow_setBuffersGeometry(window_, 0, 0, visualId);
    }

    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttributes);
    if (context_ == EGL_NO_CONTEXT) {
        setError(eglError("eglCreateContext falhou"));
        shutdown();
        return false;
    }

    surface_ = eglCreateWindowSurface(display_, config_, window_, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        setError(eglError("eglCreateWindowSurface falhou"));
        shutdown();
        return false;
    }
    if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
        setError(eglError("eglMakeCurrent falhou"));
        shutdown();
        return false;
    }
    eglSwapInterval(display_, 1);

    if (!buildProgram()) {
        shutdown();
        return false;
    }

    glGenTextures(1, &texture_);
    if (!texture_) {
        setError("glGenTextures falhou");
        shutdown();
        return false;
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    return checkGl("inicialização GLES");
}

bool GlPresenter::uploadFrame(const std::uint8_t* data,
                              unsigned width,
                              unsigned height,
                              std::size_t pitch,
                              retro_pixel_format format) {
    glBindTexture(GL_TEXTURE_2D, texture_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    if (format == RETRO_PIXEL_FORMAT_RGB565) {
        const std::size_t rowBytes = static_cast<std::size_t>(width) * 2u;
        const std::uint8_t* upload = data;
        if (pitch != rowBytes) {
            rgbaScratch_.resize(rowBytes * static_cast<std::size_t>(height));
            for (unsigned y = 0; y < height; ++y) {
                std::memcpy(rgbaScratch_.data() + static_cast<std::size_t>(y) * rowBytes,
                            data + static_cast<std::size_t>(y) * pitch, rowBytes);
            }
            upload = rgbaScratch_.data();
        }

        const bool recreate = textureWidth_ != width || textureHeight_ != height || textureKind_ != 1;
        if (recreate) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB,
                         static_cast<GLsizei>(width), static_cast<GLsizei>(height), 0,
                         GL_RGB, GL_UNSIGNED_SHORT_5_6_5, upload);
            textureWidth_ = width;
            textureHeight_ = height;
            textureKind_ = 1;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            static_cast<GLsizei>(width), static_cast<GLsizei>(height),
                            GL_RGB, GL_UNSIGNED_SHORT_5_6_5, upload);
        }
        return checkGl("upload RGB565");
    }

    // XRGB8888 and 0RGB1555 are converted explicitly to byte-order-independent
    // RGBA8888 before upload. This avoids the channel/order ambiguity that can
    // happen when a CPU framebuffer is handed directly to a native Surface.
    rgbaScratch_.resize(static_cast<std::size_t>(width) * height * 4u);
    for (unsigned y = 0; y < height; ++y) {
        const auto* srcRow = data + static_cast<std::size_t>(y) * pitch;
        auto* dst = rgbaScratch_.data() + static_cast<std::size_t>(y) * width * 4u;
        for (unsigned x = 0; x < width; ++x) {
            std::uint8_t r = 0, g = 0, b = 0;
            if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
                std::uint32_t pixel = 0;
                std::memcpy(&pixel, srcRow + static_cast<std::size_t>(x) * 4u, sizeof(pixel));
                r = static_cast<std::uint8_t>((pixel >> 16) & 0xFFu);
                g = static_cast<std::uint8_t>((pixel >> 8) & 0xFFu);
                b = static_cast<std::uint8_t>(pixel & 0xFFu);
            } else {
                std::uint16_t pixel = 0;
                std::memcpy(&pixel, srcRow + static_cast<std::size_t>(x) * 2u, sizeof(pixel));
                const unsigned r5 = (pixel >> 10) & 31u;
                const unsigned g5 = (pixel >> 5) & 31u;
                const unsigned b5 = pixel & 31u;
                r = static_cast<std::uint8_t>(r5 * 255u / 31u);
                g = static_cast<std::uint8_t>(g5 * 255u / 31u);
                b = static_cast<std::uint8_t>(b5 * 255u / 31u);
            }
            dst[x * 4u + 0u] = r;
            dst[x * 4u + 1u] = g;
            dst[x * 4u + 2u] = b;
            dst[x * 4u + 3u] = 255u;
        }
    }

    const bool recreate = textureWidth_ != width || textureHeight_ != height || textureKind_ != 2;
    if (recreate) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                     static_cast<GLsizei>(width), static_cast<GLsizei>(height), 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, rgbaScratch_.data());
        textureWidth_ = width;
        textureHeight_ = height;
        textureKind_ = 2;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                        static_cast<GLsizei>(width), static_cast<GLsizei>(height),
                        GL_RGBA, GL_UNSIGNED_BYTE, rgbaScratch_.data());
    }
    return checkGl("upload RGBA");
}

bool GlPresenter::present(const std::uint8_t* data,
                          unsigned width,
                          unsigned height,
                          std::size_t pitch,
                          retro_pixel_format format) {
    if (display_ == EGL_NO_DISPLAY || surface_ == EGL_NO_SURFACE || context_ == EGL_NO_CONTEXT ||
        !program_ || !texture_ || !data || width == 0 || height == 0) {
        setError("presenter GLES não inicializado");
        return false;
    }

    if (!uploadFrame(data, width, height, pitch, format)) return false;

    EGLint surfaceWidth = 0;
    EGLint surfaceHeight = 0;
    eglQuerySurface(display_, surface_, EGL_WIDTH, &surfaceWidth);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &surfaceHeight);
    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
        setError(eglError("Surface EGL sem dimensões"));
        return false;
    }
    if (surfaceWidth != viewportWidth_ || surfaceHeight != viewportHeight_) {
        viewportWidth_ = surfaceWidth;
        viewportHeight_ = surfaceHeight;
        glViewport(0, 0, surfaceWidth, surfaceHeight);
    }

    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(program_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glUniform1i(samplerLocation_, 0);

    glEnableVertexAttribArray(static_cast<GLuint>(positionLocation_));
    glEnableVertexAttribArray(static_cast<GLuint>(texCoordLocation_));
    glVertexAttribPointer(static_cast<GLuint>(positionLocation_), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), kQuad.data());
    glVertexAttribPointer(static_cast<GLuint>(texCoordLocation_), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), kQuad.data() + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(positionLocation_));
    glDisableVertexAttribArray(static_cast<GLuint>(texCoordLocation_));

    if (!checkGl("draw GLES")) return false;
    if (eglSwapBuffers(display_, surface_) != EGL_TRUE) {
        setError(eglError("eglSwapBuffers falhou"));
        return false;
    }
    return true;
}

void GlPresenter::shutdown() {
    if (display_ != EGL_NO_DISPLAY) {
        if (context_ != EGL_NO_CONTEXT && surface_ != EGL_NO_SURFACE) {
            eglMakeCurrent(display_, surface_, surface_, context_);
            if (texture_) glDeleteTextures(1, &texture_);
            if (program_) glDeleteProgram(program_);
        }
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }
    window_ = nullptr;
    display_ = EGL_NO_DISPLAY;
    surface_ = EGL_NO_SURFACE;
    context_ = EGL_NO_CONTEXT;
    config_ = nullptr;
    program_ = 0;
    texture_ = 0;
    positionLocation_ = -1;
    texCoordLocation_ = -1;
    samplerLocation_ = -1;
    textureWidth_ = 0;
    textureHeight_ = 0;
    textureKind_ = 0;
    viewportWidth_ = 0;
    viewportHeight_ = 0;
    rgbaScratch_.clear();
}
