package com.omnicore.emulator.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnicore.emulator.core.CoreRegistry
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.core.ps1.Ps1Core
import com.omnicore.emulator.library.RomDetector
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.PerformanceManager
import com.omnicore.emulator.settings.Ps1Settings
import com.omnicore.emulator.storage.GameLibraryStore
import com.omnicore.emulator.storage.Ps1Files
import com.omnicore.emulator.storage.SafGameSource
import java.util.UUID

private enum class HubScreen { LIBRARY, CORES, TUNING }

private val HubPanel = Color(0xEB121526)
private val HubPanelStrong = Color(0xF51A1D32)
private val HubSoft = Color(0xFFADB5CF)
private val HubPurple = Color(0xFF9879FF)
private val HubCyan = Color(0xFF57D8FF)

@Composable
fun OmniCoreV3App() {
    val context = LocalContext.current
    val store = remember { GameLibraryStore(context) }
    var games by remember { mutableStateOf(store.load()) }
    var filter by remember { mutableStateOf<ConsoleSystem?>(null) }
    var screen by remember { mutableStateOf(HubScreen.LIBRARY) }
    var message by remember { mutableStateOf<String?>(null) }
    var importDialog by remember { mutableStateOf(false) }
    var biosCount by remember { mutableIntStateOf(Ps1Files.biosFiles(context).size) }

    fun persist(additions: List<GameEntry>, success: String) {
        if (additions.isEmpty()) return
        games = (games + additions).distinctBy { "${it.uri}|${it.folderUri.orEmpty()}" }
        store.save(games)
        message = success
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val docs = uris.map { SafGameSource.metadata(context, it) }
        val cues = docs.filter { it.extension == "cue" }
        if (cues.isNotEmpty()) {
            val companions = docs.map { it.uri.toString() }
            persist(
                cues.map { cue ->
                    GameEntry(
                        id = UUID.randomUUID().toString(),
                        title = cue.name.substringBeforeLast('.', cue.name),
                        fileName = cue.name,
                        uri = cue.uri.toString(),
                        system = ConsoleSystem.PLAYSTATION_1,
                        sizeBytes = docs.sumOf { it.sizeBytes },
                        companionUris = companions
                    )
                },
                "CUE/BIN adicionado. As faixas serão validadas antes do boot."
            )
            return@rememberLauncherForActivityResult
        }

        val additions = docs.mapNotNull { doc ->
            val system = filter ?: RomDetector.detect(doc.name)
            system?.let {
                GameEntry(
                    id = UUID.randomUUID().toString(),
                    title = doc.name.substringBeforeLast('.', doc.name),
                    fileName = doc.name,
                    uri = doc.uri.toString(),
                    system = it,
                    sizeBytes = doc.sizeBytes
                )
            }
        }
        if (additions.isEmpty()) message = "Nenhum arquivo compatível foi reconhecido."
        else persist(additions, "${additions.size} jogo(s) adicionado(s).")
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val docs = runCatching {
            SafGameSource.listDirectChildren(context, treeUri).filterNot { it.isDirectory }
        }.getOrElse {
            message = it.message ?: "Não consegui ler a pasta selecionada."
            return@rememberLauncherForActivityResult
        }
        val cues = docs.filter { it.extension == "cue" }
        if (cues.isNotEmpty()) {
            persist(
                cues.map { cue ->
                    GameEntry(
                        id = UUID.randomUUID().toString(),
                        title = cue.name.substringBeforeLast('.', cue.name),
                        fileName = cue.name,
                        uri = cue.uri.toString(),
                        system = ConsoleSystem.PLAYSTATION_1,
                        sizeBytes = docs.sumOf { it.sizeBytes },
                        folderUri = treeUri.toString()
                    )
                },
                "Pasta PS1 vinculada com ${cues.size} CUE."
            )
            return@rememberLauncherForActivityResult
        }

        val singles = docs.filter { it.extension in Ps1Core.SINGLE_FILE_EXTENSIONS }
        if (singles.count { it.extension == "bin" } > 1) {
            message = "A pasta contém vários BIN sem CUE. O CUE é necessário para mapear a ordem das faixas."
            return@rememberLauncherForActivityResult
        }
        if (singles.isEmpty()) {
            message = "Não encontrei CUE, CHD, PBP ou outra imagem PS1 compatível na raiz dessa pasta."
            return@rememberLauncherForActivityResult
        }
        persist(
            singles.map { doc ->
                GameEntry(
                    id = UUID.randomUUID().toString(),
                    title = doc.name.substringBeforeLast('.', doc.name),
                    fileName = doc.name,
                    uri = doc.uri.toString(),
                    system = ConsoleSystem.PLAYSTATION_1,
                    sizeBytes = doc.sizeBytes,
                    folderUri = treeUri.toString()
                )
            },
            "${singles.size} jogo(s) PS1 vinculado(s)."
        )
    }

    val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Ps1Files.importBios(context, uri)
                .onSuccess {
                    biosCount = Ps1Files.biosFiles(context).size
                    message = "BIOS importada: ${it.name}"
                }
                .onFailure { message = it.message ?: "Não consegui importar essa BIOS." }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0xFF090A17), Color(0xFF0A0D19), Color(0xFF05060B))
            )
        )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { HubTopBar(screen, games.size) { importDialog = true } },
            bottomBar = {
                NavigationBar(containerColor = Color(0xF20D1020), tonalElevation = 10.dp) {
                    NavigationBarItem(
                        selected = screen == HubScreen.LIBRARY,
                        onClick = { screen = HubScreen.LIBRARY },
                        icon = { Text("▦") },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = screen == HubScreen.CORES,
                        onClick = { screen = HubScreen.CORES },
                        icon = { Text("◉") },
                        label = { Text("Cores") }
                    )
                    NavigationBarItem(
                        selected = screen == HubScreen.TUNING,
                        onClick = { screen = HubScreen.TUNING },
                        icon = { Text("⌁") },
                        label = { Text("Tuning") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    HubScreen.LIBRARY -> HubLibrary(
                        games = games,
                        selected = filter,
                        onFilter = { filter = it },
                        onImport = { importDialog = true },
                        onPlay = { game ->
                            val core = CoreRegistry.forSystem(game.system)
                            if (core == null || !core.isAvailable()) {
                                message = if (game.system == ConsoleSystem.PLAYSTATION_1) {
                                    "Core PS1 não carregado. Instale o APK completo gerado pelo Android Build."
                                } else {
                                    "O core de ${game.system.displayName} ainda está planejado."
                                }
                            } else {
                                core.launch(context, game).exceptionOrNull()?.let {
                                    message = it.message ?: "Falha ao iniciar ${game.title}."
                                }
                            }
                        },
                        onRemove = { game ->
                            games = games.filterNot { it.id == game.id }
                            store.save(games)
                        }
                    )
                    HubScreen.CORES -> HubCores()
                    HubScreen.TUNING -> HubTuning(
                        biosCount = biosCount,
                        gameCount = games.size,
                        onImportBios = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) }
                    )
                }
            }
        }

        if (importDialog) {
            AlertDialog(
                onDismissRequest = { importDialog = false },
                containerColor = HubPanelStrong,
                title = { Text("Adicionar jogo", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Para PS1 em CUE/BIN, escolha a pasta inteira. O OmniCore valida as faixas e mantém o conjunto unido.",
                            color = HubSoft
                        )
                        Button(
                            onClick = { importDialog = false; folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Escolher pasta PS1") }
                        Button(
                            onClick = { importDialog = false; filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29304B))
                        ) { Text("Selecionar arquivos") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Fechar") } }
            )
        }

        message?.let { text ->
            AlertDialog(
                onDismissRequest = { message = null },
                containerColor = HubPanelStrong,
                title = { Text("OmniCore") },
                text = { Text(text, color = HubSoft) },
                confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
            )
        }
    }
}

@Composable
private fun HubTopBar(screen: HubScreen, count: Int, onImport: () -> Unit) {
    Surface(color = Color(0xED0B0E1B), tonalElevation = 9.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(43.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(HubPurple, HubCyan))),
                contentAlignment = Alignment.Center
            ) {
                Text("O", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text("OmniCore", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                Text(
                    when (screen) {
                        HubScreen.LIBRARY -> "$count jogo(s) • Runtime v4"
                        HubScreen.CORES -> "Motores de emulação"
                        HubScreen.TUNING -> "Graphics & Performance Lab"
                    },
                    color = HubSoft,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (screen == HubScreen.LIBRARY) {
                Button(onClick = onImport, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("+ Jogo")
                }
            }
        }
    }
}

@Composable
private fun HubLibrary(
    games: List<GameEntry>,
    selected: ConsoleSystem?,
    onFilter: (ConsoleSystem?) -> Unit,
    onImport: () -> Unit,
    onPlay: (GameEntry) -> Unit,
    onRemove: (GameEntry) -> Unit
) {
    val shown = remember(games, selected) {
        if (selected == null) games else games.filter { it.system == selected }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { EngineHero() }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = selected == null, onClick = { onFilter(null) }, label = { Text("Todos") })
                }
                items(ConsoleSystem.entries) { system ->
                    FilterChip(
                        selected = selected == system,
                        onClick = { onFilter(system) },
                        label = { Text(system.shortName) }
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                HubCard {
                    Text("Sua biblioteca está pronta", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Adicione uma pasta PS1 ou um arquivo compatível. O PS1 é o primeiro motor funcional do OmniCore.",
                        color = HubSoft
                    )
                    Button(onClick = onImport) { Text("Adicionar jogo") }
                }
            }
        } else {
            items(shown, key = { it.id }) { game ->
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0x223B4262), RoundedCornerShape(19.dp)),
                    colors = CardDefaults.cardColors(containerColor = HubPanel),
                    shape = RoundedCornerShape(19.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Box(
                            Modifier.size(60.dp).clip(RoundedCornerShape(17.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF332B66), Color(0xFF17394B)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(game.system.shortName, color = Color.White, fontWeight = FontWeight.Black)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                            Text(game.system.displayName, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                            Text(
                                buildString {
                                    append(game.fileName)
                                    if (game.folderUri != null) append(" • pasta")
                                    if (game.sizeBytes > 0) append(" • ").append(formatHubBytes(game.sizeBytes))
                                },
                                color = Color(0xFF747D9A),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { onRemove(game) }) { Text("Remover") }
                        Button(onClick = { onPlay(game) }) { Text("Jogar") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(5.dp)) }
    }
}

@Composable
private fun EngineHero() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF261F53), Color(0xFF0D3040))),
                RoundedCornerShape(22.dp)
            ).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("PLAYSTATION ENGINE", color = HubCyan, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
                Text("PCSX-ReARMed", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
                Text("CUE/BIN • CHD • PBP • SmartPerf 3 • framebuffer direto", color = HubSoft, style = MaterialTheme.typography.bodySmall)
            }
            AssistChip(onClick = {}, label = { Text(if (NativeBridge.hasPs1Core()) "ONLINE" else "OFFLINE") })
        }
    }
}

@Composable
private fun HubCores() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("Cores", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            Text("Arquitetura multi-core isolada: cada console ganha seu próprio backend e perfil de otimização.", color = HubSoft)
        }
        items(CoreRegistry.all()) { info ->
            val ready = remember(info.id) { CoreRegistry.forSystem(info.system)?.isAvailable() == true }
            val status = when {
                info.state == CoreState.READY && ready -> "Pronto"
                info.state == CoreState.READY -> "Core ausente"
                info.state == CoreState.EXPERIMENTAL -> "Experimental"
                else -> "Planejado"
            }
            HubCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, fontWeight = FontWeight.Bold)
                        Text(info.system.displayName, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                        if (info.state == CoreState.READY) Text(info.version, color = Color(0xFF737C98), style = MaterialTheme.typography.labelSmall)
                    }
                    AssistChip(onClick = {}, label = { Text(status) })
                }
            }
        }
    }
}

@Composable
private fun HubTuning(biosCount: Int, gameCount: Int, onImportBios: () -> Unit) {
    val context = LocalContext.current
    val device = remember { PerformanceManager.profile(context) }
    var perfMode by remember { mutableStateOf(PerformanceManager.readUserMode(context)) }
    var config by remember { mutableStateOf(Ps1Settings.resolve(context)) }

    fun refresh() { config = Ps1Settings.resolve(context) }
    fun saveCustom(next: Ps1Settings.Config) {
        Ps1Settings.saveCustom(context, next.copy(preset = Ps1Settings.Preset.CUSTOM))
        refresh()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Text("Tuning Center", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            Text("Opções reais do core PS1, além do SmartPerf do frontend.", color = HubSoft)
        }
        item {
            HubSection("Presets PS1", config.preset.subtitle) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Ps1Settings.Preset.entries.filter { it != Ps1Settings.Preset.CUSTOM }) { preset ->
                        FilterChip(
                            selected = config.preset == preset,
                            onClick = { Ps1Settings.savePreset(context, preset); refresh() },
                            label = { Text(preset.label) }
                        )
                    }
                    if (config.preset == Ps1Settings.Preset.CUSTOM) {
                        item { AssistChip(onClick = {}, label = { Text("Custom") }) }
                    }
                }
            }
        }
        item {
            HubSection("Gráficos", "Renderer NEON e opções de fidelidade do PCSX-ReARMed.") {
                SettingSwitch("Resolução aprimorada", "Renderiza 3D em resolução interna maior.", config.enhancedResolution) {
                    saveCustom(config.copy(enhancedResolution = it))
                }
                SettingSwitch("Speed hack de resolução", "Acelera enhanced resolution com menor compatibilidade.", config.enhancedSpeedHack) {
                    saveCustom(config.copy(enhancedSpeedHack = it))
                }
                SettingSwitch("Ajuste de texturas", "Corrige texturas em enhanced resolution.", config.textureAdjustment) {
                    saveCustom(config.copy(textureAdjustment = it))
                }
                SettingSwitch("Dithering PS1", "Mantém gradações e aparência próximas ao hardware original.", config.dithering) {
                    saveCustom(config.copy(dithering = it))
                }
                SettingSwitch("GPU em thread", "Executa comandos gráficos em thread auxiliar.", config.threadedGpu) {
                    saveCustom(config.copy(threadedGpu = it))
                }
            }
        }
        item {
            HubSection("Performance e áudio", "Controles de estabilidade para aparelhos com diferentes limites térmicos.") {
                SettingSwitch("SPU em thread", "Move parte da emulação de áudio para outra thread.", config.threadedSpu) {
                    saveCustom(config.copy(threadedSpu = it))
                }
                SettingSwitch("Frameskip automático", "Usa o estado real do buffer de áudio para decidir quando aliviar vídeo.", config.frameskipAuto) {
                    saveCustom(config.copy(frameskipAuto = it))
                }
                Text("Interpolação", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("simple" to "Simples", "gaussian" to "Gaussiana", "cubic" to "Cúbica", "off" to "Off")) { option ->
                        FilterChip(
                            selected = config.interpolation == option.first,
                            onClick = { saveCustom(config.copy(interpolation = option.first)) },
                            label = { Text(option.second) }
                        )
                    }
                }
                Text("CD read-ahead", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0, 12, 16, 32, 64)) { value ->
                        FilterChip(
                            selected = config.cdReadAhead == value,
                            onClick = { saveCustom(config.copy(cdReadAhead = value)) },
                            label = { Text(value.toString()) }
                        )
                    }
                }
            }
        }
        item {
            HubSection("Controle", "Analógico esquerdo touch real + D-pad independente.") {
                SettingSwitch("DualShock / analógico", "Ativa o tipo DualShock no core e os eixos analógicos.", config.dualShock) {
                    Ps1Settings.saveDualShock(context, it)
                    refresh()
                }
            }
        }
        item {
            HubSection("SmartPerf 3", device.summary) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PerformanceManager.UserMode.entries) { mode ->
                        FilterChip(
                            selected = perfMode == mode,
                            onClick = {
                                perfMode = mode
                                PerformanceManager.saveUserMode(context, mode)
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text("AAudio adaptativo • frame pacing • ADPF • controle térmico • zero-copy quando disponível", color = HubSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            HubSection("Sistema", "OmniCore 0.3.0 • $gameCount jogo(s) na biblioteca") {
                Text("Runtime: ${NativeBridge.runtimeVersion()}", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("PS1: ${if (NativeBridge.hasPs1Core()) "PCSX-ReARMed pronto" else "core indisponível"}", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("BIOS: $biosCount arquivo(s) .bin", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onImportBios) { Text("Importar BIOS próprio") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HubCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HubPanel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun HubSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    HubCard {
        Text(title, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = HubSoft, style = MaterialTheme.typography.bodySmall)
        content()
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = HubSoft, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatHubBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
