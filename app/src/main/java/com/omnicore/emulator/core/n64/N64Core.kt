package com.omnicore.emulator.core.n64

import android.content.Context
import com.omnicore.emulator.core.CoreInfo
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.EmulatorCore
import com.omnicore.emulator.emulation.N64EmulationActivity
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.N64SmartPerf
import com.omnicore.emulator.settings.N64Settings

class N64Core : EmulatorCore {
    override val info = CoreInfo(
        id = "mupen64plus-next",
        name = "Mupen64Plus-Next",
        system = ConsoleSystem.NINTENDO_64,
        state = CoreState.EXPERIMENTAL,
        version = "pinned f275caf"
    )

    /**
     * The N64 native runtime is verified inside N64EmulationActivity's isolated
     * process. Avoid dlopen/probing Mupen from the main library process: a native
     * crash must never take the OmniCore hub down with it.
     */
    override fun isAvailable(): Boolean = true

    override fun launch(context: Context, game: GameEntry): Result<Unit> = runCatching {
        val extension = game.fileName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS || extension.isBlank()) {
            "Formato Nintendo 64 não reconhecido: .$extension"
        }

        // Warm only Kotlin-owned N64 policy state. ROM signature validation,
        // native runtime probing and game I/O happen in the isolated N64 process.
        N64SmartPerf.initial(context, N64Settings.resolve(context))
        context.startActivity(N64EmulationActivity.intent(context, game))
    }

    companion object {
        // u1 is advertised by the pinned Mupen64Plus-Next core itself. rom/bin
        // remain accepted only after the signature validator proves N64 content.
        val SUPPORTED_EXTENSIONS = setOf(
            "z64", "n64", "v64", "u1", "rom", "bin", "zip", "gz", "gzip"
        )
    }
}
