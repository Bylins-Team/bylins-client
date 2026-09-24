package com.bylins.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bylins.client.mapper.ZoneEntry
import com.bylins.client.ui.theme.LocalAppColorScheme

/** Что показывать в списке зон. */
enum class ZoneListMode { VISIBLE, ALL }

/**
 * Панель зон справа от карты.
 *
 * Два режима: «Видимые» — легенда того, что сейчас на экране, по строке на
 * зону; «Все» — каждая зона карты с поиском по имени и ID (зон полтысячи,
 * без поиска список бесполезен). Клик по зоне — карта приближается к ней и
 * центруется. Ниже — заметки зоны, на которую смотрит карта, сворачиваемые.
 *
 * @param visibleZones зоны отрисованных комнат
 * @param allZones все зоны карты, уже отфильтрованные по [query]
 * @param playerZoneId зона, где стоит игрок
 * @param viewZoneId зона комнаты, на которую смотрит карта — её заметки внизу
 */
@Composable
fun ZonesPanel(
    mode: ZoneListMode,
    onModeChange: (ZoneListMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    visibleZones: List<ZoneEntry>,
    allZones: List<ZoneEntry>,
    playerZoneId: String?,
    viewZoneId: String?,
    viewZoneTitle: String,
    viewZoneNotes: String,
    onSelectZone: (String) -> Unit,
    onNotesChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    width: Dp,
    modifier: Modifier = Modifier
) {
    val colorScheme = LocalAppColorScheme.current
    var notesExpanded by remember { mutableStateOf(false) }

    // Сбрасываем фокус при размонтировании: хоткеи снова должны работать
    DisposableEffect(Unit) {
        onDispose { onFocusChanged(false) }
    }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(colorScheme.surface)
            .padding(8.dp)
    ) {
        // Переключатель режима
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeChip("Видимые", mode == ZoneListMode.VISIBLE) { onModeChange(ZoneListMode.VISIBLE) }
            ModeChip("Все", mode == ZoneListMode.ALL) { onModeChange(ZoneListMode.ALL) }
        }

        if (mode == ZoneListMode.ALL) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                placeholder = { Text("Имя или ID зоны", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.border,
                    cursorColor = colorScheme.primary
                )
            )
        }

        val zones = if (mode == ZoneListMode.VISIBLE) visibleZones else allZones
        Text(
            text = if (mode == ZoneListMode.VISIBLE) "На экране: ${zones.size}" else "Зон: ${zones.size}",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(zones, key = { it.id }) { zone ->
                ZoneRow(
                    zone = zone,
                    isPlayerHere = zone.id == playerZoneId,
                    isViewed = zone.id == viewZoneId,
                    countLabel = if (mode == ZoneListMode.VISIBLE) "на экране" else "комнат",
                    onClick = { onSelectZone(zone.id) }
                )
            }
        }

        // Заметки зоны, на которую смотрит карта
        if (viewZoneId != null) {
            Divider(modifier = Modifier.padding(vertical = 6.dp), color = colorScheme.divider)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { notesExpanded = !notesExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (notesExpanded) "▾ " else "▸ ",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Заметки: $viewZoneTitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (!notesExpanded && viewZoneNotes.isNotBlank()) {
                    Text(
                        text = " •",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.warning
                    )
                }
            }
            if (notesExpanded) {
                ZoneNotes(
                    zoneId = viewZoneId,
                    notes = viewZoneNotes,
                    onNotesChanged = onNotesChanged,
                    onFocusChanged = onFocusChanged
                )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colorScheme = LocalAppColorScheme.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colorScheme.primary,
            selectedLabelColor = colorScheme.onSurface,
            labelColor = colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun ZoneRow(
    zone: ZoneEntry,
    isPlayerHere: Boolean,
    isViewed: Boolean,
    countLabel: String,
    onClick: () -> Unit
) {
    val colorScheme = LocalAppColorScheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (isViewed) Modifier.border(1.dp, colorScheme.primary, MaterialTheme.shapes.small) else Modifier),
        color = colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlayerHere) {
                    // Игрок здесь — метка тем же цветом, что и на карте
                    Text("● ", color = colorScheme.success, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = zone.title,
                    color = colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isPlayerHere) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2
                )
            }
            Text(
                text = "${zone.id} · ${zone.roomCount} $countLabel",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** Поле заметок зоны с превью markdown — то, что раньше было всей панелью. */
@Composable
private fun ZoneNotes(
    zoneId: String,
    notes: String,
    onNotesChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit
) {
    val colorScheme = LocalAppColorScheme.current
    var text by remember(zoneId) { mutableStateOf(notes) }

    // Обновляем локальное состояние при изменении внешнего
    LaunchedEffect(notes) { text = notes }

    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            onNotesChanged(value)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 180.dp)
            .onFocusChanged { onFocusChanged(it.isFocused) },
        placeholder = {
            Text(
                "Заметки о зоне...\n\nПоддерживается **жирный** и *курсив*",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurface),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colorScheme.primary,
            unfocusedBorderColor = colorScheme.border,
            cursorColor = colorScheme.primary
        )
    )

    if (text.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
            color = colorScheme.background,
            shape = MaterialTheme.shapes.small
        ) {
            // Заметки бывают длинными, поэтому превью прокручивается, а не обрезается молча
            Box(modifier = Modifier.padding(4.dp).verticalScroll(rememberScrollState())) {
                MarkdownText(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface
                )
            }
        }
    }
}
