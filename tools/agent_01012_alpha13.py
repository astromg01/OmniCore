#!/usr/bin/env python3
from pathlib import Path


def edit(path: str, replacements: list[tuple[str, str]]) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    for old, new in replacements:
        if old not in text:
            raise SystemExit(f"Alpha13 migration: marker not found in {path}: {old[:120]!r}")
        text = text.replace(old, new, 1)
    p.write_text(text, encoding="utf-8")


# Version --------------------------------------------------------------------
edit("app/build.gradle.kts", [
    ('versionCode = 27', 'versionCode = 28'),
    ('versionName = "0.10.11"', 'versionName = "0.10.12"'),
])

# Persistent GLideN64 shader directory ---------------------------------------
edit("app/src/main/java/com/omnicore/emulator/storage/N64Storage.kt", [
    (
        '        val system = File(root, "system")\n        val cache = File(context.cacheDir, "n64")\n        listOf(root, saves, states, system, cache).forEach { directory ->',
        '        val system = File(root, "system")\n        val shaderCache = File(system, "Mupen64plus/shaders")\n        val cache = File(context.cacheDir, "n64")\n        // GLideN64 resolves its user cache from RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY\n        // and appends Mupen64plus/shaders. Keep it under filesDir, never cacheDir,\n        // so compiled combiner programs survive app/process restarts.\n        listOf(root, saves, states, system, shaderCache, cache).forEach { directory ->'
    ),
])

# Smart Analog policy ---------------------------------------------------------
edit("app/src/main/java/com/omnicore/emulator/settings/N64InputSettings.kt", [
    (
        '    enum class CButtonMode(val storage: String, val label: String) {\n        BUTTONS("buttons", "4 botões C"),\n        RIGHT_STICK("right_stick", "Analógico direito")\n    }\n\n',
        '    enum class CButtonMode(val storage: String, val label: String) {\n        BUTTONS("buttons", "4 botões C"),\n        RIGHT_STICK("right_stick", "Analógico direito")\n    }\n\n    enum class SmartAnalogMode(val storage: String, val label: String) {\n        AUTO("auto", "Inteligente"),\n        ANALOG_ONLY("analog_only", "Somente analógico"),\n        DPAD_ONLY("dpad_only", "Analógico → D-pad")\n    }\n\n'
    ),
    (
        '        val analogDeadzone: Float,\n        val analogSensitivity: Float,\n        val cButtonMode: CButtonMode,',
        '        val analogDeadzone: Float,\n        val analogSensitivity: Float,\n        val smartAnalogMode: SmartAnalogMode,\n        val precisionAnalog: Boolean,\n        val cButtonMode: CButtonMode,'
    ),
    (
        '    private const val KEY_SENSITIVITY = "analog_sensitivity"\n    private const val KEY_C_MODE = "c_button_mode"',
        '    private const val KEY_SENSITIVITY = "analog_sensitivity"\n    private const val KEY_SMART_ANALOG = "smart_analog_mode"\n    private const val KEY_PRECISION_ANALOG = "precision_analog"\n    private const val KEY_C_MODE = "c_button_mode"'
    ),
    (
        '            analogSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.05f).coerceIn(0.70f, 1.30f),\n            cButtonMode = CButtonMode.entries.firstOrNull {',
        '            analogSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.05f).coerceIn(0.70f, 1.30f),\n            smartAnalogMode = SmartAnalogMode.entries.firstOrNull {\n                it.storage == prefs.getString(KEY_SMART_ANALOG, SmartAnalogMode.AUTO.storage)\n            } ?: SmartAnalogMode.AUTO,\n            precisionAnalog = prefs.getBoolean(KEY_PRECISION_ANALOG, true),\n            cButtonMode = CButtonMode.entries.firstOrNull {'
    ),
    (
        '            .putFloat(KEY_SENSITIVITY, config.analogSensitivity.coerceIn(0.70f, 1.30f))\n            .putString(KEY_C_MODE, config.cButtonMode.storage)',
        '            .putFloat(KEY_SENSITIVITY, config.analogSensitivity.coerceIn(0.70f, 1.30f))\n            .putString(KEY_SMART_ANALOG, config.smartAnalogMode.storage)\n            .putBoolean(KEY_PRECISION_ANALOG, config.precisionAnalog)\n            .putString(KEY_C_MODE, config.cButtonMode.storage)'
    ),
])

# N64 settings UI -------------------------------------------------------------
edit("app/src/main/java/com/omnicore/emulator/ui/n64/N64SettingsDialog.kt", [
    (
        '                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        items(N64InputSettings.CButtonMode.entries) { mode ->\n                            FilterChip(\n                                selected = input.cButtonMode == mode,\n                                onClick = { saveInput(input.copy(cButtonMode = mode)) },\n                                label = { Text(mode.label) }\n                            )\n                        }\n                    }\n                }\n                item {\n                    Text("Overlay touch", fontWeight = FontWeight.Bold)',
        '                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        items(N64InputSettings.CButtonMode.entries) { mode ->\n                            FilterChip(\n                                selected = input.cButtonMode == mode,\n                                onClick = { saveInput(input.copy(cButtonMode = mode)) },\n                                label = { Text(mode.label) }\n                            )\n                        }\n                    }\n                    Text("Smart Analog", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))\n                    Text(\n                        "Inteligente mantém o analógico N64 normal e, quando o D-pad virtual está oculto, também traduz movimentos fortes para setas. Use Analógico → D-pad para jogos digitais.",\n                        style = MaterialTheme.typography.bodySmall\n                    )\n                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        items(N64InputSettings.SmartAnalogMode.entries) { mode ->\n                            FilterChip(\n                                selected = input.smartAnalogMode == mode,\n                                onClick = { saveInput(input.copy(smartAnalogMode = mode)) },\n                                label = { Text(mode.label) }\n                            )\n                        }\n                    }\n                }\n                item {\n                    Text("Overlay touch", fontWeight = FontWeight.Bold)'
    ),
    (
        '                item {\n                    Text("Analógico", fontWeight = FontWeight.Bold)\n                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {',
        '                item {\n                    Text("Analógico", fontWeight = FontWeight.Bold)\n                    N64Toggle(\n                        title = "Precisão radial",\n                        subtitle = "Aplica deadzone uma única vez no host, preserva direção e amplia controle fino perto do centro sem reduzir alcance máximo.",\n                        checked = input.precisionAnalog\n                    ) { saveInput(input.copy(precisionAnalog = it)) }\n                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {'
    ),
])

# Kotlin JNI boundary + telemetry --------------------------------------------
edit("app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt", [
    (
        '        val warmStartActive: Boolean = false,\n        val shaderCacheEnabled: Boolean = false\n',
        '        val warmStartActive: Boolean = false,\n        val shaderCacheEnabled: Boolean = false,\n        val directPresenterActive: Boolean = false,\n        val shaderCacheReady: Boolean = false,\n        val smartAnalogDpadActive: Boolean = false\n'
    ),
    (
        '                analogDeadzonePercent = (input.analogDeadzone * 100f).roundToInt(),\n                analogSensitivityPercent = (input.analogSensitivity * 100f).roundToInt(),\n                audioBufferBursts = decision.audioBufferBursts.coerceIn(2, 8)',
        '                analogDeadzonePercent = (input.analogDeadzone * 100f).roundToInt(),\n                analogSensitivityPercent = (input.analogSensitivity * 100f).roundToInt(),\n                smartAnalogMode = input.smartAnalogMode.storage,\n                smartAnalogAutoDpad = !input.showDpad,\n                precisionAnalog = input.precisionAnalog,\n                audioBufferBursts = decision.audioBufferBursts.coerceIn(2, 8)'
    ),
    (
        '            shaderCacheEnabled = raw.getOrElse(14) { 0f } >= 0.5f\n',
        '            shaderCacheEnabled = raw.getOrElse(14) { 0f } >= 0.5f,\n            directPresenterActive = raw.getOrElse(15) { 0f } >= 0.5f,\n            shaderCacheReady = raw.getOrElse(16) { 0f } >= 0.5f,\n            smartAnalogDpadActive = raw.getOrElse(17) { 0f } >= 0.5f\n'
    ),
    (
        '        analogDeadzonePercent: Int,\n        analogSensitivityPercent: Int,\n        audioBufferBursts: Int\n',
        '        analogDeadzonePercent: Int,\n        analogSensitivityPercent: Int,\n        smartAnalogMode: String,\n        smartAnalogAutoDpad: Boolean,\n        precisionAnalog: Boolean,\n        audioBufferBursts: Int\n'
    ),
])

# Native ABI structs ----------------------------------------------------------
edit("app/src/main/cpp/n64/n64_libretro_host.h", [
    (
        '    int analogDeadzonePercent = 12;\n    int analogSensitivityPercent = 100;\n    int audioBufferBursts = 4;',
        '    int analogDeadzonePercent = 12;\n    int analogSensitivityPercent = 100;\n    std::string smartAnalogMode = "auto";\n    bool smartAnalogAutoDpad = false;\n    bool precisionAnalog = true;\n    int audioBufferBursts = 4;'
    ),
    (
        '    float warmStartActive = 0.0f;\n    float shaderCacheEnabled = 0.0f;\n};',
        '    float warmStartActive = 0.0f;\n    float shaderCacheEnabled = 0.0f;\n    float directPresenterActive = 0.0f;\n    float shaderCacheReady = 0.0f;\n    float smartAnalogDpadActive = 0.0f;\n};'
    ),
    (
        '    std::atomic<std::uint16_t> buttonMask_{0};\n    std::atomic<std::int16_t> analogX_{0};',
        '    std::atomic<std::uint16_t> buttonMask_{0};\n    std::atomic<std::uint16_t> smartDpadMask_{0};\n    std::atomic<std::int16_t> analogX_{0};'
    ),
    (
        '    std::atomic<bool> warmStartActive_{false};\n    std::atomic<bool> shaderCacheEnabled_{false};',
        '    std::atomic<bool> warmStartActive_{false};\n    std::atomic<bool> shaderCacheEnabled_{false};\n    std::atomic<bool> directPresenterActive_{false};\n    std::atomic<bool> shaderCacheReady_{false};\n    std::atomic<bool> smartAnalogDpadActive_{false};\n    std::atomic<float> lastPresentMs_{0.0f};'
    ),
])

# Native JNI ------------------------------------------------------------------
edit("app/src/main/cpp/n64/n64_native_bridge.cpp", [
    (
        '    const std::string value = std::string("OmniCore N64 Runtime 0.10.11 • Mupen64Plus-Next • GLES3 + AAudio host v10 • BurstShield + WarmStart + ShaderCache • ") +',
        '    const std::string value = std::string("OmniCore N64 Runtime 0.10.12 • Mupen64Plus-Next • GLES3 + AAudio host v11 • DirectPresenter + RenderShield + SmartAnalog + ShaderCache • ") +'
    ),
    (
        '    jint analogDeadzonePercent,\n    jint analogSensitivityPercent,\n    jint audioBufferBursts) {',
        '    jint analogDeadzonePercent,\n    jint analogSensitivityPercent,\n    jstring smartAnalogMode,\n    jboolean smartAnalogAutoDpad,\n    jboolean precisionAnalog,\n    jint audioBufferBursts) {'
    ),
    (
        '    config.analogDeadzonePercent = analogDeadzonePercent;\n    config.analogSensitivityPercent = analogSensitivityPercent;\n    config.audioBufferBursts = audioBufferBursts;',
        '    config.analogDeadzonePercent = analogDeadzonePercent;\n    config.analogSensitivityPercent = analogSensitivityPercent;\n    config.smartAnalogMode = fromJString(env, smartAnalogMode);\n    config.smartAnalogAutoDpad = smartAnalogAutoDpad == JNI_TRUE;\n    config.precisionAnalog = precisionAnalog == JNI_TRUE;\n    config.audioBufferBursts = audioBufferBursts;'
    ),
    (
        '    const jfloat values[15] = {\n',
        '    const jfloat values[18] = {\n'
    ),
    (
        '        telemetry.warmStartActive,\n        telemetry.shaderCacheEnabled\n    };\n    jfloatArray result = env->NewFloatArray(15);\n    if (result) env->SetFloatArrayRegion(result, 0, 15, values);',
        '        telemetry.warmStartActive,\n        telemetry.shaderCacheEnabled,\n        telemetry.directPresenterActive,\n        telemetry.shaderCacheReady,\n        telemetry.smartAnalogDpadActive\n    };\n    jfloatArray result = env->NewFloatArray(18);\n    if (result) env->SetFloatArrayRegion(result, 0, 18, values);'
    ),
])

# Native runtime --------------------------------------------------------------
edit("app/src/main/cpp/n64/n64_libretro_host.cpp", [
    (
        '#include <dlfcn.h>\n#include <fcntl.h>',
        '#include <dlfcn.h>\n#include <dirent.h>\n#include <fcntl.h>'
    ),
    (
        'std::string boolOption(bool value) { return value ? "True" : "False"; }\n\nstd::int16_t axisFromFloat(float value) {',
        '''std::string boolOption(bool value) { return value ? "True" : "False"; }\n\nstruct AnalogVector final {\n    float x = 0.0f;\n    float y = 0.0f;\n};\n\nAnalogVector shapeAnalog(float x, float y, int deadzonePercent, int sensitivityPercent, bool precision) {\n    x = std::clamp(x, -1.0f, 1.0f);\n    y = std::clamp(y, -1.0f, 1.0f);\n    if (!precision) return {x, y};\n    const float magnitude = std::hypot(x, y);\n    if (magnitude <= 0.00001f) return {};\n    const float deadzone = std::clamp(static_cast<float>(deadzonePercent) / 100.0f, 0.0f, 0.30f);\n    if (magnitude <= deadzone) return {};\n    const float sourceMagnitude = std::min(1.0f, magnitude);\n    float normalized = (sourceMagnitude - deadzone) / std::max(0.01f, 1.0f - deadzone);\n    normalized = std::pow(std::clamp(normalized, 0.0f, 1.0f), 1.12f);\n    normalized *= std::clamp(static_cast<float>(sensitivityPercent) / 100.0f, 0.70f, 1.30f);\n    normalized = std::clamp(normalized, 0.0f, 1.0f);\n    return {x / magnitude * normalized, y / magnitude * normalized};\n}\n\nbool ensureDirectoryTree(const std::string& path) {\n    if (path.empty()) return false;\n    for (std::size_t i = 1; i <= path.size(); ++i) {\n        if (i != path.size() && path[i] != '/') continue;\n        const std::string part = path.substr(0, i);\n        if (part.empty()) continue;\n        if (::mkdir(part.c_str(), 0700) != 0 && errno != EEXIST) return false;\n    }\n    struct stat st {};\n    return ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode) && ::access(path.c_str(), W_OK) == 0;\n}\n\nstd::size_t warmDirectoryPages(const std::string& path, std::size_t budgetBytes) {\n    if (path.empty() || budgetBytes == 0) return 0;\n    DIR* dir = ::opendir(path.c_str());\n    if (!dir) return 0;\n    const long pageSize = std::max<long>(4096, ::sysconf(_SC_PAGESIZE));\n    std::size_t warmed = 0;\n    while (warmed < budgetBytes) {\n        dirent* entry = ::readdir(dir);\n        if (!entry) break;\n        if (entry->d_name[0] == '.') continue;\n        const std::string filePath = path + "/" + entry->d_name;\n        const int fd = ::open(filePath.c_str(), O_RDONLY | O_CLOEXEC);\n        if (fd < 0) continue;\n        struct stat st {};\n        if (::fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {\n#ifdef POSIX_FADV_WILLNEED\n            ::posix_fadvise(fd, 0, 0, POSIX_FADV_WILLNEED);\n#endif\n            const std::size_t remaining = budgetBytes - warmed;\n            const std::size_t fileBudget = std::min<std::size_t>(static_cast<std::size_t>(st.st_size), remaining);\n            std::uint8_t byte = 0;\n            for (std::size_t offset = 0; offset < fileBudget; offset += static_cast<std::size_t>(pageSize)) {\n                if (::pread(fd, &byte, 1, static_cast<off_t>(offset)) != 1) break;\n                warmed += std::min<std::size_t>(static_cast<std::size_t>(pageSize), fileBudget - offset);\n            }\n        }\n        ::close(fd);\n    }\n    ::closedir(dir);\n    return warmed;\n}\n\nstd::int16_t axisFromFloat(float value) {'''
    ),
    (
        '    GLuint depthBuffer = 0;\n    std::uint64_t presentedFrames = 0;',
        '    GLuint depthBuffer = 0;\n    bool directPresent = false;\n    std::uint64_t presentedFrames = 0;'
    ),
    (
        '    bool createEgl(ANativeWindow* window) {\n        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);',
        '    bool createEgl(ANativeWindow* window, int preferredWidth, int preferredHeight) {\n        renderWidth = std::max(320, preferredWidth);\n        renderHeight = std::max(240, preferredHeight);\n        const int geometryStatus = ANativeWindow_setBuffersGeometry(window, renderWidth, renderHeight, 0);\n        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);'
    ),
    (
        '        eglQuerySurface(display, surface, EGL_WIDTH, &surfaceWidth);\n        eglQuerySurface(display, surface, EGL_HEIGHT, &surfaceHeight);\n        return surfaceWidth > 0 && surfaceHeight > 0;\n    }',
        '        eglQuerySurface(display, surface, EGL_WIDTH, &surfaceWidth);\n        eglQuerySurface(display, surface, EGL_HEIGHT, &surfaceHeight);\n        directPresent = geometryStatus == 0 && surfaceWidth == renderWidth && surfaceHeight == renderHeight;\n        if (directPresent) {\n            glBindFramebuffer(GL_FRAMEBUFFER, 0);\n            glViewport(0, 0, renderWidth, renderHeight);\n        }\n        return surfaceWidth > 0 && surfaceHeight > 0;\n    }'
    ),
    (
        'void LibretroHost::setAnalog(float x, float y, float cX, float cY) {\n    analogX_.store(axisFromFloat(x), std::memory_order_release);\n    analogY_.store(axisFromFloat(y), std::memory_order_release);\n    cX_.store(axisFromFloat(cX), std::memory_order_release);\n    cY_.store(axisFromFloat(cY), std::memory_order_release);\n}',
        '''void LibretroHost::setAnalog(float x, float y, float cX, float cY) {\n    const AnalogVector shaped = shapeAnalog(\n        x, y, config_.analogDeadzonePercent, config_.analogSensitivityPercent, config_.precisionAnalog);\n    analogX_.store(axisFromFloat(shaped.x), std::memory_order_release);\n    analogY_.store(axisFromFloat(shaped.y), std::memory_order_release);\n    cX_.store(axisFromFloat(cX), std::memory_order_release);\n    cY_.store(axisFromFloat(cY), std::memory_order_release);\n\n    const bool dpadOnly = config_.smartAnalogMode == "dpad_only";\n    const bool allowSmartDpad = dpadOnly ||\n        (config_.smartAnalogMode == "auto" && config_.smartAnalogAutoDpad);\n    std::uint16_t nextMask = 0;\n    if (allowSmartDpad) {\n        const float magnitude = std::hypot(shaped.x, shaped.y);\n        const bool alreadyActive = smartDpadMask_.load(std::memory_order_acquire) != 0;\n        const float engageMagnitude = alreadyActive ? 0.42f : 0.58f;\n        if (magnitude >= engageMagnitude) {\n            constexpr float kAxisThreshold = 0.34f;\n            if (shaped.y <= -kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_UP);\n            if (shaped.y >= kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_DOWN);\n            if (shaped.x <= -kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_LEFT);\n            if (shaped.x >= kAxisThreshold) nextMask |= static_cast<std::uint16_t>(1u << RETRO_DEVICE_ID_JOYPAD_RIGHT);\n        }\n    }\n    smartDpadMask_.store(nextMask, std::memory_order_release);\n    smartAnalogDpadActive_.store(nextMask != 0, std::memory_order_release);\n}'''
    ),
    (
        '    // GLideN64 stores compiled combiner programs per ROM/GPU in the writable\n    // N64 system directory. First execution may still compile new programs;\n    // later launches can restore them instead of paying that cost mid-scene.\n    options_["mupen64plus-EnableShadersStorage"] = "True";\n    options_["mupen64plus-EnableTextureCache"] = "False";\n    shaderCacheEnabled_.store(true, std::memory_order_release);',
        '    // Pinned GLideN64 resolves its cache as <systemDir>/Mupen64plus/shaders.\n    // Enable persistent shader binaries only after the exact directory is writable;\n    // otherwise fall back cleanly instead of pretending cache is active.\n    const std::string shaderDir = config_.systemDir + "/Mupen64plus/shaders";\n    const bool shaderReady = ensureDirectoryTree(shaderDir);\n    options_["mupen64plus-EnableShadersStorage"] = boolOption(shaderReady);\n    options_["mupen64plus-EnableTextureCache"] = "False";\n    shaderCacheEnabled_.store(shaderReady, std::memory_order_release);\n    shaderCacheReady_.store(shaderReady, std::memory_order_release);'
    ),
    (
        '    options_["mupen64plus-astick-deadzone"] = std::to_string(std::clamp(config_.analogDeadzonePercent, 4, 30));\n    options_["mupen64plus-astick-sensitivity"] = std::to_string(std::clamp(config_.analogSensitivityPercent, 70, 130));',
        '    // Precision mode owns the calibration in one radial stage. Avoid applying\n    // the same deadzone/sensitivity twice inside the core afterwards.\n    options_["mupen64plus-astick-deadzone"] = config_.precisionAnalog\n        ? "0" : std::to_string(std::clamp(config_.analogDeadzonePercent, 4, 30));\n    options_["mupen64plus-astick-sensitivity"] = config_.precisionAnalog\n        ? "100" : std::to_string(std::clamp(config_.analogSensitivityPercent, 70, 130));'
    ),
    (
        '        const auto mask = buttonMask_.load(std::memory_order_acquire);\n        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<std::int16_t>(mask);\n        if (id <= RETRO_DEVICE_ID_JOYPAD_R3) return (mask & (1u << id)) ? 1 : 0;',
        '        const auto mask = static_cast<std::uint16_t>(\n            buttonMask_.load(std::memory_order_acquire) | smartDpadMask_.load(std::memory_order_acquire));\n        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<std::int16_t>(mask);\n        if (id <= RETRO_DEVICE_ID_JOYPAD_R3) return (mask & (1u << id)) ? 1 : 0;'
    ),
    (
        '        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {\n            if (id == RETRO_DEVICE_ID_ANALOG_X) return analogX_.load(std::memory_order_acquire);\n            if (id == RETRO_DEVICE_ID_ANALOG_Y) return analogY_.load(std::memory_order_acquire);\n        }',
        '        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {\n            if (config_.smartAnalogMode == "dpad_only") return 0;\n            if (id == RETRO_DEVICE_ID_ANALOG_X) return analogX_.load(std::memory_order_acquire);\n            if (id == RETRO_DEVICE_ID_ANALOG_Y) return analogY_.load(std::memory_order_acquire);\n        }'
    ),
    (
        'void LibretroHost::recordPresent(float presentMs) {\n    std::lock_guard<std::mutex> lock(telemetryMutex_);',
        'void LibretroHost::recordPresent(float presentMs) {\n    lastPresentMs_.store(presentMs, std::memory_order_release);\n    std::lock_guard<std::mutex> lock(telemetryMutex_);'
    ),
    (
        '    out.warmStartActive = warmStartActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.shaderCacheEnabled = shaderCacheEnabled_.load(std::memory_order_acquire) ? 1.0f : 0.0f;',
        '    out.warmStartActive = warmStartActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.shaderCacheEnabled = shaderCacheEnabled_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.directPresenterActive = directPresenterActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.shaderCacheReady = shaderCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.smartAnalogDpadActive = smartAnalogDpadActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;'
    ),
    (
        '    buttonMask_.store(0, std::memory_order_release);\n    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);',
        '    buttonMask_.store(0, std::memory_order_release);\n    smartDpadMask_.store(0, std::memory_order_release);\n    smartAnalogDpadActive_.store(false, std::memory_order_release);\n    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);'
    ),
    (
        '    warmStartActive_.store(false, std::memory_order_release);\n    shaderCacheEnabled_.store(false, std::memory_order_release);\n    hwRenderRequested_ = false;',
        '    warmStartActive_.store(false, std::memory_order_release);\n    shaderCacheEnabled_.store(false, std::memory_order_release);\n    shaderCacheReady_.store(false, std::memory_order_release);\n    directPresenterActive_.store(false, std::memory_order_release);\n    smartAnalogDpadActive_.store(false, std::memory_order_release);\n    lastPresentMs_.store(0.0f, std::memory_order_release);\n    hwRenderRequested_ = false;'
    ),
    (
        '    if (!impl_->createEgl(window_)) {\n        setMessage("N64 BOOT E01 • não consegui criar EGL/GLES3");\n        cleanup();\n        return;\n    }\n    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";\n    const int renderWidth = config_.internalResolution >= 20 ? 1280 :\n        (config_.internalResolution >= 15 ? 960 : 640);\n    const int renderHeight = wide\n        ? (config_.internalResolution >= 20 ? 720 : (config_.internalResolution >= 15 ? 540 : 360))\n        : (config_.internalResolution >= 20 ? 960 : (config_.internalResolution >= 15 ? 720 : 480));\n    if (!impl_->createFrontendFramebuffer(renderWidth, renderHeight)) {\n        setMessage("N64 BOOT E02 • framebuffer GLES3 inválido");\n        cleanup();\n        return;\n    }\n    setMessage("N64 BOOT 2/6 • GLES3 single-pacer, carregando Mupen64Plus-Next…");',
        '    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";\n    const int renderWidth = config_.internalResolution >= 20 ? 1280 :\n        (config_.internalResolution >= 15 ? 960 : 640);\n    const int renderHeight = wide\n        ? (config_.internalResolution >= 20 ? 720 : (config_.internalResolution >= 15 ? 540 : 360))\n        : (config_.internalResolution >= 20 ? 960 : (config_.internalResolution >= 15 ? 720 : 480));\n    if (!impl_->createEgl(window_, renderWidth, renderHeight)) {\n        setMessage("N64 BOOT E01 • não consegui criar EGL/GLES3");\n        cleanup();\n        return;\n    }\n    directPresenterActive_.store(impl_->directPresent, std::memory_order_release);\n    if (!impl_->directPresent && !impl_->createFrontendFramebuffer(renderWidth, renderHeight)) {\n        setMessage("N64 BOOT E02 • framebuffer GLES3 fallback inválido");\n        cleanup();\n        return;\n    }\n    setMessage(impl_->directPresent\n        ? "N64 BOOT 2/6 • DirectPresenter GLES3, carregando Mupen64Plus-Next…"\n        : "N64 BOOT 2/6 • RenderBridge fallback GLES3, carregando Mupen64Plus-Next…");'
    ),
    (
        '    hwRender_.context_reset();\n    callContextDestroy = true;\n    loadSaveRam();',
        '    if (shaderCacheReady_.load(std::memory_order_acquire)) {\n        const std::string shaderDir = config_.systemDir + "/Mupen64plus/shaders";\n        const std::size_t warmed = warmDirectoryPages(shaderDir, 4u * 1024u * 1024u);\n        if (warmed > 0) logPrint(ANDROID_LOG_INFO, "ShaderWarmup prefetched %zu bytes", warmed);\n    }\n    hwRender_.context_reset();\n    callContextDestroy = true;\n    loadSaveRam();'
    ),
    (
        '        const bool slowFrame = frameMs > targetMs * 1.18f;\n        const bool verySlowFrame = frameMs > targetMs * 1.55f;\n        if (slowFrame) {',
        '        const bool slowFrame = frameMs > targetMs * 1.18f;\n        const bool verySlowFrame = frameMs > targetMs * 1.55f;\n        const float presentMs = lastPresentMs_.load(std::memory_order_acquire);\n        const bool renderSpike = slowFrame && presentMs >= std::max(4.0f, targetMs * 0.28f);\n        if (slowFrame) {'
    ),
    (
        '        if (verySlowFrame) {\n            impl_->perfHint.notifySpike(true, true, "omnicore-n64-frame-spike");',
        '        if (renderSpike) {\n            // Presentation cost is a meaningful portion of this slow frame. Give\n            // ADPF a GPU-specific transient hint and protect audio, without lowering\n            // internal resolution or disabling framebuffer emulation.\n            impl_->perfHint.notifySpike(false, true, "omnicore-n64-render-spike");\n            impl_->perfHint.setTargetScale(0.76);\n            burstHeadroomUntil = std::max(\n                burstHeadroomUntil, controlNow + std::chrono::milliseconds(1900));\n            const float fillMs = audioFillMs_.load(std::memory_order_acquire);\n            const float bufferMs = audioBufferMs_.load(std::memory_order_acquire);\n            if (fillMs < std::max(30.0f, bufferMs * 1.25f)) impl_->adaptAudio(8);\n        }\n        if (verySlowFrame) {\n            impl_->perfHint.notifySpike(true, true, "omnicore-n64-frame-spike");'
    ),
    (
        'void LibretroHost::videoRefresh(const void* data, unsigned, unsigned, std::size_t) {\n    if (!impl_ || impl_->display == EGL_NO_DISPLAY || impl_->frontFbo == 0) return;\n    if (data != abi::RETRO_HW_FRAME_BUFFER_VALID) return;\n    glBindFramebuffer(GL_READ_FRAMEBUFFER, impl_->frontFbo);\n    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);\n    const auto presentBegin = std::chrono::steady_clock::now();\n    // Native N64 output was being blurred twice by linear upscaling. Keep\n    // 2x output smooth, but preserve low-resolution text/UI pixels at 1x.\n    const GLenum presentFilter = config_.internalResolution >= 15 ? GL_LINEAR : GL_NEAREST;\n    glBlitFramebuffer(0, 0, impl_->renderWidth, impl_->renderHeight,\n                      0, 0, impl_->surfaceWidth, impl_->surfaceHeight,\n                      GL_COLOR_BUFFER_BIT, presentFilter);',
        'void LibretroHost::videoRefresh(const void* data, unsigned, unsigned, std::size_t) {\n    if (!impl_ || impl_->display == EGL_NO_DISPLAY) return;\n    if (data != abi::RETRO_HW_FRAME_BUFFER_VALID) return;\n    const auto presentBegin = std::chrono::steady_clock::now();\n    if (!impl_->directPresent) {\n        if (impl_->frontFbo == 0) return;\n        glBindFramebuffer(GL_READ_FRAMEBUFFER, impl_->frontFbo);\n        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);\n        // Fallback preserves the proven frontend path on devices that reject\n        // internal-size native buffers. DirectPresenter skips this full-frame copy.\n        const GLenum presentFilter = config_.internalResolution >= 15 ? GL_LINEAR : GL_NEAREST;\n        glBlitFramebuffer(0, 0, impl_->renderWidth, impl_->renderHeight,\n                          0, 0, impl_->surfaceWidth, impl_->surfaceHeight,\n                          GL_COLOR_BUFFER_BIT, presentFilter);\n    }'
    ),
    (
        '    glBindFramebuffer(GL_FRAMEBUFFER, impl_->frontFbo);\n    glViewport(0, 0, impl_->renderWidth, impl_->renderHeight);\n    ++impl_->presentedFrames;',
        '    glBindFramebuffer(GL_FRAMEBUFFER, impl_->directPresent ? 0 : impl_->frontFbo);\n    glViewport(0, 0, impl_->renderWidth, impl_->renderHeight);\n    ++impl_->presentedFrames;'
    ),
    (
        '            ? "N64 RUN OK • single-pacer GLES3 • AAudio nativo pronto"\n            : "N64 RUN OK • single-pacer GLES3 • áudio indisponível");',
        '            ? (impl_->directPresent\n                ? "N64 RUN OK • DirectPresenter GLES3 • AAudio nativo pronto"\n                : "N64 RUN OK • RenderBridge fallback GLES3 • AAudio nativo pronto")\n            : (impl_->directPresent\n                ? "N64 RUN OK • DirectPresenter GLES3 • áudio indisponível"\n                : "N64 RUN OK • RenderBridge fallback GLES3 • áudio indisponível"));'
    ),
    (
        '    return host.impl_ ? static_cast<std::uintptr_t>(host.impl_->frontFbo) : 0u;',
        '    if (!host.impl_) return 0u;\n    return host.impl_->directPresent ? 0u : static_cast<std::uintptr_t>(host.impl_->frontFbo);'
    ),
])

# Runtime status text ---------------------------------------------------------
edit("app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt", [
    (
        '            append(if (t.shaderCacheEnabled) " • ShaderCache" else "")\n            append("\\nÁudio ")',
        '            append(if (t.shaderCacheEnabled) " • ShaderCache" else "")\n            append(if (t.shaderCacheReady) " ✓" else "")\n            append(if (t.directPresenterActive) " • DirectPresenter" else " • RenderBridge")\n            append(if (t.smartAnalogDpadActive) " • SmartAnalog→D" else "")\n            append("\\nÁudio ")'
    ),
])

print("OmniCore 0.10.12 N64 Alpha 13 migration applied")
