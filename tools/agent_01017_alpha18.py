from pathlib import Path

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, content: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding='utf-8')


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:100]!r}')
    if text.count(old) != 1:
        raise SystemExit(f'pattern not unique in {path}: {old[:100]!r}')
    write(path, text.replace(old, new, 1))


build_path = 'app/build.gradle.kts'
build = read(build_path)
if 'versionCode = 32' not in build or 'versionName = "0.10.16"' not in build:
    raise SystemExit('Alpha 18 expects OmniCore 0.10.16 / versionCode 32')
build = build.replace('versionCode = 32', 'versionCode = 33', 1)
build = build.replace('versionName = "0.10.16"', 'versionName = "0.10.17"', 1)
write(build_path, build)

write('app/src/main/java/com/omnicore/emulator/ui/theme/OmniStarfield.kt', r'''package com.omnicore.emulator.ui.theme

import android.app.ActivityManager
import android.os.PowerManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

private data class OmniStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val pulse: Float
)

/**
 * Draw-only StarUI background. The animation state is consumed inside Canvas,
 * so frame ticks invalidate the drawing layer instead of recomposing the hub.
 */
@Composable
fun OmniStarfieldBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val activityManager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val reducedMotion = remember(activityManager, powerManager) {
        activityManager?.isLowRamDevice == true || powerManager?.isPowerSaveMode == true
    }
    val count = if (reducedMotion) 8 else 18
    val stars = remember(count) {
        val random = Random(0x0C0E2026L)
        List(count) {
            OmniStar(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.75f + random.nextFloat() * 1.55f,
                speed = 0.030f + random.nextFloat() * 0.075f,
                pulse = random.nextFloat()
            )
        }
    }

    if (reducedMotion) {
        Canvas(modifier) {
            stars.forEachIndexed { index, star ->
                val tint = if (index % 5 == 0) Color(0xFF9FE7FF) else Color(0xFFEDE8FF)
                drawCircle(
                    color = tint.copy(alpha = 0.52f),
                    radius = star.radius * density,
                    center = Offset(star.x * size.width, star.y * size.height)
                )
            }
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "OmniStarfield")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OmniStarPhase"
    )

    Canvas(modifier) {
        // Read animation state in DrawScope: draw invalidation only, no full hub recomposition.
        val currentPhase = phase.value
        stars.forEachIndexed { index, star ->
            val yUnit = (star.y + currentPhase * star.speed * 8f) % 1f
            val twinkle = (
                0.47f + 0.22f * sin((currentPhase + star.pulse) * PI * 2.0).toFloat()
            ).coerceIn(0.28f, 0.69f)
            val tint = if (index % 5 == 0) Color(0xFF9FE7FF) else Color(0xFFEDE8FF)
            drawCircle(
                color = tint.copy(alpha = twinkle),
                radius = star.radius * density,
                center = Offset(star.x * size.width, yUnit * size.height)
            )
        }
    }
}
''')

write('app/src/main/java/com/omnicore/emulator/achievements/OmniAchievements.kt', r'''package com.omnicore.emulator.achievements

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
''')

write('app/src/main/java/com/omnicore/emulator/ui/achievements/AchievementsScreen.kt', r'''package com.omnicore.emulator.ui.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnicore.emulator.achievements.OmniAchievements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AchievementsScreen() {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<OmniAchievements.Snapshot?>(null) }
    var category by remember { mutableStateOf<OmniAchievements.Category?>(null) }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { OmniAchievements.snapshot(context) }
    }

    val current = snapshot
    val shown = remember(current, category) {
        current?.entries?.filter { category == null || it.definition.category == category }.orEmpty()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xD91A1830)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Constelação OmniCore", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text(
                        "Conquistas locais, offline e sem conta. Progresso persistente entre o hub e o runtime N64.",
                        color = Color(0xFFB5B8D2)
                    )
                    Text(
                        if (current == null) "Carregando estrelas…" else "${current.unlockedCount}/${current.totalCount} • ${current.points}/${current.maxPoints} pts",
                        color = Color(0xFF8FDEFF),
                        fontWeight = FontWeight.Bold
                    )
                    if (current != null) {
                        Box(
                            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp))
                                .background(Color(0xFF292A43))
                        ) {
                            val fraction = if (current.maxPoints <= 0) 0f else current.points.toFloat() / current.maxPoints
                            Box(
                                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp)
                                    .background(Color(0xFF8FDEFF))
                            )
                        }
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = category == null,
                        onClick = { category = null },
                        label = { Text("Todas") }
                    )
                }
                items(OmniAchievements.Category.entries, key = { it.name }) { item ->
                    FilterChip(
                        selected = category == item,
                        onClick = { category = item },
                        label = { Text("${item.icon} ${item.label}") }
                    )
                }
            }
        }

        if (current != null) {
            items(shown, key = { it.definition.id }) { entry ->
                val unlocked = entry.unlocked
                val accent = when (entry.definition.rarity) {
                    OmniAchievements.Rarity.COMMON -> Color(0xFF7FC9FF)
                    OmniAchievements.Rarity.RARE -> Color(0xFFAA8CFF)
                    OmniAchievements.Rarity.EPIC -> Color(0xFFFFD85A)
                    OmniAchievements.Rarity.LEGENDARY -> Color(0xFFFF9E78)
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (unlocked) Color(0xE51B1A31) else Color(0xB8121320)
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                                .background(accent.copy(alpha = if (unlocked) 0.20f else 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                entry.definition.icon,
                                color = if (unlocked) accent else Color(0xFF62657A),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    entry.definition.title,
                                    fontWeight = FontWeight.Black,
                                    color = if (unlocked) Color.White else Color(0xFF9B9DB0)
                                )
                                Text(
                                    "${entry.definition.points} pts",
                                    color = accent,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                "${entry.definition.rarity.label} • ${entry.definition.category.label}",
                                color = accent.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                entry.definition.description,
                                color = Color(0xFF999DB4),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Box(
                                Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp))
                                    .background(Color(0xFF2B2D40))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(entry.fraction.coerceIn(0f, 1f)).height(5.dp)
                                        .background(accent)
                                )
                            }
                            if (!unlocked && entry.definition.target > 1) {
                                Text(
                                    "${entry.progress}/${entry.definition.target}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF777B91)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
''')

write('app/src/main/java/com/omnicore/emulator/achievements/AchievementBanner.kt', r'''package com.omnicore.emulator.achievements

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.WeakHashMap

/** Lightweight queued banner: multiple unlocks are shown sequentially instead of replacing each other. */
object AchievementBanner {
    private val pending = WeakHashMap<Activity, ArrayDeque<OmniAchievements.Unlock>>()
    private val active = WeakHashMap<Activity, Boolean>()

    fun show(activity: Activity, unlock: OmniAchievements.Unlock) = showAll(activity, listOf(unlock))

    fun showAll(activity: Activity, unlocks: List<OmniAchievements.Unlock>) {
        if (unlocks.isEmpty()) return
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val queue = pending.getOrPut(activity) { ArrayDeque() }
            unlocks.distinctBy { it.definition.id }.forEach(queue::addLast)
            if (active[activity] != true) showNext(activity)
        }
    }

    private fun showNext(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            pending.remove(activity)
            active.remove(activity)
            return
        }
        val queue = pending[activity]
        val unlock = queue?.pollFirst()
        if (unlock == null) {
            active[activity] = false
            return
        }
        active[activity] = true
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            active[activity] = false
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(36, 29, 65), Color.rgb(13, 25, 42))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(150, 157, 126, 255))
            }
            elevation = dp(10).toFloat()
            alpha = 0f
            translationY = -dp(70).toFloat()
        }

        panel.addView(TextView(activity).apply {
            text = unlock.definition.icon.ifBlank { "★" }
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 216, 48))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        copy.addView(TextView(activity).apply {
            text = "CONQUISTA • ${unlock.definition.rarity.label.uppercase()} • ${unlock.definition.points} PTS"
            textSize = 10f
            setTextColor(Color.rgb(153, 205, 255))
            setTypeface(typeface, Typeface.BOLD)
        })
        copy.addView(TextView(activity).apply {
            text = unlock.definition.title
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        copy.addView(TextView(activity).apply {
            text = unlock.definition.description
            textSize = 11f
            setTextColor(Color.rgb(185, 192, 214))
            maxLines = 2
        })
        panel.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { setMargins(dp(12), dp(14), dp(12), 0) }
        )
        panel.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        panel.postDelayed({
            panel.animate()
                .alpha(0f)
                .translationY(-dp(42).toFloat())
                .setDuration(200L)
                .withEndAction {
                    if (panel.parent === root) root.removeView(panel)
                    active[activity] = false
                    panel.postDelayed({ showNext(activity) }, 120L)
                }
                .start()
        }, 2450L)
    }
}
''')

activity_path = 'app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt'
replace_once(
    activity_path,
    'import java.io.File\nimport kotlin.math.abs',
    'import java.io.File\nimport java.util.concurrent.Executors\nimport kotlin.math.abs'
)
replace_once(
    activity_path,
    '''    private var achievementLastProgressAt = 0L
    private var stableAchievementStreak = 0
    private var smartAnalogAchievementQueued = false
    private var stableAchievementQueued = false
''',
    '''    private var achievementLastProgressAt = 0L
    private var stableAchievementStreak = 0
    private var smartAnalogAchievementQueued = false
    private var stableAchievementQueued = false
    private var directPresenterAchievementQueued = false
    private var launchAchievementQueued = false
    private var minuteSamples = 0
    private var minuteStableSamples = 0
    private var minuteHadAudioUnderrun = false
    private var lastAchievementAudioUnderruns = 0
    private val achievementExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmniCore-Achievement").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
'''
)
replace_once(
    activity_path,
    '''                    if (runOkPolls == 1) {
                        bootStar.visibility = View.GONE
                        unlockAchievementAsync("n64_first_run")
                        runCatching {
''',
    '''                    if (runOkPolls == 1) {
                        bootStar.visibility = View.GONE
                        if (!launchAchievementQueued) {
                            launchAchievementQueued = true
                            achievementAsync { OmniAchievements.recordN64Launch(this, currentGameKey) }
                        }
                        runCatching {
'''
)
replace_once(
    activity_path,
    '                else -> 750L\n',
    '                else -> 900L\n'
)
replace_once(
    activity_path,
    '''                    item.itemId == MENU_EDIT_DONE -> {
                        controls.setEditMode(false)
                        if (started) N64NativeBridge.setPaused(manualPaused)
                        unlockAchievementAsync("customizer")
                        Toast.makeText(this@N64EmulationActivity, "Layout N64 salvo.", Toast.LENGTH_SHORT).show()
''',
    '''                    item.itemId == MENU_EDIT_DONE -> {
                        controls.setEditMode(false)
                        if (started) N64NativeBridge.setPaused(manualPaused)
                        achievementAsync { OmniAchievements.recordLayoutEdit(this) }
                        Toast.makeText(this@N64EmulationActivity, "Layout N64 salvo.", Toast.LENGTH_SHORT).show()
'''
)
replace_once(
    activity_path,
    '''                    item.itemId == MENU_CONTROLS -> {
                        controlsVisible = !controlsVisible
                        if (!controlsVisible) {
                            controls.setEditMode(false)
                            controls.releaseAll()
                        }
''',
    '''                    item.itemId == MENU_CONTROLS -> {
                        controlsVisible = !controlsVisible
                        if (!controlsVisible) {
                            controls.setEditMode(false)
                            controls.releaseAll()
                            unlockAchievementAsync("cinema_mode")
                        }
'''
)
replace_once(
    activity_path,
    '        if (queued) unlockAchievementAsync("save_keeper")\n',
    '        if (queued) achievementAsync { OmniAchievements.recordSaveState(this) }\n'
)
replace_once(
    activity_path,
    '''        val queued = N64NativeBridge.loadState(file)
        Toast.makeText(
''',
    '''        val queued = N64NativeBridge.loadState(file)
        if (queued) achievementAsync { OmniAchievements.recordLoadState(this) }
        Toast.makeText(
'''
)
replace_once(
    activity_path,
    '''    private fun achievementAsync(block: () -> List<OmniAchievements.Unlock>) {
        Thread({
            val unlocked = runCatching(block).getOrDefault(emptyList())
            if (unlocked.isNotEmpty()) runOnUiThread {
                if (!destroyed) AchievementBanner.show(this, unlocked.first())
            }
        }, "OmniCore-Achievement").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    private fun trackAchievementTelemetry(t: N64NativeBridge.Telemetry, now: Long) {
        if (achievementLastProgressAt == 0L) achievementLastProgressAt = now
        if (now - achievementLastProgressAt >= 60_000L) {
            achievementLastProgressAt = now
            if (started && !manualPaused && !controls.isEditMode()) {
                achievementAsync { OmniAchievements.addCounter(this, "n64_active_seconds", 60) }
            }
        }

        if (t.smartAnalogDpadActive && !smartAnalogAchievementQueued) {
            smartAnalogAchievementQueued = true
            unlockAchievementAsync("smart_analog")
        }

        val targetMs = if (t.targetFps in 40f..75f) 1000f / t.targetFps else 1000f / 60f
        val stableWindow = t.sampleWindowFrames >= 90 &&
            t.precisionGovernorMode == 0 &&
            t.frameJitterMs in 0f..1.60f &&
            t.p95FrameMs > 0f && t.p95FrameMs <= targetMs * 1.10f
        stableAchievementStreak = if (stableWindow) {
            (stableAchievementStreak + 1).coerceAtMost(12)
        } else {
            (stableAchievementStreak - 1).coerceAtLeast(0)
        }
        if (stableAchievementStreak >= 8 && !stableAchievementQueued) {
            stableAchievementQueued = true
            unlockAchievementAsync("silky_session")
        }
    }
''',
    '''    private fun achievementAsync(block: () -> List<OmniAchievements.Unlock>) {
        if (destroyed) return
        runCatching {
            achievementExecutor.execute {
                val unlocked = runCatching(block).getOrDefault(emptyList())
                if (unlocked.isNotEmpty()) runOnUiThread {
                    if (!destroyed) AchievementBanner.showAll(this, unlocked)
                }
            }
        }
    }

    private fun trackAchievementTelemetry(t: N64NativeBridge.Telemetry, now: Long) {
        if (achievementLastProgressAt == 0L) {
            achievementLastProgressAt = now
            lastAchievementAudioUnderruns = t.audioUnderruns
        }

        val targetMs = if (t.targetFps in 20f..75f) 1000f / t.targetFps else 1000f / 60f
        val stableWindow = t.sampleWindowFrames >= 90 &&
            t.precisionGovernorMode == 0 &&
            t.frameJitterMs in 0f..1.60f &&
            t.p95FrameMs > 0f && t.p95FrameMs <= targetMs * 1.10f

        minuteSamples++
        if (stableWindow) minuteStableSamples++
        if (t.audioUnderruns > lastAchievementAudioUnderruns) minuteHadAudioUnderrun = true
        lastAchievementAudioUnderruns = maxOf(lastAchievementAudioUnderruns, t.audioUnderruns)

        if (now - achievementLastProgressAt >= 60_000L) {
            achievementLastProgressAt = now
            if (started && !manualPaused && !controls.isEditMode()) {
                val stableMinute = minuteSamples >= 4 && minuteStableSamples * 100 >= minuteSamples * 70
                val cleanAudioMinute = !minuteHadAudioUnderrun
                achievementAsync { OmniAchievements.recordN64Minute(this, stableMinute, cleanAudioMinute) }
            }
            minuteSamples = 0
            minuteStableSamples = 0
            minuteHadAudioUnderrun = false
        }

        if (t.smartAnalogDpadActive && !smartAnalogAchievementQueued) {
            smartAnalogAchievementQueued = true
            unlockAchievementAsync("smart_analog")
        }
        if (t.directPresenterActive && !directPresenterAchievementQueued) {
            directPresenterAchievementQueued = true
            unlockAchievementAsync("direct_presenter")
        }

        stableAchievementStreak = if (stableWindow) {
            (stableAchievementStreak + 1).coerceAtMost(12)
        } else {
            (stableAchievementStreak - 1).coerceAtLeast(0)
        }
        if (stableAchievementStreak >= 8 && !stableAchievementQueued) {
            stableAchievementQueued = true
            unlockAchievementAsync("silky_session")
        }
    }
'''
)
replace_once(
    activity_path,
    '''    private fun showPerformanceStatus() {
        unlockAchievementAsync("tuner")
        val t = N64NativeBridge.telemetry()
''',
    '''    private fun showPerformanceStatus() {
        achievementAsync { OmniAchievements.recordPerformancePanel(this) }
        val t = N64NativeBridge.telemetry()
'''
)
replace_once(
    activity_path,
    '''    private fun applySurfaceFrameRateHint(fps: Float) {
        if (Build.VERSION.SDK_INT < 30 || fps !in 40f..75f || abs(fps - lastSurfaceFps) < 0.05f) return
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return
        runCatching {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            lastSurfaceFps = fps
        }.onFailure { error ->
            N64Diagnostics.mark(this, "surface:frame_rate_hint_skipped", error.javaClass.simpleName)
        }
    }
''',
    '''    private fun applySurfaceFrameRateHint(fps: Float) {
        if (Build.VERSION.SDK_INT < 30 || fps !in 20f..75f || abs(fps - lastSurfaceFps) < 0.05f) return
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            lastSurfaceFps = fps
        }.onFailure { error ->
            N64Diagnostics.mark(this, "surface:fixed_cadence_hint_skipped", error.javaClass.simpleName)
        }
    }
'''
)
replace_once(
    activity_path,
    '''    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (::controls.isInitialized) controls.releaseAll()
        N64NativeBridge.stop()
        prepareThread = null
        super.onDestroy()
    }
''',
    '''    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (::controls.isInitialized) controls.releaseAll()
        N64NativeBridge.stop()
        achievementExecutor.shutdownNow()
        prepareThread = null
        super.onDestroy()
    }
'''
)

native_bridge_path = 'app/src/main/cpp/n64/n64_native_bridge.cpp'
native_bridge = read(native_bridge_path)
if 'OmniCore N64 Runtime 0.10.16' not in native_bridge:
    raise SystemExit('expected Alpha 17 runtime version marker')
native_bridge = native_bridge.replace('OmniCore N64 Runtime 0.10.16', 'OmniCore N64 Runtime 0.10.17', 1)
write(native_bridge_path, native_bridge)

print('Alpha 18 CadencePolish + StarUI Smooth + Achievements v2 migration applied')
