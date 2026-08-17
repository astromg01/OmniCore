package com.omnicore.emulator.core.ps1

import android.content.Context
import com.omnicore.emulator.core.CoreInfo
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.EmulatorCore
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.emulation.EmulationActivity
import com.omnicore.emulator.library.RomDetector
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry

class Ps1Core : EmulatorCore {
    override val info = CoreInfo(
        id = "pcsx-rearmed",
        name = "PCSX-ReARMed",
        system = ConsoleSystem.PLAYSTATION_1,
        state = CoreState.READY,
        version = "pinned da2cb8e"
    )

    override fun isAvailable(): Boolean = NativeBridge.hasPs1Core()

    override fun launch(context: Context, game: GameEntry): Result<Unit> = runCatching {
        check(isAvailable()) {
            "O core PS1 não está empacotado neste APK. Gere o build completo pelo workflow Android Build."
        }
        val extension = RomDetector.extension(game.fileName)
        require(extension in SINGLE_FILE_EXTENSIONS) {
            "Na v0.2 o PS1 abre jogos de arquivo único (${SINGLE_FILE_EXTENSIONS.joinToString { ".$it" }}). " +
                "CUE/M3U e jogos com várias faixas entram na próxima etapa."
        }
        context.startActivity(EmulationActivity.intent(context, game, extension))
    }


    companion object {
        val SINGLE_FILE_EXTENSIONS = setOf("chd", "pbp", "iso", "bin", "img", "mdf", "cbn", "exe")
    }
}
