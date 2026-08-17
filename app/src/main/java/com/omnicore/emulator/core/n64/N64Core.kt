package com.omnicore.emulator.core.n64

import android.content.Context
import com.omnicore.emulator.core.CoreInfo
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.EmulatorCore
import com.omnicore.emulator.emulation.N64EmulationActivity
import com.omnicore.emulator.library.RomDetector
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

    override fun isAvailable(): Boolean = N64NativeBridge.hasCore()

    override fun launch(context: Context, game: GameEntry): Result<Unit> = runCatching {
        check(isAvailable()) {
            "O núcleo Nintendo 64 não está empacotado neste build."
        }
        val extension = RomDetector.extension(game.fileName)
        require(extension in SUPPORTED_EXTENSIONS) {
            "Formato Nintendo 64 não suportado: .$extension"
        }

        // Warm only N64-owned caches here. ROM I/O stays off this caller thread
        // and is performed by N64EmulationActivity.
        N64SmartPerf.initial(context, N64Settings.resolve(context))
        context.startActivity(N64EmulationActivity.intent(context, game))
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("z64", "n64", "v64")
    }
}
