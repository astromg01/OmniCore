package com.omnicore.emulator.cheats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object CheatStore {
    data class Cheat(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val code: String,
        val enabled: Boolean = true
    )

    private const val PREFS = "omnicore_cheats"
    private const val MAX_CHEATS = 128
    private const val MAX_CODE_CHARS = 8192

    fun load(context: Context, gameKey: String): List<Cheat> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(safeGameKey(gameKey), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_CHEATS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val code = item.optString("code").trim().take(MAX_CODE_CHARS)
                    if (code.isBlank()) continue
                    add(
                        Cheat(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name").ifBlank { "Cheat ${index + 1}" }.take(80),
                            code = code,
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, gameKey: String, cheats: List<Cheat>) {
        val array = JSONArray()
        cheats.take(MAX_CHEATS).forEach { cheat ->
            val code = cheat.code.trim().take(MAX_CODE_CHARS)
            if (code.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("id", cheat.id)
                    .put("name", cheat.name.trim().ifBlank { "Cheat" }.take(80))
                    .put("code", code)
                    .put("enabled", cheat.enabled)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(safeGameKey(gameKey), array.toString())
            .apply()
    }

    private fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_') }
    }.ifBlank { "game" }
}
