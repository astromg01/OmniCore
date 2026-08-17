package com.omnicore.emulator.model

data class GameEntry(
    val id: String,
    val title: String,
    val fileName: String,
    val uri: String,
    val system: ConsoleSystem,
    val sizeBytes: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val folderUri: String? = null,
    val companionUris: List<String> = emptyList()
)
