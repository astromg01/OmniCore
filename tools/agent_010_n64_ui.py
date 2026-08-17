from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing pattern: {label}")
    return text.replace(old, new, 1)


def replace_regex(text: str, pattern: str, new: str, label: str) -> str:
    updated, count = re.subn(pattern, new, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"regex pattern count {count}: {label}")
    return updated


gradle_path = Path("app/build.gradle.kts")
gradle = gradle_path.read_text()
gradle = replace_once(
    gradle,
    'versionCode = 15\n        versionName = "0.9.4"',
    'versionCode = 16\n        versionName = "0.10.0"',
    "version bump",
)
gradle_path.write_text(gradle)

ui_path = Path("app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt")
ui = ui_path.read_text()
ui = replace_once(
    ui,
    'import com.omnicore.emulator.update.UpdateManager\n',
    'import com.omnicore.emulator.update.UpdateManager\nimport com.omnicore.emulator.ui.n64.N64SettingsDialog\n',
    "N64 settings import",
)
ui = replace_once(
    ui,
    '"Para PS1 em CUE/BIN, escolha a pasta inteira. O OmniCore valida as faixas e mantém o conjunto unido."',
    '"PS1 CUE/BIN: escolha a pasta inteira. Nintendo 64: use Selecionar arquivos para .z64, .n64 ou .v64."',
    "import dialog guidance",
)

hub_cores = '''@Composable
private fun HubCores() {
    var showN64Settings by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("Cores", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            Text("Cada console usa backend, armazenamento, perfil de performance e configuração próprios.", color = HubSoft)
        }
        items(CoreRegistry.all()) { info ->
            val ready = CoreRegistry.forSystem(info.system)?.isAvailable() == true
            val status = when {
                info.state == CoreState.READY && ready -> "Pronto"
                info.state == CoreState.READY -> "Core ausente"
                info.state == CoreState.EXPERIMENTAL && ready -> "Core integrado"
                info.state == CoreState.EXPERIMENTAL -> "Experimental"
                else -> "Planejado"
            }
            HubCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, fontWeight = FontWeight.Bold)
                        Text(info.system.displayName, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                        if (info.state != CoreState.PLANNED) {
                            Text(info.version, color = Color(0xFF737C98), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    AssistChip(onClick = {}, label = { Text(status) })
                }
                if (info.system == ConsoleSystem.NINTENDO_64) {
                    Text(
                        "Núcleo e runtime independentes do PS1. O primeiro alvo é GLES3 + GLideN64 com perfil conservador.",
                        color = HubSoft,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { showN64Settings = true }) { Text("Configurar N64") }
                }
            }
        }
    }

    if (showN64Settings) {
        N64SettingsDialog(onDismiss = { showN64Settings = false })
    }
}

@Composable
private fun HubTuning'''
ui = replace_regex(
    ui,
    r'@Composable\nprivate fun HubCores\(\) \{.*?\n\}\n\n@Composable\nprivate fun HubTuning',
    hub_cores,
    "HubCores",
)
ui = replace_once(
    ui,
    'Text("Tuning Center", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)\n            Text("Opções reais do core PS1, além do SmartPerf do frontend.", color = HubSoft)',
    'Text("PlayStation Tuning", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)\n            Text("Configurações exclusivas do backend PS1. Nintendo 64 é configurado separadamente em Cores.", color = HubSoft)',
    "PS1 tuning title",
)
ui_path.write_text(ui)
