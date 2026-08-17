package com.omnicore.emulator.core

import android.content.Context
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry

enum class CoreState { READY, PLANNED, EXPERIMENTAL }

data class CoreInfo(
    val id: String,
    val name: String,
    val system: ConsoleSystem,
    val state: CoreState,
    val version: String = "not-installed"
)

interface EmulatorCore {
    val info: CoreInfo
    fun isAvailable(): Boolean
    fun launch(context: Context, game: GameEntry): Result<Unit>
}
