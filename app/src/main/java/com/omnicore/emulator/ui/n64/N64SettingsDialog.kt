package com.omnicore.emulator.ui.n64

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
import com.omnicore.emulator.core.n64.N64NativeBridge
import com.omnicore.emulator.settings.N64InputSettings
import com.omnicore.emulator.settings.N64PerformanceProfile
import com.omnicore.emulator.settings.N64Settings

@Composable
fun N64SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val device = remember { N64PerformanceProfile.detect(context) }
    var config by remember { mutableStateOf(N64Settings.resolve(context)) }
    var input by remember { mutableStateOf(N64InputSettings.resolve(context)) }

    fun refreshCore() { config = N64Settings.resolve(context) }
    fun saveCore(next: N64Settings.Config) {
        N64Settings.saveCustom(context, next)
        refreshCore()
    }
    fun saveInput(next: N64InputSettings.Config) {
        N64InputSettings.save(context, next)
        input = N64InputSettings.resolve(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Nintendo 64", fontWeight = FontWeight.Black)
                Text("Mupen64Plus-Next • configuração isolada", style = MaterialTheme.typography.labelMedium)
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
                            Text(device.label, fontWeight = FontWeight.Bold)
                            Text(N64NativeBridge.runtimeInfo(), style = MaterialTheme.typography.bodySmall)
                        }
                        AssistChip(onClick = {}, label = { Text(if (N64NativeBridge.hasCore()) "CORE OK" else "CORE PENDENTE") })
                    }
                }
                item {
                    Text("Preset N64", fontWeight = FontWeight.Bold)
                    Text(config.preset.subtitle, style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(N64Settings.Preset.entries.filter { it != N64Settings.Preset.CUSTOM }) { preset ->
                            FilterChip(
                                selected = config.preset == preset,
                                onClick = { N64Settings.savePreset(context, preset); refreshCore() },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                }
                item {
                    Text("CPU", fontWeight = FontWeight.Bold)
                    Text("Dynarec é o caminho recomendado para gameplay. Cached Interpreter fica disponível para diagnóstico/compatibilidade.", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(N64Settings.CpuMode.entries) { mode ->
                            FilterChip(
                                selected = config.cpuMode == mode,
                                onClick = { saveCore(config.copy(cpuMode = mode)) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
                item {
                    Text("Resolução interna", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(N64Settings.InternalResolution.entries) { mode ->
                            FilterChip(
                                selected = config.internalResolution == mode,
                                onClick = { saveCore(config.copy(internalResolution = mode)) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    if (device.tier == N64PerformanceProfile.Tier.LOW) {
                        Text("Hardware limitado permanece em resolução nativa para proteger FPS e áudio.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("Formato da imagem", fontWeight = FontWeight.Bold)
                    Text("Widescreen ajustado usa o hack de aspect ratio do próprio GLideN64; não é só imagem esticada.", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(N64Settings.AspectRatio.entries) { mode ->
                            FilterChip(
                                selected = config.aspectRatio == mode,
                                onClick = { saveCore(config.copy(aspectRatio = mode)) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
                item {
                    N64Toggle(
                        title = "Framebuffer emulation",
                        subtitle = "Ative para efeitos que dependem de framebuffer. Desligado reduz custo e input lag em muitos jogos.",
                        checked = config.framebufferEmulation
                    ) { saveCore(config.copy(framebufferEmulation = it)) }
                    Text(
                        "RSP HLE, Expansion Pak automático e renderer GL single-thread permanecem protegidos nesta fase para evitar regressões de compatibilidade.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                item {
                    Text("Controle N64", fontWeight = FontWeight.Bold)
                    Text("A/B, Z, L/R, Start, analógico, D-pad e quatro C-buttons com multitouch independente.", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(N64InputSettings.CButtonMode.entries) { mode ->
                            FilterChip(
                                selected = input.cButtonMode == mode,
                                onClick = { saveInput(input.copy(cButtonMode = mode)) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
                item {
                    Text("Overlay touch", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(N64InputSettings.OverlayPreset.entries) { preset ->
                            FilterChip(
                                selected = input.overlayPreset == preset,
                                onClick = { saveInput(input.copy(overlayPreset = preset)) },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { saveInput(input.copy(touchOpacity = (input.touchOpacity - 0.08f).coerceAtLeast(0.25f))) },
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
                            onClick = { saveInput(input.copy(touchScale = (input.touchScale - 0.06f).coerceAtLeast(0.72f))) },
                            label = { Text("Tamanho −") }
                        )
                        Text("${(input.touchScale * 100).toInt()}%")
                        AssistChip(
                            onClick = { saveInput(input.copy(touchScale = (input.touchScale + 0.06f).coerceAtMost(1.28f))) },
                            label = { Text("Tamanho +") }
                        )
                    }
                    N64Toggle(
                        title = "Fade automático",
                        subtitle = "Diminui a presença dos controles quando você não está tocando.",
                        checked = input.dynamicOpacity
                    ) { saveInput(input.copy(dynamicOpacity = it)) }
                    N64Toggle(
                        title = "Mostrar D-pad",
                        subtitle = "Pode ser ocultado em jogos que usam apenas o analógico.",
                        checked = input.showDpad
                    ) { saveInput(input.copy(showDpad = it)) }
                }
                item {
                    Text("Analógico", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { saveInput(input.copy(analogDeadzone = (input.analogDeadzone - 0.02f).coerceAtLeast(0.04f))) },
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
                    Text("Controller Pak", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(N64InputSettings.PakMode.entries) { mode ->
                            FilterChip(
                                selected = input.pakMode == mode,
                                onClick = { saveInput(input.copy(pakMode = mode)) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    N64Toggle(
                        title = "Resposta tátil",
                        subtitle = "Vibração curta ao tocar nos controles virtuais.",
                        checked = input.haptics
                    ) { saveInput(input.copy(haptics = it)) }
                }
                item {
                    Text(
                        "Essas opções pertencem somente ao Nintendo 64. Nenhuma configuração do PlayStation é alterada aqui.",
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
private fun N64Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
