package com.omnicore.emulator.ui.ps2

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnicore.emulator.performance.PS2SmartPerf
import com.omnicore.emulator.settings.PS2BiosManager
import com.omnicore.emulator.settings.PS2InputSettings
import com.omnicore.emulator.settings.PS2Settings

/**
 * Lightweight PS2 settings surface. The PCSX2 emucore itself is loaded only by
 * the isolated :ps2 process when a game starts.
 */
@Composable
fun PS2SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val envelope = remember { PS2SmartPerf.envelope(context) }
    val hasVulkan = remember {
        Build.VERSION.SDK_INT >= 24 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    }
    var config by remember { mutableStateOf(PS2Settings.resolve(context)) }
    var input by remember { mutableStateOf(PS2InputSettings.resolve(context)) }
    var bios by remember { mutableStateOf(PS2BiosManager.read(context)) }

    val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) bios = PS2BiosManager.save(context, uri)
    }

    fun refreshCore() {
        config = PS2Settings.resolve(context)
    }

    fun saveCore(next: PS2Settings.Config) {
        PS2Settings.saveCustom(context, next)
        refreshCore()
    }

    fun saveInput(next: PS2InputSettings.Config) {
        PS2InputSettings.save(context, next)
        input = PS2InputSettings.resolve(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("PlayStation 2", fontWeight = FontWeight.Black)
                Text("PCSX2 / ARMSX2 • runtime isolado :ps2", style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Perfil Android", fontWeight = FontWeight.Bold)
                            Text(
                                "${envelope.processors} threads lógicas • memória ${envelope.memoryClassMiB} MiB",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        AssistChip(onClick = {}, label = { Text(if (hasVulkan) "VULKAN HW" else "OPENGL") })
                    }
                }

                item {
                    Text("Preset PS2", fontWeight = FontWeight.Bold)
                    Text(config.preset.subtitle, style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PS2Settings.Preset.entries.filter { it != PS2Settings.Preset.CUSTOM }) { preset ->
                            FilterChip(
                                selected = config.preset == preset,
                                onClick = {
                                    PS2Settings.savePreset(context, preset)
                                    refreshCore()
                                },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                    Text(
                        "Na Alpha 6 o SmartPerf é somente observador: ele mede FPS/pressão, mas não troca renderer, limiter ou resolução sozinho.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }

                item {
                    Text("Renderer", fontWeight = FontWeight.Bold)
                    Text(
                        "Automático delega a escolha ao próprio PCSX2. OmniCore não força Vulkan/OpenGL por telemetria e não grava mudanças para o próximo boot.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PS2Settings.RendererMode.entries) { renderer ->
                            FilterChip(
                                selected = config.renderer == renderer,
                                enabled = renderer != PS2Settings.RendererMode.VULKAN || hasVulkan,
                                onClick = { saveCore(config.copy(renderer = renderer)) },
                                label = { Text(renderer.label) }
                            )
                        }
                    }
                }

                item {
                    Text("Resolução interna", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PS2Settings.InternalResolution.entries) { resolution ->
                            FilterChip(
                                selected = config.internalResolution == resolution,
                                onClick = { saveCore(config.copy(internalResolution = resolution)) },
                                label = { Text(resolution.label) }
                            )
                        }
                    }
                    Text(
                        "1× continua sendo o baseline. 2×/4× só entram por escolha explícita e não são reduzidos automaticamente para esconder gargalo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                item {
                    Text("BIOS PS2 do usuário", fontWeight = FontWeight.Bold)
                    Text(
                        "O PCSX2 exige uma BIOS real fornecida por você. OmniCore valida ROMDIR/RESET/ROMVER, guarda somente sua referência e copia o dump para o armazenamento privado do processo PS2 na hora do boot.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                            label = { Text(if (bios == null) "Selecionar BIOS" else "Trocar BIOS") }
                        )
                        if (bios != null) {
                            AssistChip(
                                onClick = {
                                    PS2BiosManager.clear(context)
                                    bios = null
                                },
                                label = { Text("Remover") }
                            )
                        }
                    }
                    bios?.let { info ->
                        val sizeMiB = if (info.sizeBytes > 0) info.sizeBytes / (1024f * 1024f) else -1f
                        Text(
                            buildString {
                                append(if (info.plausible) "✓ BIOS pronta para boot real • " else "⚠ BIOS rejeitada • ")
                                append(info.displayName)
                                if (sizeMiB > 0) append(" • ${String.format("%.1f", sizeMiB)} MiB")
                                append("\n")
                                append(info.reason)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                }

                item {
                    Text("Inicialização", fontWeight = FontWeight.Bold)
                    Text(
                        "BIOS clássica executa o firmware real selecionado antes do disco (Fast Boot OFF). Direta pula a sequência da BIOS e entra no jogo pelo Fast Boot do PCSX2.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PS2Settings.BootStyle.entries) { mode ->
                            FilterChip(
                                selected = config.bootStyle == mode,
                                enabled = mode != PS2Settings.BootStyle.CLASSIC || bios?.plausible == true,
                                onClick = {
                                    PS2Settings.saveBootStyle(context, mode)
                                    refreshCore()
                                },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }

                item {
                    Text("Pacing", fontWeight = FontWeight.Bold)
                    PS2Toggle(
                        title = "Limitar FPS ao ritmo do PS2",
                        subtitle = "Controle manual do limiter do PCSX2. O modo Inteligente não liga/desliga este ajuste automaticamente.",
                        checked = config.frameLimit
                    ) { saveCore(config.copy(frameLimit = it)) }
                    Text(
                        "Primeiro baseline PCSX2: afinidade explícita e ADPF ficam desligados até termos medição física no aparelho.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                item {
                    Text("DualShock 2 touch", fontWeight = FontWeight.Bold)
                    Text(
                        "Dois analógicos, D-pad, □ △ ○ ✕, L1/L2/R1/R2, L3/R3, Start e Select com multitouch independente.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PS2InputSettings.OverlayPreset.entries) { preset ->
                            FilterChip(
                                selected = input.overlayPreset == preset,
                                onClick = { saveInput(input.copy(overlayPreset = preset)) },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { saveInput(input.copy(touchOpacity = (input.touchOpacity - 0.08f).coerceAtLeast(0.22f))) },
                            label = { Text("Opacidade −") }
                        )
                        Text("${(input.touchOpacity * 100).toInt()}%")
                        AssistChip(
                            onClick = { saveInput(input.copy(touchOpacity = (input.touchOpacity + 0.08f).coerceAtMost(1f))) },
                            label = { Text("Opacidade +") }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { saveInput(input.copy(touchScale = (input.touchScale - 0.06f).coerceAtLeast(0.70f))) },
                            label = { Text("Tamanho −") }
                        )
                        Text("${(input.touchScale * 100).toInt()}%")
                        AssistChip(
                            onClick = { saveInput(input.copy(touchScale = (input.touchScale + 0.06f).coerceAtMost(1.30f))) },
                            label = { Text("Tamanho +") }
                        )
                    }
                    PS2Toggle("Fade automático", "Reduz o overlay quando ele está ocioso.", input.dynamicOpacity) {
                        saveInput(input.copy(dynamicOpacity = it))
                    }
                    PS2Toggle("Mostrar D-pad", "Oculta apenas as setas virtuais.", input.showDpad) {
                        saveInput(input.copy(showDpad = it))
                    }
                    PS2Toggle("Mostrar analógico direito", "Mantém o stick direito disponível no touch.", input.showRightStick) {
                        saveInput(input.copy(showRightStick = it))
                    }
                    PS2Toggle("Mostrar L3/R3", "Exibe os cliques dos dois analógicos como botões separados.", input.showL3R3) {
                        saveInput(input.copy(showL3R3 = it))
                    }
                    PS2Toggle("Resposta tátil", "Vibração curta ao capturar um controle virtual.", input.haptics) {
                        saveInput(input.copy(haptics = it))
                    }
                }

                item {
                    Text("Analógicos", fontWeight = FontWeight.Bold)
                    PS2Toggle(
                        "Precisão radial",
                        "Deadzone radial com controle fino no centro e 100% de alcance na borda.",
                        input.precisionAnalog
                    ) { saveInput(input.copy(precisionAnalog = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { saveInput(input.copy(analogDeadzone = (input.analogDeadzone - 0.02f).coerceAtLeast(0.03f))) },
                            label = { Text("Deadzone −") }
                        )
                        Text("${(input.analogDeadzone * 100).toInt()}%")
                        AssistChip(
                            onClick = { saveInput(input.copy(analogDeadzone = (input.analogDeadzone + 0.02f).coerceAtMost(0.30f))) },
                            label = { Text("Deadzone +") }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { saveInput(input.copy(analogSensitivity = (input.analogSensitivity - 0.05f).coerceAtLeast(0.70f))) },
                            label = { Text("Sens. −") }
                        )
                        Text("${(input.analogSensitivity * 100).toInt()}%")
                        AssistChip(
                            onClick = { saveInput(input.copy(analogSensitivity = (input.analogSensitivity + 0.05f).coerceAtMost(1.30f))) },
                            label = { Text("Sens. +") }
                        )
                    }
                }

                item {
                    Text(
                        "Esses ajustes pertencem somente ao PS2. O emucore PCSX2 é carregado apenas no processo :ps2; abrir esta tela não toca nos runtimes PS1/N64.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun PS2Toggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
