package com.omnicore.emulator.core.n64

import android.content.Context
import com.omnicore.emulator.core.CoreInfo
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.EmulatorCore
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
            "O núcleo Nintendo 64 ainda não está empacotado neste build."
        }

        // N64 gets its own settings + adaptive policy. Nothing here reuses the
        // PlayStation runtime policy; the resulting decision is consumed by the
        // dedicated N64 host as it comes online.
        val requestedConfig = N64Settings.resolve(context)
        val smartPerf = N64SmartPerf.initial(context, requestedConfig)
        val prepared = N64RomPreparer.prepare(context, game).getOrThrow()

        error(
            buildString {
                append("N64 Foundation pronta: ")
                append(prepared.sourceOrder.label)
                append(" → cache z64")
                if (prepared.reusedCache) append(" reutilizado")
                append(" • SmartPerf ")
                append(smartPerf.level.name)
                append(". O host de execução entra no próximo marco.")
            }
        )
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("z64", "n64", "v64")
    }
}
