package com.omnicore.emulator.core.ps2

/** Minimal native foundation probe. No PS2 game code is loaded here yet. */
object PS2NativeBridge {
    init {
        System.loadLibrary("omnicore_ps2_runtime")
    }

    data class Probe(
        val apiLevel: Int,
        val pointerBits: Int,
        val pageSize: Int,
        val architecture: String,
        val vulkanLoader: Boolean,
        val gles3Build: Boolean,
        val foundationVersion: String
    )

    fun descriptor(): String = nativeDescriptor()

    fun probe(): Probe {
        val values = nativeProbe()
            .split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
            }
            .toMap()

        return Probe(
            apiLevel = values["api"]?.toIntOrNull() ?: -1,
            pointerBits = values["ptr"]?.toIntOrNull() ?: -1,
            pageSize = values["page"]?.toIntOrNull() ?: -1,
            architecture = values["arch"].orEmpty().ifBlank { "unknown" },
            vulkanLoader = values["vulkan"] == "1",
            gles3Build = values["gles3"] == "1",
            foundationVersion = values["version"].orEmpty().ifBlank { "unknown" }
        )
    }

    private external fun nativeDescriptor(): String
    private external fun nativeProbe(): String
}
