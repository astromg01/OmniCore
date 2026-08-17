package com.omnicore.emulator.model

enum class ConsoleSystem(
    val displayName: String,
    val shortName: String,
    val extensions: Set<String>,
    val generation: String
) {
    NINTENDO_64("Nintendo 64", "N64", setOf("z64", "n64", "v64"), "1996"),
    PLAYSTATION_1("PlayStation", "PS1", setOf("cue", "bin", "chd", "ccd"), "1994"),
    PLAYSTATION_2("PlayStation 2", "PS2", setOf("iso", "chd"), "2000"),
    PSP("PlayStation Portable", "PSP", setOf("iso", "cso", "pbp"), "2004"),
    WII("Nintendo Wii", "Wii", setOf("rvz", "wbfs", "iso", "gcz"), "2006"),
    SWITCH("Nintendo Switch", "Switch", setOf("nsp", "xci", "nro"), "2017")
}
