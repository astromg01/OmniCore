package com.omnicore.emulator.ui.achievements

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
