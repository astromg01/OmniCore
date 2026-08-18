from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    found = text.count(old)
    if found < count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {found}: {old[:160]!r}")
    write(path, text.replace(old, new, count))


# Version: Alpha 17 is UI/achievement polish over the proven Alpha 16 native governor.
replace("app/build.gradle.kts", "versionCode = 31", "versionCode = 32")
replace("app/build.gradle.kts", 'versionName = "0.10.15"', 'versionName = "0.10.16"')
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    "OmniCore N64 Runtime 0.10.15",
    "OmniCore N64 Runtime 0.10.16",
)

# ---------------------------------------------------------------------------
# Local, cross-process achievement store. The main hub and isolated :n64
# process coordinate through a tiny locked file instead of stale multiprocess
# SharedPreferences caches.
# ---------------------------------------------------------------------------
write(
    "app/src/main/java/com/omnicore/emulator/achievements/OmniAchievements.kt",
    r'''package com.omnicore.emulator.achievements

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
'''
)

write(
    "app/src/main/java/com/omnicore/emulator/achievements/AchievementBanner.kt",
    r'''package com.omnicore.emulator.achievements

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object AchievementBanner {
    private const val TAG = "omnicore-achievement-banner"

    fun show(activity: Activity, unlock: OmniAchievements.Unlock) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

            val density = activity.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val panel = LinearLayout(activity).apply {
                tag = TAG
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

            val star = TextView(activity).apply {
                text = "★"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 216, 48))
            }
            panel.addView(star, LinearLayout.LayoutParams(dp(44), dp(44)))

            val copy = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
            }
            copy.addView(TextView(activity).apply {
                text = "CONQUISTA • ${unlock.definition.rarity.label.uppercase()}"
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

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(dp(12), dp(14), dp(12), 0)
            }
            root.addView(panel, params)
            panel.animate().alpha(1f).translationY(0f).setDuration(220L).start()
            panel.postDelayed({
                panel.animate()
                    .alpha(0f)
                    .translationY(-dp(42).toFloat())
                    .setDuration(240L)
                    .withEndAction { if (panel.parent === root) root.removeView(panel) }
                    .start()
            }, 2900L)
        }
    }
}
'''
)

# ---------------------------------------------------------------------------
# Starfield theme: 20 fps maximum in normal mode, static/reduced density on
# low-RAM devices or Battery Saver. It is hub-only, never drawn over gameplay.
# ---------------------------------------------------------------------------
write(
    "app/src/main/java/com/omnicore/emulator/ui/theme/OmniStarfield.kt",
    r'''package com.omnicore.emulator.ui.theme

import android.app.ActivityManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
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

@Composable
fun OmniStarfieldBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val activityManager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val reducedMotion = remember(activityManager, powerManager) {
        activityManager?.isLowRamDevice == true || powerManager?.isPowerSaveMode == true
    }
    val count = if (reducedMotion) 9 else 24
    val stars = remember(count) {
        val random = Random(0x0C0E2026L)
        List(count) {
            OmniStar(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.7f + random.nextFloat() * 1.7f,
                speed = 0.035f + random.nextFloat() * 0.09f,
                pulse = random.nextFloat()
            )
        }
    }
    val phase by produceState(initialValue = 0f, reducedMotion) {
        if (reducedMotion) {
            value = 0f
            return@produceState
        }
        val started = SystemClock.elapsedRealtime()
        while (true) {
            value = ((SystemClock.elapsedRealtime() - started) % 12000L) / 12000f
            delay(50L)
        }
    }

    Canvas(modifier) {
        stars.forEachIndexed { index, star ->
            val yUnit = (star.y + phase * star.speed * 8f) % 1f
            val twinkle = if (reducedMotion) 0.58f else {
                (0.46f + 0.24f * sin((phase + star.pulse) * PI * 2.0).toFloat()).coerceIn(0.24f, 0.72f)
            }
            val tint = if (index % 5 == 0) Color(0xFF9FE7FF) else Color(0xFFEDE8FF)
            drawCircle(
                color = tint.copy(alpha = twinkle),
                radius = star.radius * density,
                center = Offset(star.x * size.width, yUnit * size.height)
            )
        }
    }
}
'''
)

write(
    "app/src/main/java/com/omnicore/emulator/ui/achievements/AchievementsScreen.kt",
    r'''package com.omnicore.emulator.ui.achievements

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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { OmniAchievements.snapshot(context) }
    }

    val current = snapshot
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
                        "Conquistas locais, offline e sem conta. Seu progresso fica salvo no aparelho.",
                        color = Color(0xFFB5B8D2)
                    )
                    Text(
                        if (current == null) "Carregando estrelas…" else "${current.unlockedCount}/${current.totalCount} desbloqueadas",
                        color = Color(0xFF8FDEFF),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (current != null) {
            items(current.entries, key = { it.definition.id }) { entry ->
                val unlocked = entry.unlocked
                val accent = when (entry.definition.rarity) {
                    OmniAchievements.Rarity.COMMON -> Color(0xFF7FC9FF)
                    OmniAchievements.Rarity.RARE -> Color(0xFFAA8CFF)
                    OmniAchievements.Rarity.EPIC -> Color(0xFFFFD85A)
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
                                Text(entry.definition.rarity.label, color = accent, style = MaterialTheme.typography.labelSmall)
                            }
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
'''
)

# Theme palette: dark cosmic base, soft star gold and cool cyan/purple accents.
write(
    "app/src/main/java/com/omnicore/emulator/ui/theme/Theme.kt",
    r'''package com.omnicore.emulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OmniStarDark = darkColorScheme(
    primary = Color(0xFFA58BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF342B64),
    onPrimaryContainer = Color(0xFFF1ECFF),
    secondary = Color(0xFF74DFFF),
    onSecondary = Color(0xFF002631),
    secondaryContainer = Color(0xFF123D4B),
    tertiary = Color(0xFFFFD85A),
    onTertiary = Color(0xFF352B00),
    background = Color(0xFF060712),
    onBackground = Color(0xFFF5F2FF),
    surface = Color(0xFF111324),
    onSurface = Color(0xFFF5F2FF),
    surfaceVariant = Color(0xFF1B1D32),
    onSurfaceVariant = Color(0xFFB3B8CF),
    outline = Color(0xFF5B607B),
    error = Color(0xFFFF728A)
)

@Composable
fun OmniCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OmniStarDark, content = content)
}
'''
)

# Main launch achievement is recorded off the UI thread and announced after the
# Compose root is attached.
replace(
    "app/src/main/java/com/omnicore/emulator/MainActivity.kt",
    "import com.omnicore.emulator.core.n64.N64Diagnostics\n",
    "import com.omnicore.emulator.achievements.AchievementBanner\nimport com.omnicore.emulator.achievements.OmniAchievements\nimport com.omnicore.emulator.core.n64.N64Diagnostics\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/MainActivity.kt",
    "        setContent {\n            OmniCoreTheme {\n                OmniCoreV4App()\n            }\n        }\n",
    "        setContent {\n            OmniCoreTheme {\n                OmniCoreV4App()\n            }\n        }\n        recordLaunchAchievement()\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/MainActivity.kt",
    "    private fun warmSafeMainRuntimeCaches() {\n",
    '''    private fun recordLaunchAchievement() {
        Thread({
            val unlock = runCatching { OmniAchievements.unlock(this, "first_light") }.getOrNull()
            if (unlock != null) runOnUiThread {
                window.decorView.postDelayed({ AchievementBanner.show(this, unlock) }, 650L)
            }
        }, "OmniCore-AchievementInit").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    private fun warmSafeMainRuntimeCaches() {
'''
)

# Hub: starfield, 4th navigation destination, achievement counter sync and star logo.
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    "import com.omnicore.emulator.BuildConfig\n",
    "import com.omnicore.emulator.BuildConfig\nimport com.omnicore.emulator.achievements.OmniAchievements\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    "import com.omnicore.emulator.ui.n64.N64SettingsDialog\n",
    "import com.omnicore.emulator.ui.achievements.AchievementsScreen\nimport com.omnicore.emulator.ui.n64.N64SettingsDialog\nimport com.omnicore.emulator.ui.theme.OmniStarfieldBackground\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    "private enum class OmniHubScreen { LIBRARY, SYSTEMS, SETTINGS }",
    "private enum class OmniHubScreen { LIBRARY, SYSTEMS, ACHIEVEMENTS, SETTINGS }",
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    "private val OmniBgTop = Color(0xFF0A0C14)\nprivate val OmniBgBottom = Color(0xFF05060A)\nprivate val OmniPanel = Color(0xEE141722)\nprivate val OmniPanelStrong = Color(0xFF1B1F2C)\nprivate val OmniTextSoft = Color(0xFFAAB1C4)\nprivate val OmniAccent = Color(0xFF7C8CFF)\nprivate val OmniAccent2 = Color(0xFF5ED8C6)",
    "private val OmniBgTop = Color(0xFF0B0A1D)\nprivate val OmniBgBottom = Color(0xFF050711)\nprivate val OmniPanel = Color(0xE8171930)\nprivate val OmniPanelStrong = Color(0xFF1C1C35)\nprivate val OmniTextSoft = Color(0xFFB2B7CE)\nprivate val OmniAccent = Color(0xFFA58BFF)\nprivate val OmniAccent2 = Color(0xFF74DFFF)",
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    "        val startup = withContext(Dispatchers.IO) {\n            store.load() to Ps1Files.biosFiles(context).size\n        }",
    "        val startup = withContext(Dispatchers.IO) {\n            val loadedGames = store.load()\n            runCatching { OmniAchievements.setCounter(context, \"library_games\", loadedGames.size) }\n            loadedGames to Ps1Files.biosFiles(context).size\n        }",
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    "        scope.launch(Dispatchers.IO) {\n            runCatching { store.save(next) }\n        }",
    "        scope.launch(Dispatchers.IO) {\n            runCatching {\n                store.save(next)\n                OmniAchievements.setCounter(context, \"library_games\", next.size)\n            }\n        }",
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    "    ) {\n        Scaffold(\n",
    "    ) {\n        OmniStarfieldBackground()\n        Scaffold(\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    '''                    NavigationBarItem(
                        selected = screen == OmniHubScreen.SETTINGS,
                        onClick = { screen = OmniHubScreen.SETTINGS },
                        icon = { Text("⚙") },
                        label = { Text("Ajustes") }
                    )''',
    '''                    NavigationBarItem(
                        selected = screen == OmniHubScreen.ACHIEVEMENTS,
                        onClick = { screen = OmniHubScreen.ACHIEVEMENTS },
                        icon = { Text("★") },
                        label = { Text("Conquistas") }
                    )
                    NavigationBarItem(
                        selected = screen == OmniHubScreen.SETTINGS,
                        onClick = { screen = OmniHubScreen.SETTINGS },
                        icon = { Text("⚙") },
                        label = { Text("Ajustes") }
                    )'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    '''                    OmniHubScreen.SETTINGS -> OmniSettings(
                        biosCount = biosCount,
                        onImportBios = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                        onN64Settings = { showN64Settings = true }
                    )''',
    '''                    OmniHubScreen.ACHIEVEMENTS -> AchievementsScreen()
                    OmniHubScreen.SETTINGS -> OmniSettings(
                        biosCount = biosCount,
                        onImportBios = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                        onN64Settings = { showN64Settings = true }
                    )'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV4App.kt",
    'Text("O", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)',
    'Text("★", color = Color(0xFFFFD85A), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)',
)

# N64 micro-polish + achievement hooks. All achievement I/O is off the UI and
# emulation threads. Native governor/pacer source is intentionally untouched.
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    "import com.omnicore.emulator.core.n64.N64Diagnostics\n",
    "import com.omnicore.emulator.achievements.AchievementBanner\nimport com.omnicore.emulator.achievements.OmniAchievements\nimport com.omnicore.emulator.core.n64.N64Diagnostics\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    "    private var lastSurfaceFps = 0f\n",
    "    private var lastSurfaceFps = 0f\n    private var achievementLastProgressAt = 0L\n    private var stableAchievementStreak = 0\n    private var smartAnalogAchievementQueued = false\n    private var stableAchievementQueued = false\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    "                val now = SystemClock.elapsedRealtime()\n                if (telemetry.sampleWindowFrames >= 90",
    "                val now = SystemClock.elapsedRealtime()\n                trackAchievementTelemetry(telemetry, now)\n                if (telemetry.sampleWindowFrames >= 90",
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    "                    if (runOkPolls == 1) {\n                        bootStar.visibility = View.GONE\n",
    "                    if (runOkPolls == 1) {\n                        bootStar.visibility = View.GONE\n                        unlockAchievementAsync(\"n64_first_run\")\n",
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    "            handler.postDelayed(this, if (started) 500L else 220L)\n",
    '''            val nextPollMs = when {
                !started -> 220L
                runOkPolls < 5 -> 350L
                else -> 750L
            }
            handler.postDelayed(this, nextPollMs)
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '''                    item.itemId == MENU_EDIT_DONE -> {
                        controls.setEditMode(false)
                        if (started) N64NativeBridge.setPaused(manualPaused)
                        Toast.makeText(this@N64EmulationActivity, "Layout N64 salvo.", Toast.LENGTH_SHORT).show()
                        true
                    }''',
    '''                    item.itemId == MENU_EDIT_DONE -> {
                        controls.setEditMode(false)
                        if (started) N64NativeBridge.setPaused(manualPaused)
                        unlockAchievementAsync("customizer")
                        Toast.makeText(this@N64EmulationActivity, "Layout N64 salvo.", Toast.LENGTH_SHORT).show()
                        true
                    }'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '''        val queued = N64NativeBridge.saveState(file)
        Toast.makeText(''',
    '''        val queued = N64NativeBridge.saveState(file)
        if (queued) unlockAchievementAsync("save_keeper")
        Toast.makeText('''
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    "    private fun showPerformanceStatus() {\n        val t = N64NativeBridge.telemetry()\n",
    '''    private fun showPerformanceStatus() {
        unlockAchievementAsync("tuner")
        val t = N64NativeBridge.telemetry()
'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    "    private fun showPerformanceStatus() {\n",
    '''    private fun unlockAchievementAsync(id: String) {
        achievementAsync { listOfNotNull(OmniAchievements.unlock(this, id)) }
    }

    private fun achievementAsync(block: () -> List<OmniAchievements.Unlock>) {
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

    private fun showPerformanceStatus() {
'''
)

print("Alpha 17 StarUI + Achievements + micro-polish migration applied")
