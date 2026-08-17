package com.omnicore.emulator.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
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

private enum class V3Screen { LIBRARY, CORES, TUNING }

private val V3Bg = Color(0xFF070812)
private val V3Panel = Color(0xDD111426)
private val V3Panel2 = Color(0xE61A1D33)
private val V3Purple = Color(0xFF9A7CFF)
private val V3Cyan = Color(0xFF62DBFF)
private val V3TextSoft = Color(0xFFADB3CB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniCoreV3App() {
    val context = LocalContext.current
    val store = remember { GameLibraryStore(context) }
    var games by remember { mutableStateOf(store.load()) }
    var systemFilter by remember { mutableStateOf<ConsoleSystem?>(null) }
    var screen by remember { mutableStateOf(V3Screen.LIBRARY) }
    var message by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var biosCount by remember { mutableIntStateOf(Ps1Files.biosFiles(context).size) }

    fun addGames(additions: List<GameEntry>, success: String) {
        if (additions.isEmpty()) return
        games = (games + additions).distinctBy { "${it.uri}|${it.folderUri.orEmpty()}" }
        store.save(games)
        message = success
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val docs = uris.map { SafGameSource.metadata(context, it) }
        val cues = docs.filter { it.extension == "cue" }
        if (cues.isNotEmpty()) {
            val companions = docs.map { it.uri.toString() }
            addGames(
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
                "CUE/BIN vinculado. O OmniCore vai validar todas as faixas antes do boot."
            )
            return@rememberLauncherForActivityResult
        }

        val additions = mutableListOf<GameEntry>()
        docs.forEach { doc ->
            val detected = systemFilter ?: RomDetector.detect(doc.name)
            if (detected != null) {
                additions += GameEntry(
                    id = UUID.randomUUID().toString(),
                    title = doc.name.substringBeforeLast('.', doc.name),
                    fileName = doc.name,
                    uri = doc.uri.toString(),
                    system = detected,
                    sizeBytes = doc.sizeBytes
                )
            }
        }
        if (additions.isEmpty()) message = "Nenhum arquivo compatível foi reconhecido."
        else addGames(additions, "${additions.size} jogo(s) adicionado(s).")
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val docs = runCatching { SafGameSource.listDirectChildren(context, treeUri).filterNot { it.isDirectory } }
            .getOrElse {
                message = it.message ?: "Não consegui ler essa pasta."
                return@rememberLauncherForActivityResult
            }
        val cues = docs.filter { it.extension == "cue" }
        if (cues.isNotEmpty()) {
            addGames(
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
        if (singles.isEmpty()) {
            message = "Não encontrei CUE, CHD, PBP ou imagem PS1 suportada na raiz da pasta."
            return@rememberLauncherForActivityResult
        }
        if (singles.count { it.extension == "bin" } > 1) {
            message = "Há vários BIN sem CUE. O CUE é necessário para mapear corretamente as faixas."
            return@rememberLauncherForActivityResult
        }
        addGames(
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
                .onFailure { message = it.message ?: "Falha ao importar BIOS." }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF090A18), Color(0xFF0C0E1C), Color(0xFF05060C))
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { V3TopBar(screen, games.size) { showImportDialog = true } },
            bottomBar = {
                NavigationBar(containerColor = Color(0xF20D1020), tonalElevation = 8.dp) {
                    NavigationBarItem(
                        selected = screen == V3Screen.LIBRARY,
                        onClick = { screen = V3Screen.LIBRARY },
                        icon = { Text("▦") },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = screen == V3Screen.CORES,
                        onClick = { screen = V3Screen.CORES },
                        icon = { Text("◉") },
                        label = { Text("Cores") }
                    )
                    NavigationBarItem(
                        selected = screen == V3Screen.TUNING,
                        onClick = { screen = V3Screen.TUNING },
                        icon = { Text("⌁") },
                        label = { Text("Tuning") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    V3Screen.LIBRARY -> V3Library(
                        games = games,
                        selectedSystem = systemFilter,
                        onSystem = { systemFilter = it },
                        onImport = { showImportDialog = true },
                        onPlay = { game ->
                            val core = CoreRegistry.forSystem(game.system)
                            if (core == null || !core.isAvailable()) {
                                message = if (game.system == ConsoleSystem.PLAYSTATION_1) {
                                    "O APK não contém um core PS1 válido. Instale o build completo publicado pelo Actions."
                                } else "O core de ${game.system.displayName} ainda está planejado."
                            } else {
                                core.launch(context, game).exceptionOrNull()?.let { message = it.message ?: "Falha ao iniciar." }
                            }
                        },
                        onRemove = { game ->
                            games = games.filterNot { it.id == game.id }
                            store.save(games)
                        }
                    )
                    V3Screen.CORES -> V3Cores()
                    V3Screen.TUNING -> V3Tuning(
                        biosCount = biosCount,
                        gameCount = games.size,
                        onImportBios = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) }
                    )
                }
            }
        }

        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                containerColor = V3Panel2,
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Fechar") } },
                title = { Text("Adicionar à biblioteca", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Para PS1 em CUE/BIN, use a pasta inteira. O OmniCore preserva o conjunto de faixas e valida referências antes do boot.", color = V3TextSoft)
                        Button(
                            onClick = { showImportDialog = false; folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Escolher pasta PS1") }
                        Button(
                            onClick = { showImportDialog = false; filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF272C49))
                        ) { Text("Selecionar arquivos") }
                    }
                }
            )
        }

        message?.let { text ->
            AlertDialog(
                onDismissRequest = { message = null },
                containerColor = V3Panel2,
                confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
                title = { Text("OmniCore") },
                text = { Text(text, color = V3TextSoft) }
            )
        }
    }
}

@Composable
private fun V3TopBar(screen: V3Screen, games: Int, onImport: () -> Unit) {
    Surface(color = Color(0xE80B0E1B), tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(V3Purple, V3Cyan))),
                contentAlignment = Alignment.Center
            ) { Text("O", color = Color.White, fontWeight = FontWeight.Black) }
            Column(Modifier.weight(1f)) {
                Text("OmniCore", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                Text(
                    when (screen) {
                        V3Screen.LIBRARY -> "$games jogo(s) • runtime v4"
                        V3Screen.CORES -> "Motores de emulação"
                        V3Screen.TUNING -> "Gráficos, desempenho e sistema"
                    },
                    color = V3TextSoft,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (screen == V3Screen.LIBRARY) {
                Button(onClick = onImport, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("+ Jogo")
                }
            }
        }
    }
}

@Composable
private fun V3Library(
    games: List<GameEntry>,
    selectedSystem: ConsoleSystem?,
    onSystem: (ConsoleSystem?) -> Unit,
    onImport: () -> Unit,
    onPlay: (GameEntry) -> Unit,
    onRemove: (GameEntry) -> Unit
) {
    val shown = remember(games, selectedSystem) {
        if (selectedSystem == null) games else games.filter { it.system == selectedSystem }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { V3Hero() }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = selectedSystem == null, onClick = { onSystem(null) }, label = { Text("Todos") }) }
                items(ConsoleSystem.entries) { system ->
                    FilterChip(selected = selectedSystem == system, onClick = { onSystem(system) }, label = { Text(system.shortName) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = V3Panel),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Biblioteca pronta para receber jogos", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text("Importe uma pasta PS1 ou um arquivo único compatível. Outros sistemas entram conforme os cores forem integrados.", color = V3TextSoft)
                        Button(onClick = onImport) { Text("Adicionar jogo") }
                    }
                }
            }
        } else {
            items(shown, key = { it.id }) { game ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = V3Panel),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0x223C4264), RoundedCornerShape(18.dp)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Box(
                            Modifier.size(58.dp).clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF332B66), Color(0xFF19374A)))),
                            contentAlignment = Alignment.Center
                        ) { Text(game.system.shortName, fontWeight = FontWeight.Black, color = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(game.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(game.system.displayName, color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
                            val detail = buildString {
                                append(game.fileName)
                                if (game.folderUri != null) append(" • pasta vinculada")
                                if (game.sizeBytes > 0) append(" • ").append(v3FormatBytes(game.sizeBytes))
                            }
                            Text(detail, color = Color(0xFF747C99), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onRemove(game) }) { Text("Remover") }
                        Button(onClick = { onPlay(game) }) { Text("Jogar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun V3Hero() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xFF241D4D), Color(0xFF102839))), RoundedCornerShape(22.dp))
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PLAYSTATION ENGINE", color = V3Cyan, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
                Text("PCSX-ReARMed • Runtime v4", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
                Text("CUE/BIN • CHD • PBP • framebuffer direto • SmartPerf 3", color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
            }
            AssistChip(onClick = {}, label = { Text(if (NativeBridge.hasPs1Core()) "ONLINE" else "OFFLINE") })
        }
    }
}

@Composable
private fun V3Cores() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Cores", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("Cada sistema fica isolado atrás da mesma camada de runtime. Isso evita conflitos e facilita otimizações por core.", color = V3TextSoft)
        }
        items(CoreRegistry.all()) { info ->
            val available = remember(info.id) { CoreRegistry.forSystem(info.system)?.isAvailable() == true }
            val status = when {
                info.state == CoreState.READY && available -> "Pronto"
                info.state == CoreState.READY -> "Binário ausente"
                info.state == CoreState.EXPERIMENTAL -> "Experimental"
                else -> "Planejado"
            }
            Card(colors = CardDefaults.cardColors(containerColor = V3Panel), shape = RoundedCornerShape(17.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, fontWeight = FontWeight.Bold)
                        Text(info.system.displayName, color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
                        if (info.state == CoreState.READY) Text(info.version, color = Color(0xFF737B98), style = MaterialTheme.typography.labelSmall)
                    }
                    AssistChip(onClick = {}, label = { Text(status) })
                }
            }
        }
    }
}

@Composable
private fun V3Tuning(biosCount: Int, gameCount: Int, onImportBios: () -> Unit) {
    val context = LocalContext.current
    val device = remember { PerformanceManager.profile(context) }
    var performanceMode by remember { mutableStateOf(PerformanceManager.readUserMode(context)) }
    var config by remember { mutableStateOf(Ps1Settings.resolve(context)) }

    fun refresh() { config = Ps1Settings.resolve(context) }
    fun custom(next: Ps1Settings.Config) {
        Ps1Settings.saveCustom(context, next.copy(preset = Ps1Settings.Preset.CUSTOM))
        refresh()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Text("Tuning Center", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("Configurações reais do core, sem tweaks permanentes no Android.", color = V3TextSoft)
        }

        item {
            V3Section("Preset PS1", "O preset define uma base segura; qualquer ajuste avançado muda para Custom.") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Ps1Settings.Preset.entries.filter { it != Ps1Settings.Preset.CUSTOM }) { preset ->
                        FilterChip(
                            selected = config.preset == preset,
                            onClick = {
                                Ps1Settings.savePreset(context, preset)
                                refresh()
                            },
                            label = { Text(preset.label) }
                        )
                    }
                    if (config.preset == Ps1Settings.Preset.CUSTOM) {
                        item { AssistChip(onClick = {}, label = { Text("Custom") }) }
                    }
                }
                Text(config.preset.subtitle, color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            V3Section("Gráficos", "Opções nativas do PCSX-ReARMed/NEON.") {
                V3SwitchRow("Resolução aprimorada", "Renderiza 3D em resolução interna maior; exige mais GPU/CPU.", config.enhancedResolution) {
                    custom(config.copy(enhancedResolution = it))
                }
                V3SwitchRow("Speed hack da resolução", "Acelera enhanced resolution, mas pode causar glitches.", config.enhancedSpeedHack) {
                    custom(config.copy(enhancedSpeedHack = it))
                }
                V3SwitchRow("Ajuste de texturas", "Corrige artefatos em resolução aprimorada.", config.textureAdjustment) {
                    custom(config.copy(textureAdjustment = it))
                }
                V3SwitchRow("Dithering PS1", "Mantém o padrão de cores original; desligar reduz um pouco a carga.", config.dithering) {
                    custom(config.copy(dithering = it))
                }
                V3SwitchRow("GPU em thread", "Processa comandos gráficos em thread auxiliar.", config.threadedGpu) {
                    custom(config.copy(threadedGpu = it))
                }
            }
        }

        item {
            V3Section("Desempenho e áudio", "Ajustes que ajudam estabilidade e frame pacing.") {
                V3SwitchRow("SPU em thread", "Pode aliviar CPU principal; alguns jogos podem apresentar áudio irregular.", config.threadedSpu) {
                    custom(config.copy(threadedSpu = it))
                }
                V3SwitchRow("Frameskip automático", "Usa ocupação real do buffer de áudio para decidir quando pular frames.", config.frameskipAuto) {
                    custom(config.copy(frameskipAuto = it))
                }
                Text("Interpolação de áudio", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("simple" to "Simples", "gaussian" to "Gaussiana", "cubic" to "Cúbica", "off" to "Desligada")) { item ->
                        FilterChip(selected = config.interpolation == item.first, onClick = { custom(config.copy(interpolation = item.first)) }, label = { Text(item.second) })
                    }
                }
                Text("CD read-ahead", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0, 12, 16, 32, 64)) { value ->
                        FilterChip(selected = config.cdReadAhead == value, onClick = { custom(config.copy(cdReadAhead = value)) }, label = { Text(value.toString()) })
                    }
                }
            }
        }

        item {
            V3Section("Controle", "O touch agora envia eixo analógico real ao core.") {
                V3SwitchRow("DualShock / analógico", "Ativa o tipo de controle DualShock e o analógico esquerdo.", config.dualShock) {
                    Ps1Settings.saveDualShock(context, it)
                    refresh()
                }
            }
        }

        item {
            V3Section("SmartPerf", device.summary) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PerformanceManager.UserMode.entries) { mode ->
                        FilterChip(
                            selected = performanceMode == mode,
                            onClick = {
                                performanceMode = mode
                                PerformanceManager.saveUserMode(context, mode)
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text("Frame pacing, AAudio, buffer adaptativo, ADPF e proteção térmica são ajustados durante a sessão.", color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            V3Section("Sistema", "OmniCore 0.3.0 • biblioteca: $gameCount jogo(s)") {
                Text("Runtime: ${NativeBridge.runtimeVersion()}", color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
                Text("Core PS1: ${if (NativeBridge.hasPs1Core()) "PCSX-ReARMed pronto" else "não carregado"}", color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
                Text("BIOS: $biosCount arquivo(s) .bin", color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onImportBios) { Text("Importar BIOS próprio") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun V3Section(title: String, subtitle: String, content: @Composable Column.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = V3Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(title, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun V3SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = V3TextSoft, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun v3FormatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
