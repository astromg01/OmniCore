package com.omnicore.emulator.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.omnicore.emulator.storage.GameLibraryStore
import com.omnicore.emulator.storage.Ps1Files
import com.omnicore.emulator.storage.SafGameSource
import java.util.UUID

private enum class Screen { LIBRARY, CORES, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniCoreApp() {
    val context = LocalContext.current
    val store = remember { GameLibraryStore(context) }
    var games by remember { mutableStateOf(store.load()) }
    var systemFilter by remember { mutableStateOf<ConsoleSystem?>(null) }
    var screen by remember { mutableStateOf(Screen.LIBRARY) }
    var message by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var biosCount by remember { mutableIntStateOf(Ps1Files.biosFiles(context).size) }

    fun addGames(additions: List<GameEntry>, successMessage: String) {
        if (additions.isEmpty()) return
        games = (games + additions).distinctBy { "${it.uri}|${it.folderUri.orEmpty()}" }
        store.save(games)
        message = successMessage
    }

    val gamePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val docs = uris.map { SafGameSource.metadata(context, it) }
        val cueDocs = docs.filter { it.extension == "cue" }
        val additions = mutableListOf<GameEntry>()

        if (cueDocs.isNotEmpty()) {
            val companions = docs.map { it.uri.toString() }
            cueDocs.forEach { cue ->
                additions += GameEntry(
                    id = UUID.randomUUID().toString(),
                    title = cue.name.substringBeforeLast('.', cue.name),
                    fileName = cue.name,
                    uri = cue.uri.toString(),
                    system = ConsoleSystem.PLAYSTATION_1,
                    sizeBytes = docs.sumOf { it.sizeBytes },
                    companionUris = companions
                )
            }
            addGames(additions, "${additions.size} jogo(s) CUE/BIN adicionado(s).")
            return@rememberLauncherForActivityResult
        }

        val bins = docs.filter { it.extension == "bin" }
        if (systemFilter == ConsoleSystem.PLAYSTATION_1 && bins.size > 1) {
            message = "Foram encontrados vários BIN sem CUE. Use Importar pasta PS1 ou selecione o CUE junto com todas as faixas BIN."
            return@rememberLauncherForActivityResult
        }

        docs.forEach { doc ->
            val detected = systemFilter ?: RomDetector.detect(doc.name)
            if (detected == null) {
                message = "Não consegui identificar ${doc.name}. Selecione o console antes de importar formatos ambíguos."
            } else {
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
        addGames(additions, "${additions.size} jogo(s) adicionado(s).")
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val docs = runCatching { SafGameSource.listDirectChildren(context, treeUri).filterNot { it.isDirectory } }
            .getOrElse { error ->
                message = error.message ?: "Não consegui ler a pasta selecionada."
                return@rememberLauncherForActivityResult
            }
        val cueDocs = docs.filter { it.extension == "cue" }
        val additions = mutableListOf<GameEntry>()

        if (cueDocs.isNotEmpty()) {
            cueDocs.forEach { cue ->
                additions += GameEntry(
                    id = UUID.randomUUID().toString(),
                    title = cue.name.substringBeforeLast('.', cue.name),
                    fileName = cue.name,
                    uri = cue.uri.toString(),
                    system = ConsoleSystem.PLAYSTATION_1,
                    sizeBytes = docs.sumOf { it.sizeBytes },
                    folderUri = treeUri.toString()
                )
            }
            addGames(additions, "Pasta PS1 importada: ${additions.size} CUE encontrado(s).")
            return@rememberLauncherForActivityResult
        }

        val singleFiles = docs.filter { it.extension in Ps1Core.SINGLE_FILE_EXTENSIONS }
        val bins = singleFiles.filter { it.extension == "bin" }
        if (bins.size > 1) {
            message = "Essa pasta tem várias faixas BIN, mas nenhum CUE. O CUE é necessário para saber a ordem das faixas."
            return@rememberLauncherForActivityResult
        }

        singleFiles.forEach { doc ->
            additions += GameEntry(
                id = UUID.randomUUID().toString(),
                title = doc.name.substringBeforeLast('.', doc.name),
                fileName = doc.name,
                uri = doc.uri.toString(),
                system = ConsoleSystem.PLAYSTATION_1,
                sizeBytes = doc.sizeBytes,
                folderUri = treeUri.toString()
            )
        }
        if (additions.isEmpty()) {
            message = "Não encontrei CUE, BIN, CHD ou outra imagem PS1 compatível na raiz dessa pasta."
        } else {
            addGames(additions, "${additions.size} jogo(s) PS1 adicionado(s) pela pasta.")
        }
    }

    val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Ps1Files.importBios(context, uri)
                .onSuccess { file ->
                    biosCount = Ps1Files.biosFiles(context).size
                    message = "BIOS PS1 importado: ${file.name}"
                }
                .onFailure { error ->
                    message = error.message ?: "Não consegui importar o BIOS."
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OmniCore", fontWeight = FontWeight.Black)
                        Text("Universal Emulator Hub • PS1 CUE/BIN", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    if (screen == Screen.LIBRARY) {
                        TextButton(onClick = { showImportDialog = true }) { Text("+ Importar") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.LIBRARY,
                    onClick = { screen = Screen.LIBRARY },
                    icon = { Text("▦") },
                    label = { Text("Biblioteca") }
                )
                NavigationBarItem(
                    selected = screen == Screen.CORES,
                    onClick = { screen = Screen.CORES },
                    icon = { Text("◉") },
                    label = { Text("Cores") }
                )
                NavigationBarItem(
                    selected = screen == Screen.SETTINGS,
                    onClick = { screen = Screen.SETTINGS },
                    icon = { Text("⚙") },
                    label = { Text("Ajustes") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(
                    games = games,
                    selectedSystem = systemFilter,
                    onSystemSelected = { systemFilter = it },
                    onImport = { showImportDialog = true },
                    onPlay = { game ->
                        val core = CoreRegistry.forSystem(game.system)
                        if (core == null || !core.isAvailable()) {
                            message = if (game.system == ConsoleSystem.PLAYSTATION_1) {
                                "O backend PS1 está integrado ao projeto, mas o binário PCSX-ReARMed não está presente neste APK. Use o build completo do GitHub Actions."
                            } else {
                                "O core de ${game.system.displayName} ainda está na fila de integração."
                            }
                        } else {
                            core.launch(context, game).exceptionOrNull()?.let { error ->
                                message = error.message ?: "Falha ao iniciar ${game.title}."
                            }
                        }
                    },
                    onRemove = { game ->
                        games = games.filterNot { it.id == game.id }
                        store.save(games)
                    }
                )

                Screen.CORES -> CoresScreen()

                Screen.SETTINGS -> SettingsScreen(
                    gameCount = games.size,
                    biosCount = biosCount,
                    onImportBios = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) }
                )
            }

            if (showImportDialog) {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showImportDialog = false }) { Text("Cancelar") }
                    },
                    title = { Text("Importar jogo") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Para jogos PS1 em CUE/BIN, importar a pasta é o método recomendado.")
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    showImportDialog = false
                                    folderPicker.launch(null)
                                }
                            ) { Text("Importar pasta PS1") }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    showImportDialog = false
                                    gamePicker.launch(arrayOf("*/*"))
                                }
                            ) { Text("Selecionar arquivos") }
                            Text(
                                "Se usar arquivos, selecione o .CUE e todas as faixas .BIN juntos.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
            }

            message?.let { msg ->
                AlertDialog(
                    onDismissRequest = { message = null },
                    confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
                    title = { Text("OmniCore") },
                    text = { Text(msg) }
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    games: List<GameEntry>,
    selectedSystem: ConsoleSystem?,
    onSystemSelected: (ConsoleSystem?) -> Unit,
    onImport: () -> Unit,
    onPlay: (GameEntry) -> Unit,
    onRemove: (GameEntry) -> Unit
) {
    val shown = remember(games, selectedSystem) {
        if (selectedSystem == null) games else games.filter { it.system == selectedSystem }
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedSystem == null,
                    onClick = { onSystemSelected(null) },
                    label = { Text("Todos") }
                )
            }
            items(ConsoleSystem.entries) { system ->
                FilterChip(
                    selected = selectedSystem == system,
                    onClick = { onSystemSelected(system) },
                    label = { Text(system.shortName) }
                )
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Sua biblioteca está vazia", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (selectedSystem == null) {
                            "Escolha um console acima ou importe seus próprios arquivos de jogo."
                        } else {
                            "Importe um jogo de ${selectedSystem.displayName}. CUE/BIN do PS1 pode ser adicionado escolhendo a pasta completa."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onImport) { Text("Importar jogos") }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { game ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                                    Text(game.system.shortName, fontWeight = FontWeight.Black)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                Text(game.system.displayName, style = MaterialTheme.typography.bodySmall)
                                Text(game.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                if (game.folderUri != null) Text("Pasta vinculada", style = MaterialTheme.typography.labelSmall)
                                if (game.sizeBytes > 0) Text(formatBytes(game.sizeBytes), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onRemove(game) }) { Text("Remover") }
                            Button(onClick = { onPlay(game) }) { Text("Jogar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoresScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Núcleos de emulação", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("PS1 já suporta arquivos únicos e conjuntos CUE/BIN por pasta; os demais cores continuam isolados atrás da mesma API.")
            Spacer(Modifier.height(8.dp))
        }
        items(CoreRegistry.all()) { info ->
            val available = remember(info.id) { CoreRegistry.forSystem(info.system)?.isAvailable() == true }
            val status = when {
                info.state == CoreState.READY && available -> "Pronto"
                info.state == CoreState.READY -> "Build sem core"
                info.state == CoreState.EXPERIMENTAL -> "Experimental"
                else -> "Planejado"
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, fontWeight = FontWeight.Bold)
                        Text(info.system.displayName, style = MaterialTheme.typography.bodySmall)
                        if (info.state == CoreState.READY) Text(info.version, style = MaterialTheme.typography.labelSmall)
                    }
                    AssistChip(onClick = {}, label = { Text(status) })
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    gameCount: Int,
    biosCount: Int,
    onImportBios: () -> Unit
) {
    val context = LocalContext.current
    val deviceProfile = remember { PerformanceManager.profile(context) }
    var performanceMode by remember { mutableStateOf(PerformanceManager.readUserMode(context)) }
    val runtimeConfig = remember(performanceMode) {
        PerformanceManager.resolve(
            performanceMode,
            deviceProfile,
            PerformanceManager.currentThermalStatus(context)
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Sistema", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OmniCore 0.2.1", fontWeight = FontWeight.Bold)
                    Text("Biblioteca: $gameCount jogo(s)")
                    Text("Runtime nativo: ${NativeBridge.runtimeVersion()}")
                    Text("PS1: ${if (NativeBridge.hasPs1Core()) "PCSX-ReARMed pronto" else "core não empacotado"}")
                    Text("PS1 multi-track: CUE/BIN + pasta SAF")
                    Text("Arquitetura: Kotlin/Compose + C++/JNI + frontend libretro")
                }
            }
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Otimizador inteligente", fontWeight = FontWeight.Bold)
                    Text(deviceProfile.summary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Perfil atual: ${runtimeConfig.policy.label} • ${runtimeConfig.reason}",
                        style = MaterialTheme.typography.bodySmall
                    )
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
                    Text(
                        "No modo Inteligente o OmniCore adapta latência de áudio, frame pacing e política de CPU ao hardware e à temperatura do aparelho. Nenhuma alteração permanente é feita no Android.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BIOS do PlayStation", fontWeight = FontWeight.Bold)
                    Text("$biosCount arquivo(s) .bin importado(s). O PCSX-ReARMed também consegue usar BIOS HLE quando nenhum BIOS real é fornecido.")
                    Button(onClick = onImportBios) { Text("Importar BIOS próprio") }
                }
            }
        }

        item {
            Text(
                "Nenhum jogo, BIOS, firmware ou chave proprietária é incluído no projeto.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
