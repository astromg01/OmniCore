package com.omnicore.emulator.core.ps2

import android.content.Context
import android.view.Surface
import com.virtualapplications.play.NativeInterop

/**
 * OmniCore adapter for the pinned Play! Android backend.
 *
 * This class is instantiated only inside the isolated :ps2 process. Play!'s
 * Android UI never enters OmniCore; the third-party JNI surface stays behind
 * PS2Backend so the hub, PS1 and N64 runtimes remain independent.
 */
class PlayPS2Backend(context: Context) : PS2Backend {
    private val appContext = context.applicationContext

    @Volatile
    private var attachedSurface: Surface? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var running = false

    override val id: String = "play-pinned-04bde0df"

    override fun probe(): PS2Backend.Capabilities {
        val nativeProbe = runCatching { PS2NativeBridge.probe() }.getOrNull()
        val jniLoadable = runCatching {
            // Touching the class loads libPlay.so through Android's Runtime,
            // which also executes Play!'s JNI_OnLoad and validates our ABI shim.
            NativeInterop.isVirtualMachineCreated()
            true
        }.getOrDefault(false)

        val available = nativeProbe?.playBackend == true &&
            nativeProbe.playBootApi && jniLoadable

        return PS2Backend.Capabilities(
            available = available,
            arm64Jit = available && nativeProbe?.architecture == "arm64-v8a" && nativeProbe?.pointerBits == 64,
            // Boot Bridge 1 deliberately uses Play!'s default OpenGL path.
            // Vulkan is exposed only after the preference/config bridge is proven.
            vulkan = false,
            gles3 = available && nativeProbe?.gles3Build == true,
            hleBios = available,
            externalBios = false,
            saveStates = available,
            backendVersion = nativeProbe?.playRevision?.take(12).orEmpty().ifBlank { "unavailable" },
            notes = when {
                available -> "Play! JNI lifecycle and boot symbols are ready; Boot Bridge 1 uses GLES/OpenGL."
                nativeProbe?.playBackend != true -> "libPlay.so is not packaged or could not be loaded."
                nativeProbe?.playBootApi != true -> "Required Play! boot symbols are missing."
                else -> "Play! JNI_OnLoad/ABI shim validation failed."
            }
        )
    }

    @Synchronized
    override fun attachSurface(surface: Surface?) {
        attachedSurface = surface
        if (surface != null && surface.isValid && initialized) {
            NativeInterop.setupGsHandler(surface)
        }
    }

    @Synchronized
    override fun boot(request: PS2Backend.BootRequest): PS2Backend.BootResult {
        if (request.imagePath.isBlank()) {
            return PS2Backend.BootResult.Rejected("PS2 image URI/path is empty.")
        }
        if (request.externalBiosPath != null) {
            return PS2Backend.BootResult.Rejected(
                "External BIOS selection is not wired in Boot Bridge 1; use the backend HLE path for this gate."
            )
        }

        val capabilities = probe()
        if (!capabilities.available) {
            return PS2Backend.BootResult.Rejected(capabilities.notes)
        }

        val surface = attachedSurface
        if (surface == null || !surface.isValid) {
            return PS2Backend.BootResult.Rejected("PS2 render surface is not ready.")
        }

        return runCatching {
            initializeRuntime()
            NativeInterop.setupGsHandler(surface)
            NativeInterop.bootDiskImage(request.imagePath)
            NativeInterop.resumeVirtualMachine()
            running = true
            PS2Backend.BootResult.Started(id, PS2Backend.Renderer.GLES3)
        }.getOrElse { error ->
            running = false
            PS2Backend.BootResult.Failed(
                reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim(),
                recoverable = true
            )
        }
    }

    @Synchronized
    override fun pause() {
        if (!initialized || !running) return
        runCatching { NativeInterop.pauseVirtualMachine() }
    }

    @Synchronized
    override fun resume() {
        if (!initialized || !running) return
        runCatching { NativeInterop.resumeVirtualMachine() }
    }

    @Synchronized
    override fun stop() {
        if (initialized && running) {
            runCatching { NativeInterop.pauseVirtualMachine() }
        }
        running = false
        attachedSurface = null
        // Pinned Play! does not expose a VM destroy JNI entry point. The VM is
        // intentionally process-scoped; Android tears it down with the isolated
        // :ps2 process, and a later boot resets the same VM safely.
    }

    override fun telemetry(): PS2Backend.Telemetry = PS2Backend.Telemetry(
        renderer = if (running) PS2Backend.Renderer.GLES3 else PS2Backend.Renderer.AUTO
    )

    @Synchronized
    private fun initializeRuntime() {
        if (initialized && NativeInterop.isVirtualMachineCreated()) return

        NativeInterop.setFilesDirPath(appContext.filesDir.absolutePath)
        NativeInterop.setCacheDirPath(appContext.cacheDir.absolutePath)
        NativeInterop.setAssetManager(appContext.assets)
        NativeInterop.setContentResolver(appContext.contentResolver)
        if (!NativeInterop.isVirtualMachineCreated()) {
            NativeInterop.createVirtualMachine()
        }
        initialized = true
    }
}
