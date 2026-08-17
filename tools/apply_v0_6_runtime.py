from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "app/src/main/cpp"
UI = ROOT / "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


# ---- Runtime v7: preserve v6 emulation/audio/timing, replace only presentation. ----
v6 = (CPP / "libretro_host_v6.cpp").read_text()
v7 = v6
v7 = replace_once(
    v7,
    '#include "libretro_abi.h"\n',
    '#include "libretro_abi.h"\n#include "gl_presenter.h"\n',
    "GLES presenter include",
)
v7 = v7.replace("Critical runtime-v6 rule", "Critical runtime-v7 rule")

new_renderer = r'''    void setRendererError(std::string value) {
        std::lock_guard<std::mutex> lock(rendererErrorMutex_);
        rendererError_ = std::move(value);
    }

    std::string rendererError() const {
        std::lock_guard<std::mutex> lock(rendererErrorMutex_);
        return rendererError_;
    }

    void renderLoop() {
        GlPresenter presenter;
        if (!presenter.initialize(window_)) {
            rendererReady_.store(false, std::memory_order_release);
            surfaceFailures_.fetch_add(1, std::memory_order_relaxed);
            setRendererError(presenter.lastError());
            return;
        }
        rendererReady_.store(true, std::memory_order_release);
        setRendererError({});

        std::vector<std::uint8_t> local;
        std::uint64_t seenSerial = 0;
        while (!renderStop_.load(std::memory_order_acquire)) {
            unsigned width = 0;
            unsigned height = 0;
            std::size_t pitch = 0;
            retro_pixel_format format = RETRO_PIXEL_FORMAT_RGB565;
            {
                std::unique_lock<std::mutex> lock(frameMutex_);
                frameCv_.wait_for(lock, std::chrono::milliseconds(25), [&] {
                    return renderStop_.load(std::memory_order_acquire) || pendingSerial_ != seenSerial;
                });
                if (renderStop_.load(std::memory_order_acquire)) break;
                if (pendingSerial_ == seenSerial || pendingFrame_.empty()) continue;
                local = pendingFrame_;
                width = pendingWidth_;
                height = pendingHeight_;
                pitch = pendingPitch_;
                format = pendingFormat_;
                seenSerial = pendingSerial_;
            }

            if (presenter.present(local.data(), width, height, pitch, format)) {
                presentedFrames_.fetch_add(1, std::memory_order_relaxed);
            } else {
                surfaceFailures_.fetch_add(1, std::memory_order_relaxed);
                setRendererError(presenter.lastError());
            }
        }
        presenter.shutdown();
        rendererReady_.store(false, std::memory_order_release);
    }

    void pushAudioFrames'''

v7, count = re.subn(
    r"    void renderLoop\(\) \{.*?\n    void pushAudioFrames",
    new_renderer,
    v7,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f"renderer replacement: expected 1 match, got {count}")

v7 = replace_once(
    v7,
    'setStatus("RUNTIME E11 • Surface recusou frames • falhas " + std::to_string(surfaceFail));',
    'setStatus("RUNTIME E11 • EGL/GLES falhou • " + rendererError() + " • falhas " + std::to_string(surfaceFail));',
    "E11 diagnostic",
)
v7 = replace_once(
    v7,
    '"RUN OK • %.1f/%.1f fps • vídeo %llu/%llu drop %llu • áudio %u%% • u%llu/o%llu",',
    '"RUN OK • %.1f/%.1f fps • GLES %llu/%llu drop %llu • áudio %u%% • u%llu/o%llu",',
    "RUN OK backend label",
)
v7 = replace_once(
    v7,
    '    unsigned renderWidth_ = 0;\n    unsigned renderHeight_ = 0;\n    int renderFormat_ = 0;\n',
    '    mutable std::mutex rendererErrorMutex_;\n    std::string rendererError_;\n    std::atomic<bool> rendererReady_{false};\n',
    "renderer fields",
)
(CPP / "libretro_host_v7.cpp").write_text(v7)

# ---- UI: current runtime labels + in-app update controls. ----
ui = UI.read_text()
ui = replace_once(ui, "import android.content.Intent\n", "import android.content.Intent\nimport android.os.Build\n", "Build import")
ui = replace_once(ui, "import com.omnicore.emulator.core.CoreRegistry\n", "import com.omnicore.emulator.BuildConfig\nimport com.omnicore.emulator.core.CoreRegistry\n", "BuildConfig import")
ui = replace_once(ui, "import com.omnicore.emulator.storage.SafGameSource\n", "import com.omnicore.emulator.storage.SafGameSource\nimport com.omnicore.emulator.update.UpdateManager\n", "UpdateManager import")
ui = ui.replace('$count jogo(s) • Runtime v4', '$count jogo(s) • Runtime v7')
ui = ui.replace('CUE/BIN • CHD • PBP • SmartPerf 3 • framebuffer direto', 'CUE/BIN • CHD • PBP • EGL/GLES • A/V desacoplado')
ui = ui.replace('HubSection("Sistema", "OmniCore 0.3.0 • $gameCount jogo(s) na biblioteca")', 'HubSection("Sistema", "OmniCore ${BuildConfig.VERSION_NAME} • $gameCount jogo(s) na biblioteca")')

vars_anchor = '    var config by remember { mutableStateOf(Ps1Settings.resolve(context)) }\n'
vars_insert = '''    var config by remember { mutableStateOf(Ps1Settings.resolve(context)) }
    var updateStatus by remember { mutableStateOf("Canal DEV • pronto para verificar") }
    var updateRelease by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }

    fun checkUpdate() {
        updateStatus = "Verificando GitHub Releases…"
        UpdateManager.checkForUpdate(context) { result ->
            when (result) {
                is UpdateManager.CheckResult.Available -> {
                    updateRelease = result.release
                    updateStatus = "OmniCore ${result.release.version} disponível"
                }
                is UpdateManager.CheckResult.Current -> {
                    updateRelease = null
                    updateStatus = "Você já está na versão DEV mais recente (${result.version})."
                }
                is UpdateManager.CheckResult.Error -> updateStatus = result.message
            }
        }
    }

    fun installUpdate(release: UpdateManager.ReleaseInfo) {
        UpdateManager.install(context, release) { result ->
            when (result) {
                is UpdateManager.InstallResult.Progress -> updateStatus = result.message
                UpdateManager.InstallResult.NeedsUnknownSourcesPermission -> {
                    updateStatus = "Autorize o OmniCore a instalar updates e toque em Atualizar novamente."
                    context.startActivity(UpdateManager.unknownSourcesIntent(context))
                }
                UpdateManager.InstallResult.InstallerStarted ->
                    updateStatus = "Confirme a atualização na tela oficial do Android."
                is UpdateManager.InstallResult.Error -> updateStatus = result.message
            }
        }
    }
'''
ui = replace_once(ui, vars_anchor, vars_insert, "updater state")

system_anchor = '''        item {
            HubSection("Sistema", "OmniCore ${BuildConfig.VERSION_NAME} • $gameCount jogo(s) na biblioteca") {'''
update_section = '''        item {
            HubSection("Atualizações", "Canal DEV assinado de forma estável a partir da 0.6.0.") {
                Text(updateStatus, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { checkUpdate() }) { Text("Verificar atualização") }
                updateRelease?.let { release ->
                    Button(onClick = { installUpdate(release) }) {
                        Text("Atualizar para ${release.version}")
                    }
                }
                Text(
                    "Builds DEV 0.6+ podem atualizar por cima mantendo dados. O Android ainda mostra a confirmação oficial de instalação.",
                    color = Color(0xFF737C98),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        item {
            HubSection("Sistema", "OmniCore ${BuildConfig.VERSION_NAME} • $gameCount jogo(s) na biblioteca") {'''
ui = replace_once(ui, system_anchor, update_section, "update UI section")
UI.write_text(ui)

print("v0.6 runtime/UI migration prepared")
