package com.omnicore.emulator.core.ps2

import android.content.Context
import com.omnicore.emulator.core.CoreInfo
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.EmulatorCore
import com.omnicore.emulator.emulation.PS2EmulationActivity
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry

/** Hub-facing PS2 launcher. Backend loading remains isolated in the :ps2 process. */
class PS2Core : EmulatorCore {
    override val info = CoreInfo(
        id = "play-ps2",
        name = "Play! PS2",
        system = ConsoleSystem.PLAYSTATION_2,
        state = CoreState.EXPERIMENTAL,
        version = "pinned 04bde0df"
    )

    override fun isAvailable(): Boolean = true

    override fun launch(context: Context, game: GameEntry): Result<Unit> = runCatching {
        val extension = game.fileName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS || extension.isBlank()) {
            "Formato PlayStation 2 não reconhecido: .$extension"
        }
        context.startActivity(PS2EmulationActivity.intent(context, game))
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("iso", "chd")
    }
}
