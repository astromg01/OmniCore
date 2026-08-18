package com.omnicore.emulator.achievements

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Offline, multi-process-safe achievement storage shared by the hub and :n64. */
object OmniAchievements {
    enum class Category(val label: String, val icon: String) {
        GENERAL("Geral", "✦"),
        N64("Nintendo 64", "N"),
        PERFORMANCE("Desempenho", "⌁"),
        CONTROLS("Controles", "◎"),
        SAVES("Memória", "◫")
    }

    enum class Rarity(val label: String) {
        COMMON("Comum"),
        RARE("Rara"),
        EPIC("Épica"),
        LEGENDARY("Lendária")
    }

    data class Definition(
        val id: String,
        val title: String,
        val description: String,
        val icon: String,
        val rarity: Rarity,
        val category: Category,
        val points: Int,
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
        val points: Int get() = entries.filter { it.unlocked }.sumOf { it.definition.points }
        val maxPoints: Int get() = entries.sumOf { it.definition.points }
    }

    data class Unlock(val definition: Definition, val unlockedAt: Long)

    val definitions: List<Definition> = listOf(
        Definition("first_light", "Primeira Estrela", "Abra o OmniCore pela primeira vez.", "★", Rarity.COMMON, Category.GENERAL, 10),
        Definition("first_game", "Primeiro Cartucho", "Adicione o primeiro jogo à biblioteca.", "▣", Rarity.COMMON, Category.GENERAL, 10, "library_games", 1),
        Definition("collector_10", "Colecionador", "Tenha 10 jogos na biblioteca unificada.", "◆", Rarity.RARE, Category.GENERAL, 25, "library_games", 10),
        Definition("collector_25", "Estante Cósmica", "Tenha 25 jogos na biblioteca.", "✦", Rarity.EPIC, Category.GENERAL, 50, "library_games", 25),
        Definition("collector_50", "Galáxia de Jogos", "Tenha 50 jogos na biblioteca.", "✹", Rarity.LEGENDARY, Category.GENERAL, 100, "library_games", 50),

        Definition("n64_first_run", "64 Bits Acordados", "Chegue ao primeiro frame confirmado de um jogo N64.", "N", Rarity.COMMON, Category.N64, 10),
        Definition("n64_sessions_5", "Volta ao Cartucho", "Inicie 5 sessões N64 confirmadas.", "↻", Rarity.RARE, Category.N64, 25, "n64_sessions", 5),
        Definition("n64_sessions_20", "Veterano 64", "Inicie 20 sessões N64 confirmadas.", "N", Rarity.EPIC, Category.N64, 50, "n64_sessions", 20),
        Definition("n64_unique_3", "Passeio 64", "Jogue 3 títulos N64 diferentes.", "◇", Rarity.RARE, Category.N64, 25, "n64_unique_games", 3),
        Definition("n64_unique_10", "Explorador 64", "Jogue 10 títulos N64 diferentes.", "✧", Rarity.EPIC, Category.N64, 50, "n64_unique_games", 10),
        Definition("n64_10m", "Sessão Dourada", "Jogue 10 minutos ativos de Nintendo 64.", "★", Rarity.RARE, Category.N64, 25, "n64_active_seconds", 600),
        Definition("n64_30m", "Meia Hora no 64", "Some 30 minutos ativos no Nintendo 64.", "◷", Rarity.RARE, Category.N64, 25, "n64_active_seconds", 1800),
        Definition("n64_1h", "Hora Estelar", "Some 1 hora ativa no Nintendo 64.", "✦", Rarity.EPIC, Category.N64, 50, "n64_active_seconds", 3600),
        Definition("n64_3h", "Lenda do Cartucho", "Some 3 horas ativas no Nintendo 64.", "✹", Rarity.LEGENDARY, Category.N64, 100, "n64_active_seconds", 10800),

        Definition("tuner", "Afinador", "Abra o painel Desempenho agora.", "⌁", Rarity.COMMON, Category.PERFORMANCE, 10, "perf_panel_opens", 1),
        Definition("tuner_10", "Olho Clínico", "Abra o painel de desempenho 10 vezes.", "⌁", Rarity.RARE, Category.PERFORMANCE, 25, "perf_panel_opens", 10),
        Definition("silky_session", "Fluxo de Seda", "Mantenha uma janela estável com jitter baixo no PrecisionGovernor v2.", "✧", Rarity.EPIC, Category.PERFORMANCE, 50),
        Definition("stable_10m", "Órbita Estável", "Some 10 minutos de janelas estáveis no N64.", "◎", Rarity.LEGENDARY, Category.PERFORMANCE, 100, "n64_stable_seconds", 600),
        Definition("clean_audio_10m", "Som de Cristal", "Some 10 minutos sem novos underruns de áudio.", "♫", Rarity.EPIC, Category.PERFORMANCE, 50, "n64_clean_audio_seconds", 600),
        Definition("direct_presenter", "Rota Direta", "Jogue uma sessão usando DirectPresenter.", "↯", Rarity.RARE, Category.PERFORMANCE, 25),

        Definition("customizer", "Do Meu Jeito", "Edite e salve o layout dos controles N64.", "✦", Rarity.COMMON, Category.CONTROLS, 10, "layout_edits", 1),
        Definition("customizer_5", "Arquiteto Touch", "Salve 5 ajustes de layout dos controles.", "✥", Rarity.RARE, Category.CONTROLS, 25, "layout_edits", 5),
        Definition("smart_analog", "Analógico Esperto", "Ative na prática a ponte Smart Analog → D-pad.", "◎", Rarity.RARE, Category.CONTROLS, 25),
        Definition("cinema_mode", "Tela Livre", "Jogue com os controles touch ocultos.", "◌", Rarity.COMMON, Category.CONTROLS, 10),

        Definition("save_keeper", "Guardião do Tempo", "Envie seu primeiro save state N64.", "◫", Rarity.COMMON, Category.SAVES, 10, "save_states", 1),
        Definition("time_traveler", "Viajante do Tempo", "Envie 10 save states N64.", "◫", Rarity.RARE, Category.SAVES, 25, "save_states", 10),
        Definition("first_restore", "De Volta ao Momento", "Carregue seu primeiro save state N64.", "↶", Rarity.COMMON, Category.SAVES, 10, "load_states", 1),
        Definition("restore_10", "Linha do Tempo", "Carregue 10 save states N64.", "↶", Rarity.RARE, Category.SAVES, 25, "load_states", 10)
    )

    private data class State(
        val counters: MutableMap<String, Int> = mutableMapOf(),
        val unlocked: MutableMap<String, Long> = mutableMapOf(),
        val marks: MutableSet<String> = mutableSetOf()
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
        unlockDefinition(state, definition)
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

    fun recordN64Launch(context: Context, gameKey: String): List<Unlock> =
        withLockedState(context, writeBack = true) { state ->
            state.counters["n64_sessions"] = (state.counters["n64_sessions"] ?: 0) + 1
            val safeKey = gameKey.replace('|', '_').take(96)
            if (state.marks.add("n64_game:$safeKey")) {
                state.counters["n64_unique_games"] = (state.counters["n64_unique_games"] ?: 0) + 1
            }
            val out = mutableListOf<Unlock>()
            definitions.firstOrNull { it.id == "n64_first_run" }?.let { unlockDefinition(state, it)?.let(out::add) }
            out += evaluate(state)
            out.distinctBy { it.definition.id }
        }

    fun recordN64Minute(context: Context, stable: Boolean, cleanAudio: Boolean): List<Unlock> =
        withLockedState(context, writeBack = true) { state ->
            state.counters["n64_active_seconds"] = (state.counters["n64_active_seconds"] ?: 0) + 60
            if (stable) state.counters["n64_stable_seconds"] = (state.counters["n64_stable_seconds"] ?: 0) + 60
            if (cleanAudio) state.counters["n64_clean_audio_seconds"] = (state.counters["n64_clean_audio_seconds"] ?: 0) + 60
            evaluate(state)
        }

    fun recordPerformancePanel(context: Context): List<Unlock> = addCounter(context, "perf_panel_opens", 1)
    fun recordLayoutEdit(context: Context): List<Unlock> = addCounter(context, "layout_edits", 1)
    fun recordSaveState(context: Context): List<Unlock> = addCounter(context, "save_states", 1)
    fun recordLoadState(context: Context): List<Unlock> = addCounter(context, "load_states", 1)

    private fun unlockDefinition(state: State, definition: Definition): Unlock? {
        if (state.unlocked.containsKey(definition.id)) return null
        val now = System.currentTimeMillis()
        state.unlocked[definition.id] = now
        return Unlock(definition, now)
    }

    private fun evaluate(state: State): List<Unlock> {
        val unlocked = mutableListOf<Unlock>()
        definitions.forEach { definition ->
            val key = definition.counterKey ?: return@forEach
            if (state.unlocked.containsKey(definition.id)) return@forEach
            if ((state.counters[key] ?: 0) >= definition.target) {
                unlockDefinition(state, definition)?.let(unlocked::add)
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
                    parts.size == 2 && parts[0] == "M" -> state.marks.add(parts[1])
                }
            }
        }
        return state
    }

    private fun writeState(file: File, state: State) {
        val text = buildString {
            state.counters.toSortedMap().forEach { (key, value) -> append("C|").append(key).append('|').append(value).append('\n') }
            state.unlocked.toSortedMap().forEach { (id, at) -> append("U|").append(id).append('|').append(at).append('\n') }
            state.marks.toSortedSet().forEach { mark -> append("M|").append(mark).append('\n') }
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
            }
        }
    }
}
