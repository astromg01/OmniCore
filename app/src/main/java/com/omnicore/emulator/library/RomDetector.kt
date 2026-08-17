package com.omnicore.emulator.library

import com.omnicore.emulator.model.ConsoleSystem

object RomDetector {
    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun candidates(fileName: String): List<ConsoleSystem> {
        val ext = extension(fileName)
        return ConsoleSystem.entries.filter { ext in it.extensions }
    }

    fun detect(fileName: String): ConsoleSystem? = candidates(fileName).singleOrNull()
}
