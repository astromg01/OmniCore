from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")

def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    found = text.count(old)
    if found < count:
        raise SystemExit(f"{path}: expected at least {count} occurrence(s), found {found}: {old[:100]!r}")
    text = text.replace(old, new, count)
    write(path, text)

# Version.
replace("app/build.gradle.kts", 'versionCode = 28', 'versionCode = 29')
replace("app/build.gradle.kts", 'versionName = "0.10.12"', 'versionName = "0.10.13"')

# Game-aware input intelligence. The ROM preparer already normalizes N64 images to z64,
# but this reader still understands the three common header byte orders.
write(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64GameIntelligence.kt",
    r'''package com.omnicore.emulator.core.n64

import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Small, isolated N64 compatibility brain.
 *
 * It never changes a user's explicit Smart Analog choice. It only enriches AUTO
 * with per-game knowledge when a title is known to use digital movement.
 */
object N64GameIntelligence {
    data class InputPolicy(
        val bridgeDpadInAuto: Boolean,
        val internalTitle: String,
        val reason: String?
    )

    private val dpadFirstMarkers = listOf(
        "KIRBY"
    )

    fun inputPolicy(rom: File): InputPolicy {
        val title = readInternalTitle(rom)
        val fileIdentity = rom.nameWithoutExtension.uppercase(Locale.ROOT)
        val identity = "$title $fileIdentity"
        val matched = dpadFirstMarkers.firstOrNull { identity.contains(it) }
        return InputPolicy(
            bridgeDpadInAuto = matched != null,
            internalTitle = title,
            reason = matched?.let { "digital-profile:$it" }
        )
    }

    private fun readInternalTitle(rom: File): String {
        if (!rom.isFile || rom.length() < 0x40L) return ""
        val header = ByteArray(0x40)
        var read = 0
        runCatching {
            FileInputStream(rom).use { input ->
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
        }.getOrElse { return "" }
        if (read < header.size) return ""

        val normalized = header.copyOf()
        when (
            listOf(
                header[0].toInt() and 0xff,
                header[1].toInt() and 0xff,
                header[2].toInt() and 0xff,
                header[3].toInt() and 0xff
            )
        ) {
            listOf(0x37, 0x80, 0x40, 0x12) -> {
                for (i in normalized.indices step 2) {
                    if (i + 1 >= normalized.size) break
                    val tmp = normalized[i]
                    normalized[i] = normalized[i + 1]
                    normalized[i + 1] = tmp
                }
            }
            listOf(0x40, 0x12, 0x37, 0x80) -> {
                for (i in normalized.indices step 4) {
                    if (i + 3 >= normalized.size) break
                    val a = normalized[i]
                    val b = normalized[i + 1]
                    normalized[i] = normalized[i + 3]
                    normalized[i + 1] = normalized[i + 2]
                    normalized[i + 2] = b
                    normalized[i + 3] = a
                }
            }
        }

        return String(normalized, 0x20, 20, Charsets.US_ASCII)
            .replace('\u0000', ' ')
            .trim()
            .uppercase(Locale.ROOT)
    }
}
''')

# AUTO Smart Analog now cooperates with the game compatibility brain.
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''        val diagnosticFile = File(paths.root, "last_boot_stage.txt")
        val verificationFile = File(paths.root, "boot_verified.flag")
''',
    '''        val inputPolicy = N64GameIntelligence.inputPolicy(rom)
        val diagnosticFile = File(paths.root, "last_boot_stage.txt")
        val verificationFile = File(paths.root, "boot_verified.flag")
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''                    "detail=${config.cpuMode.storage},threaded=${config.threadedRenderer},fb=${config.framebufferEmulation},aspect=${config.aspectRatio.storage}\\n"
''',
    '''                    "detail=${config.cpuMode.storage},threaded=${config.threadedRenderer},fb=${config.framebufferEmulation},aspect=${config.aspectRatio.storage}," +
                    "smartInput=${inputPolicy.reason ?: "generic"},title=${inputPolicy.internalTitle.take(28)}\\n"
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''                smartAnalogAutoDpad = !input.showDpad,
''',
    '''                smartAnalogAutoDpad = !input.showDpad || inputPolicy.bridgeDpadInAuto,
'''
)

# Telemetry: expose that the bounded real pre-execution/compile pass completed.
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''        val shaderCacheReady: Boolean = false,
        val smartAnalogDpadActive: Boolean = false
''',
    '''        val shaderCacheReady: Boolean = false,
        val smartAnalogDpadActive: Boolean = false,
        val smartPrecompileReady: Boolean = false
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''            presentAverageMs = presentAverageMs,
            presentP95Ms = presentP95Ms
''',
    '''            presentAverageMs = presentAverageMs,
            presentP95Ms = presentP95Ms,
            smartPrecompileReady = smartPrecompileReady
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''            shaderCacheReady = raw.getOrElse(16) { 0f } >= 0.5f,
            smartAnalogDpadActive = raw.getOrElse(17) { 0f } >= 0.5f
''',
    '''            shaderCacheReady = raw.getOrElse(16) { 0f } >= 0.5f,
            smartAnalogDpadActive = raw.getOrElse(17) { 0f } >= 0.5f,
            smartPrecompileReady = raw.getOrElse(18) { 0f } >= 0.5f
'''
)

# SmartPerf consumes the new compiler signal. Once SmartPrecompile has completed,
# WarmStart can hand back headroom sooner instead of staying aggressive by timer alone.
replace(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    '''        val presentAverageMs: Float = 0f,
        val presentP95Ms: Float = 0f
''',
    '''        val presentAverageMs: Float = 0f,
        val presentP95Ms: Float = 0f,
        val smartPrecompileReady: Boolean = false
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    '''        private val warmupMinUntil = warmupStartedAt + 12_000L
''',
    '''        private var warmupMinUntil = warmupStartedAt + 12_000L
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    '''            val now = SystemClock.elapsedRealtime()
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now
''',
    '''            val now = SystemClock.elapsedRealtime()
            if (telemetry.smartPrecompileReady) {
                warmupMinUntil = minOf(warmupMinUntil, warmupStartedAt + 7_000L)
            }
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now
'''
)

# Settings copy explains the new AUTO behavior.
replace(
    "app/src/main/java/com/omnicore/emulator/ui/n64/N64SettingsDialog.kt",
    '''"Inteligente mantém o analógico N64 normal e, quando o D-pad virtual está oculto, também traduz movimentos fortes para setas. Use Analógico → D-pad para jogos digitais."''',
    '''"Inteligente mantém o analógico N64 normal, ativa setas quando o D-pad está oculto e também usa perfis de compatibilidade para jogos digitais conhecidos. Use Analógico → D-pad para forçar esse comportamento."'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/n64/N64SettingsDialog.kt",
    '''subtitle = "Pode ser ocultado em jogos que usam apenas o analógico.",''',
    '''subtitle = "Pode ser ocultado; o Smart Analog continua cobrindo jogos digitais conhecidos automaticamente.",'''
)

# Native host declarations.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    float shaderCacheReady = 0.0f;
    float smartAnalogDpadActive = 0.0f;
''',
    '''    float shaderCacheReady = 0.0f;
    float smartAnalogDpadActive = 0.0f;
    float smartPrecompileReady = 0.0f;
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    bool processPendingCommand();
    void loadSaveRam();
''',
    '''    bool processPendingCommand();
    bool runSmartPrecompile();
    void loadSaveRam();
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    std::atomic<bool> shaderCacheReady_{false};
    std::atomic<bool> smartAnalogDpadActive_{false};
''',
    '''    std::atomic<bool> shaderCacheReady_{false};
    std::atomic<bool> shaderCacheHot_{false};
    std::atomic<bool> smartAnalogDpadActive_{false};
    std::atomic<bool> smartPrecompileActive_{false};
    std::atomic<bool> smartPrecompileReady_{false};
'''
)

# Prefer the newest shader binaries in the bounded page-cache warmup. GLideN64
# creates per-game/per-program files; recency is a better signal than raw directory order.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''std::size_t warmDirectoryPages(const std::string& path, std::size_t budgetBytes) {
    if (path.empty() || budgetBytes == 0) return 0;
    DIR* dir = ::opendir(path.c_str());
    if (!dir) return 0;
    const long pageSize = std::max<long>(4096, ::sysconf(_SC_PAGESIZE));
    std::size_t warmed = 0;
    while (warmed < budgetBytes) {
        dirent* entry = ::readdir(dir);
        if (!entry) break;
        if (entry->d_name[0] == '.') continue;
        const std::string filePath = path + "/" + entry->d_name;
        const int fd = ::open(filePath.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        struct stat st {};
        if (::fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
#ifdef POSIX_FADV_WILLNEED
            ::posix_fadvise(fd, 0, 0, POSIX_FADV_WILLNEED);
#endif
            const std::size_t remaining = budgetBytes - warmed;
            const std::size_t fileBudget = std::min<std::size_t>(static_cast<std::size_t>(st.st_size), remaining);
            std::uint8_t byte = 0;
            for (std::size_t offset = 0; offset < fileBudget; offset += static_cast<std::size_t>(pageSize)) {
                if (::pread(fd, &byte, 1, static_cast<off_t>(offset)) != 1) break;
                warmed += std::min<std::size_t>(static_cast<std::size_t>(pageSize), fileBudget - offset);
            }
        }
        ::close(fd);
    }
    ::closedir(dir);
    return warmed;
}
''',
    '''std::size_t warmDirectoryPages(const std::string& path, std::size_t budgetBytes) {
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
#ifdef POSIX_FADV_WILLNEED
        ::posix_fadvise(fd, 0, static_cast<off_t>(file.size), POSIX_FADV_WILLNEED);
#endif
        const std::size_t remaining = budgetBytes - warmed;
        const std::size_t fileBudget = std::min(file.size, remaining);
        std::uint8_t byte = 0;
        for (std::size_t offset = 0; offset < fileBudget; offset += static_cast<std::size_t>(pageSize)) {
            if (::pread(fd, &byte, 1, static_cast<off_t>(offset)) != 1) break;
            warmed += std::min<std::size_t>(static_cast<std::size_t>(pageSize), fileBudget - offset);
        }
        ::close(fd);
    }
    return warmed;
}
'''
)

# Session reset and boot identity.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    smartAnalogDpadActive_.store(false, std::memory_order_release);
    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);
''',
    '''    smartAnalogDpadActive_.store(false, std::memory_order_release);
    smartPrecompileActive_.store(false, std::memory_order_release);
    smartPrecompileReady_.store(false, std::memory_order_release);
    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    shaderCacheReady_.store(false, std::memory_order_release);
    directPresenterActive_.store(false, std::memory_order_release);
''',
    '''    shaderCacheReady_.store(false, std::memory_order_release);
    shaderCacheHot_.store(false, std::memory_order_release);
    directPresenterActive_.store(false, std::memory_order_release);
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    smartAnalogDpadActive_.store(false, std::memory_order_release);
    lastPresentMs_.store(0.0f, std::memory_order_release);
''',
    '''    smartAnalogDpadActive_.store(false, std::memory_order_release);
    smartPrecompileActive_.store(false, std::memory_order_release);
    smartPrecompileReady_.store(false, std::memory_order_release);
    lastPresentMs_.store(0.0f, std::memory_order_release);
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    setMessage("N64 BOOT 1/6 • runtime Alpha 7 single-pacer…");
''',
    '''    setMessage("N64 BOOT 1/7 • Alpha 14 SmartPrecompile + Game Intelligence…");
'''
)

# Do not present or accept gameplay input during the hidden warm frames.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    if (!impl_ || impl_->display == EGL_NO_DISPLAY) return;
    if (data != abi::RETRO_HW_FRAME_BUFFER_VALID) return;
    const auto presentBegin = std::chrono::steady_clock::now();
''',
    '''    if (!impl_ || impl_->display == EGL_NO_DISPLAY) return;
    if (data != abi::RETRO_HW_FRAME_BUFFER_VALID) return;
    if (smartPrecompileActive_.load(std::memory_order_acquire)) {
        glFlush();
        return;
    }
    const auto presentBegin = std::chrono::steady_clock::now();
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''std::int16_t LibretroHost::inputState(unsigned port, unsigned device, unsigned index, unsigned id) const {
    if (port != 0) return 0;
''',
    '''std::int16_t LibretroHost::inputState(unsigned port, unsigned device, unsigned index, unsigned id) const {
    if (smartPrecompileActive_.load(std::memory_order_acquire)) return 0;
    if (port != 0) return 0;
'''
)

# Telemetry signal.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    out.shaderCacheReady = shaderCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.smartAnalogDpadActive = smartAnalogDpadActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
''',
    '''    out.shaderCacheReady = shaderCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.smartAnalogDpadActive = smartAnalogDpadActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.smartPrecompileReady = smartPrecompileReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
'''
)

# Bounded real compile warmup: execute a few hidden frames, forcing GLideN64 shader
# programs and early dynarec blocks to materialize, then restore the exact boot state.
# If serialization is unavailable or restore fails, gameplay continues normally.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''void LibretroHost::run() {
''',
    '''bool LibretroHost::runSmartPrecompile() {
    if (!impl_ || !impl_->gameLoaded || !impl_->core.run ||
        !impl_->core.serializeSize || !impl_->core.serialize || !impl_->core.unserialize) {
        return false;
    }
    const std::size_t stateSize = impl_->core.serializeSize();
    if (stateSize == 0 || stateSize > 64u * 1024u * 1024u) return false;

    std::vector<std::uint8_t> bootState(stateSize);
    if (!impl_->core.serialize(bootState.data(), bootState.size())) return false;
    // Prove that the snapshot can be restored before we mutate emulation state.
    if (!impl_->core.unserialize(bootState.data(), bootState.size())) return false;

    const int warmFrames = shaderCacheHot_.load(std::memory_order_acquire) ? 5 : 9;
    smartPrecompileActive_.store(true, std::memory_order_release);
    setMessage("N64 BOOT 5/7 • SmartPrecompile aquecendo shaders + Dynarec…");
    if (impl_->perfHint.active()) {
        impl_->perfHint.notifySpike(true, true, "omnicore-n64-smart-precompile");
        impl_->perfHint.setTargetScale(0.76);
    }

    bool ran = true;
    for (int frame = 0; frame < warmFrames; ++frame) {
        if (stopRequested_.load(std::memory_order_acquire)) {
            ran = false;
            break;
        }
        impl_->core.run();
    }
    glFinish();
    const bool restored = impl_->core.unserialize(bootState.data(), bootState.size());
    smartPrecompileActive_.store(false, std::memory_order_release);
    if (!ran || !restored) return false;

    smartPrecompileReady_.store(true, std::memory_order_release);
    return true;
}

void LibretroHost::run() {
'''
)

# Cleanup must never leave hidden mode latched.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''            warmStartActive_.store(false, std::memory_order_release);
            impl_->closeAudio();
''',
    '''            warmStartActive_.store(false, std::memory_order_release);
            smartPrecompileActive_.store(false, std::memory_order_release);
            impl_->closeAudio();
'''
)

# Larger but still bounded shader page warmup (12 MiB max), now recency ordered.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''        const std::size_t warmed = warmDirectoryPages(shaderDir, 4u * 1024u * 1024u);
        if (warmed > 0) logPrint(ANDROID_LOG_INFO, "ShaderWarmup prefetched %zu bytes", warmed);
''',
    '''        const std::size_t warmed = warmDirectoryPages(shaderDir, 12u * 1024u * 1024u);
        shaderCacheHot_.store(warmed > 0, std::memory_order_release);
        if (warmed > 0) logPrint(ANDROID_LOG_INFO, "SmartPrecompile cache-prefetched %zu bytes", warmed);
'''
)

# Open ADPF before the hidden compile pass so BurstShield/SmartPerf can lend
# transient CPU+GPU headroom to compilation instead of reacting only afterwards.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    targetFps_.store(static_cast<float>(impl_->targetFps), std::memory_order_release);
    const bool audioReady = impl_->openAudio(
        impl_->coreSampleRate,
        audioTargetBursts_.load(std::memory_order_acquire));
''',
    '''    targetFps_.store(static_cast<float>(impl_->targetFps), std::memory_order_release);
    const bool adpfReady = impl_->perfHint.open(impl_->targetFps);
    adpfActive_.store(adpfReady, std::memory_order_release);
    if (adpfReady) {
        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");
        impl_->perfHint.bindSurface(window_);
        impl_->perfHint.setTargetScale(0.80);
    }
    const bool precompileReady = runSmartPrecompile();
    if (adpfReady) impl_->perfHint.setTargetScale(precompileReady ? 0.84 : 0.82);

    const bool audioReady = impl_->openAudio(
        impl_->coreSampleRate,
        audioTargetBursts_.load(std::memory_order_acquire));
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    const bool adpfReady = impl_->perfHint.open(impl_->targetFps);
    adpfActive_.store(adpfReady, std::memory_order_release);
    if (adpfReady) {
        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");
        impl_->perfHint.bindSurface(window_);
        // Ask for transient headroom while dynarec blocks and GLideN64 shaders
        // are being seen for the first time. This does not change fidelity.
        impl_->perfHint.setTargetScale(0.84);
    }
    burstShieldActive_.store(adpfReady && impl_->perfHint.burstCapable(), std::memory_order_release);
''',
    '''    burstShieldActive_.store(adpfReady && impl_->perfHint.burstCapable(), std::memory_order_release);
'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''        ? "N64 BOOT 5/6 • GLideN64 + AAudio nativo, aguardando primeiro frame…"
        : "N64 BOOT 5/6 • GLideN64 pronto, aguardando primeiro frame…");
''',
    '''        ? (precompileReady
            ? "N64 BOOT 6/7 • SmartPrecompile ✓ • GLideN64 + AAudio, primeiro frame…"
            : "N64 BOOT 6/7 • GLideN64 + AAudio, primeiro frame…")
        : (precompileReady
            ? "N64 BOOT 6/7 • SmartPrecompile ✓ • GLideN64, primeiro frame…"
            : "N64 BOOT 6/7 • GLideN64 pronto, primeiro frame…"));
'''
)

# Native JNI telemetry/runtime identity.
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''OmniCore N64 Runtime 0.10.12 • Mupen64Plus-Next • GLES3 + AAudio host v11 • DirectPresenter + RenderShield + SmartAnalog + ShaderCache''',
    '''OmniCore N64 Runtime 0.10.13 • Mupen64Plus-Next • GLES3 + AAudio host v12 • SmartPrecompile + DirectPresenter + RenderShield + GameAware SmartAnalog + ShaderCache'''
)
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''    const jfloat values[18] = {
''',
    '''    const jfloat values[19] = {
'''
)
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''        telemetry.shaderCacheReady,
        telemetry.smartAnalogDpadActive
    };
    jfloatArray result = env->NewFloatArray(18);
    if (result) env->SetFloatArrayRegion(result, 0, 18, values);
''',
    '''        telemetry.shaderCacheReady,
        telemetry.smartAnalogDpadActive,
        telemetry.smartPrecompileReady
    };
    jfloatArray result = env->NewFloatArray(19);
    if (result) env->SetFloatArrayRegion(result, 0, 19, values);
'''
)

# Performance HUD/status.
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '''            append(if (t.shaderCacheReady) " ✓" else "")
            append(if (t.directPresenterActive) " • DirectPresenter" else " • RenderBridge")
''',
    '''            append(if (t.shaderCacheReady) " ✓" else "")
            append(if (t.smartPrecompileReady) " • SmartPrecompile ✓" else "")
            append(if (t.directPresenterActive) " • DirectPresenter" else " • RenderBridge")
'''
)

print("OmniCore 0.10.13 Alpha 14 migration applied.")
