package com.omnicore.emulator.storage

import android.content.Context
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import org.json.JSONArray
import org.json.JSONObject

class GameLibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("game_library", Context.MODE_PRIVATE)

    fun load(): List<GameEntry> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        GameEntry(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            fileName = item.optString("fileName", item.getString("title")),
                            uri = item.getString("uri"),
                            system = ConsoleSystem.valueOf(item.getString("system")),
                            sizeBytes = item.optLong("sizeBytes", 0L),
                            addedAt = item.optLong("addedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun save(games: List<GameEntry>) {
        val array = JSONArray()
        games.forEach { game ->
            array.put(JSONObject().apply {
                put("id", game.id)
                put("title", game.title)
                put("fileName", game.fileName)
                put("uri", game.uri)
                put("system", game.system.name)
                put("sizeBytes", game.sizeBytes)
                put("addedAt", game.addedAt)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object { private const val KEY = "games" }
}
