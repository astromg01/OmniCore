package com.omnicore.emulator.achievements

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object OmniAchievements {
    enum class Rarity(val label: String) {
        COMMON("Comum"),
        RARE("Rara"),
        EPIC("Épica")
    }

    data class Definition(
        val id: String,
        val title: String,
        val description: String,
        val icon: String,
        val rarity: Rarity,
        val counterKey: String? = null,
        val target: Int = 1
    )

    data class Entry(
        val definition: Definition,
        val progress: Int,
        val unlockedAt: Long?
    ) {
        val unlocked: Boolean get() = unlockedAt != null
        val fraction: Float
            get() = if (unlocked) 1f else (progress.toFloat() / definition.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    data class Snapshot(val entries: List<Entry>) {
        val unlockedCount: Int get() = entries.count { it.unlocked }
        val totalCount: Int get() = entries.size
    }

    data class Unlock(val definition: Definition, val unlockedAt: Long)

    val definitions: List<Definition> = listOf(
        Definition("first_light", "Primeira Estrela", "Abra o OmniCore pela primeira vez.", "★", Rarity.COMMON),
        Definition("first_game", "Primeiro Cartucho", "Adicione o primeiro jogo à biblioteca.", "▣", Rarity.COMMON, "library_games", 1),
        Definition("collector_10", "Colecionador", "Tenha 10 jogos na biblioteca unificada.", "◆", Rarity.RARE, "library_games", 10),
        Definition("n64_first_run", "64 Bits Acordados", "Chegue ao primeiro frame confirmado de um jogo N64.", "N", Rarity.COMMON),
        Definition("n64_10m", "Sessão Dourada", "Jogue 10 minutos ativos de Nintendo 64.", "★", Rarity.RARE, "n64_active_seconds", 600),
        Definition("tuner", "Afinador", "Abra o painel Desempenho agora.", "⌁", Rarity.COMMON),
        Definition("customizer", "Do Meu Jeito", "Edite e salve o layout dos controles N64.", "✦", Rarity.COMMON),
        Definition("save_keeper", "Guardião do Tempo", "Envie seu primeiro save state N64.", "◫", Rarity.COMMON),
        Definition("smart_analog", "Analógico Esperto", "Ative na prática a ponte Smart Analog → D-pad.", "◎", Rarity.RARE),
        Definition("silky_session", "Fluxo de Seda", "Mantenha uma janela estável com jitter baixo no PrecisionGovernor v2.", "✧", Rarity.EPIC)
    )

    private data class State(
        val counters: MutableMap<String, Int> = mutableMapOf(),
        val unlocked: MutableMap<String, Long> = mutableMapOf()
    )

    fun snapshot(context: Context): Snapshot = withLockedState(context, writeBack = false) { state ->
        Snapshot(definitions.map { definition ->
            val value = definition.counterKey?.let { state.counters[it] } ?: 0
            Entry(
                definition = definition,
                progress = value.coerceAtMost(definition.target),
                unlockedAt = state.unlocked[definition.id]
            )
        })
    }

    fun unlock(context: Context, id: String): Unlock? = withLockedState(context, writeBack = true) { state ->
        val definition = definitions.firstOrNull { it.id == id } ?: return@withLockedState null
        if (state.unlocked.containsKey(id)) return@withLockedState null
        val now = System.currentTimeMillis()
        state.unlocked[id] = now
        Unlock(definition, now)
    }

    fun setCounter(context: Context, key: String, value: Int): List<Unlock> =
        withLockedState(context, writeBack = true) { state ->
            state.counters[key] = maxOf(state.counters[key] ?: 0, value.coerceAtLeast(0))
            evaluate(state)
        }

    fun addCounter(context: Context, key: String, amount: Int): List<Unlock> =
        withLockedState(context, writeBack = true) { state ->
            if (amount > 0) state.counters[key] = (state.counters[key] ?: 0) + amount
            evaluate(state)
        }

    private fun evaluate(state: State): List<Unlock> {
        val unlocked = mutableListOf<Unlock>()
        val now = System.currentTimeMillis()
        definitions.forEach { definition ->
            val key = definition.counterKey ?: return@forEach
            if (state.unlocked.containsKey(definition.id)) return@forEach
            if ((state.counters[key] ?: 0) >= definition.target) {
                state.unlocked[definition.id] = now
                unlocked += Unlock(definition, now)
            }
        }
        return unlocked
    }

    private fun stateDir(context: Context): File =
        File(context.applicationContext.filesDir, "achievements").apply { mkdirs() }

    private fun readState(file: File): State {
        val state = State()
        if (!file.isFile) return state
        runCatching {
            file.forEachLine { line ->
                val parts = line.split('|')
                when {
                    parts.size == 3 && parts[0] == "C" -> parts[2].toIntOrNull()?.let { state.counters[parts[1]] = it }
                    parts.size == 3 && parts[0] == "U" -> parts[2].toLongOrNull()?.let { state.unlocked[parts[1]] = it }
                }
            }
        }
        return state
    }

    private fun writeState(file: File, state: State) {
        val text = buildString {
            state.counters.toSortedMap().forEach { (key, value) -> append("C|").append(key).append('|').append(value).append('\n') }
            state.unlocked.toSortedMap().forEach { (id, at) -> append("U|").append(id).append('|').append(at).append('\n') }
        }
        val tmp = File(file.parentFile, "state-v1.tmp")
        tmp.writeText(text)
        runCatching {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            file.writeText(text)
            tmp.delete()
        }
    }

    @Synchronized
    private fun <T> withLockedState(context: Context, writeBack: Boolean, block: (State) -> T): T {
        val dir = stateDir(context)
        val stateFile = File(dir, "state-v1.txt")
        val lockFile = File(dir, "state-v1.lock")
        RandomAccessFile(lockFile, "rw").use { raf ->
            val channel = raf.channel
            val lock = channel.lock()
            try {
                val state = readState(stateFile)
                val result = block(state)
                if (writeBack) writeState(stateFile, state)
                return result
            } finally {
                runCatching { lock.release() }
                runCatching { channel.close() }
            }
        }
    }
}
