package com.bylins.client.ui.components

import mu.KotlinLogging
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import java.awt.Cursor
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bylins.client.ClientState
import com.bylins.client.mapper.Direction
import com.bylins.client.mapper.Room
import com.bylins.client.mapper.ZoneList
import com.bylins.client.ui.theme.LocalAppColorScheme

@OptIn(ExperimentalComposeUiApi::class)
private val logger = KotlinLogging.logger("MapPanel")
private const val MIN_ZOOM = 0.3f
private const val MAX_ZOOM = 3f

@Composable
fun MapPanel(
    clientState: ClientState,
    modifier: Modifier = Modifier
) {
    val colorScheme = LocalAppColorScheme.current
    val rooms by clientState.mapRooms.collectAsState()
    val currentRoomId by clientState.currentRoomId.collectAsState()
    val mapEnabled by clientState.mapEnabled.collectAsState()
    // Path highlighting from scripts
    val pathRoomIds by clientState.pathHighlightRoomIds.collectAsState()
    val pathTargetRoomId by clientState.pathHighlightTargetId.collectAsState()
    // Zone notes and names
    val zoneNotesMap by clientState.zoneNotes.collectAsState()
    val zoneNamesMap by clientState.zoneNames.collectAsState()
    // Сохранённый центр обзора карты из MapManager
    val savedViewCenterRoomId by clientState.mapViewCenterRoomId.collectAsState()

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var zoom by remember { mutableStateOf(1f) }
    var selectedRoom by remember { mutableStateOf<Room?>(null) }
    var showRoomDialog by remember { mutableStateOf(false) }
    var showGoToRoomDialog by remember { mutableStateOf(false) }
    var hoveredRoom by remember { mutableStateOf<Room?>(null) }
    var mousePosition by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Pair(0f, 0f)) }

    // Центр обзора карты (инициализируется из сохранённого значения)
    var viewCenterRoomId by remember(savedViewCenterRoomId) { mutableStateOf(savedViewCenterRoomId) }
    var followPlayer by remember { mutableStateOf(savedViewCenterRoomId == null) }

    // Сохраняем viewCenterRoomId в mapManager при изменении
    LaunchedEffect(viewCenterRoomId) {
        clientState.setMapViewCenterRoom(viewCenterRoomId)
    }

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuRoom by remember { mutableStateOf<Room?>(null) }
    var contextMenuPosition by remember { mutableStateOf(Offset.Zero) }

    // Zone panel width (resizable, persisted)
    val zonePanelWidth by clientState.zonePanelWidth.collectAsState()
    // Панель зон: режим и строка поиска живут, пока открыта вкладка
    var zoneListMode by remember { mutableStateOf(ZoneListMode.VISIBLE) }
    var zoneQuery by remember { mutableStateOf("") }

    // Автоследование за игроком
    LaunchedEffect(currentRoomId, followPlayer) {
        if (followPlayer && currentRoomId != null) {
            viewCenterRoomId = currentRoomId
            offsetX = 0f
            offsetY = 0f
        }
    }

    // Используем viewCenterRoomId или currentRoomId
    val effectiveCenterRoomId = viewCenterRoomId ?: currentRoomId

    // Параметры отрисовки (масштабируемые)
    val baseRoomSize = 32f
    val baseRoomSpacing = 64f  // Increased for better labyrinth visibility
    val roomSize = baseRoomSize * zoom
    val roomSpacing = baseRoomSpacing * zoom

    // Вычисляем позиции комнат с учётом смещения
    val roomPositionsResult = remember(rooms, effectiveCenterRoomId, canvasSize, offsetX, offsetY, zoom) {
        if (canvasSize.first > 0 && canvasSize.second > 0 && effectiveCenterRoomId != null) {
            calculateRoomPositions(
                rooms = rooms,
                startRoomId = effectiveCenterRoomId,
                centerX = canvasSize.first / 2 + offsetX,
                centerY = canvasSize.second / 2 + offsetY,
                roomSize = roomSize,
                roomSpacing = roomSpacing,
                canvasWidth = canvasSize.first,
                canvasHeight = canvasSize.second
            )
        } else {
            RoomPositionsResult(emptyMap(), true, null)
        }
    }
    val displayRooms = roomPositionsResult.displayRooms
    val viewCenterDirection = roomPositionsResult.startRoomDirection

    // Keep updated reference for use in gesture handlers
    val currentDisplayRooms by rememberUpdatedState(displayRooms)

    Column(modifier = modifier) {
        // Панель управления
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surface)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Левая группа: управление картой, навигация
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Тумблер управляет автомаппингом, а не показом карты:
                // прежняя подпись «Карта» читалась как «показать/скрыть»
                TooltipArea(
                    tooltip = {
                        Surface(color = colorScheme.surface, tonalElevation = 4.dp) {
                            Text(
                                text = "Записывать новые комнаты и связи при перемещении. " +
                                    "Выключено — карта не пополняется, но клиент " +
                                    "по-прежнему знает, где вы находитесь.",
                                modifier = Modifier.padding(8.dp),
                                color = colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Автомаппинг",
                            color = if (mapEnabled) Color.White else colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = mapEnabled,
                            onCheckedChange = { clientState.setMapEnabled(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Следовать за игроком
                Text("Следовать", color = if (followPlayer) Color.White else colorScheme.onSurfaceVariant)
                Switch(
                    checked = followPlayer,
                    onCheckedChange = { followPlayer = it }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Навигационные кнопки
                if (!followPlayer) {
                    Button(
                        onClick = {
                            viewCenterRoomId = currentRoomId
                            offsetX = 0f
                            offsetY = 0f
                        },
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        Text("К игроку")
                    }

                    Button(
                        onClick = { showGoToRoomDialog = true },
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        Text("Перейти к...")
                    }
                }

                // Центрировать (сброс смещения и зума)
                Button(
                    onClick = {
                        offsetX = 0f
                        offsetY = 0f
                        zoom = 1f
                    },
                    contentPadding = PaddingValues(8.dp)
                ) {
                    Text("Центрировать")
                }

                // Масштаб
                Button(
                    onClick = { zoom = (zoom * 1.2f).coerceAtMost(MAX_ZOOM) },
                    modifier = Modifier.size(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+")
                }

                Text("${(zoom * 100).toInt()}%", color = Color.White)

                Button(
                    onClick = { zoom = (zoom / 1.2f).coerceAtLeast(MIN_ZOOM) },
                    modifier = Modifier.size(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("-")
                }
            }

            // Правая группа: очистка
            Button(
                onClick = { clientState.clearMap() },
                contentPadding = PaddingValues(8.dp)
            ) {
                Text("Очистить")
            }
        }

        // Основная область карты с панелью зоны
        // Получаем текущую зону из комнаты
        val currentZoneId = effectiveCenterRoomId?.let { rooms[it]?.zone } ?: ""
        // Ищем сохранённое имя зоны
        val savedZoneName = zoneNamesMap[currentZoneId]
        // Формат: "ZoneName (ZoneID)" или "Зона ID: xxx" если нет имени
        val currentZoneName = when {
            savedZoneName != null && currentZoneId.isNotEmpty() -> "$savedZoneName ($currentZoneId)"
            savedZoneName != null -> savedZoneName
            currentZoneId.isNotEmpty() -> "Зона ID: $currentZoneId"
            else -> "Неизвестная зона"
        }
        val currentZoneNotes = zoneNotesMap[currentZoneId] ?: ""

        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            // Карта
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Get current zone for border styling
                val currentZone = effectiveCenterRoomId?.let { rooms[it]?.zone }

            // Click tracking via onPointerEvent
            var pendingClickRoom by remember { mutableStateOf<Room?>(null) }
            var pendingClickTime by remember { mutableStateOf(0L) }
            var pressPos by remember { mutableStateOf(Offset.Zero) }
            var totalDragDist by remember { mutableStateOf(0f) }
            var isPressed by remember { mutableStateOf(false) }
            val doubleClickTimeout = 300L
            val dragThreshold = 10f

            // Execute pending single click after timeout
            LaunchedEffect(pendingClickRoom, pendingClickTime) {
                if (pendingClickRoom != null) {
                    kotlinx.coroutines.delay(doubleClickTimeout)
                    if (pendingClickRoom != null) {
                        followPlayer = false
                        viewCenterRoomId = pendingClickRoom!!.id
                        offsetX = 0f
                        offsetY = 0f
                        pendingClickRoom = null
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .onPointerEvent(PointerEventType.Press) { event ->
                        val change = event.changes.first()
                        if (event.button == PointerButton.Primary) {
                            pressPos = change.position
                            totalDragDist = 0f
                            isPressed = true
                        } else if (event.button == PointerButton.Secondary) {
                            // Right-click context menu
                            val room = findRoomAtPosition(displayRooms, change.position.x, change.position.y, roomSize)
                            if (room != null) {
                                contextMenuRoom = room
                                contextMenuPosition = change.position
                                showContextMenu = true
                            }
                        }
                    }
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val change = event.changes.first()
                        mousePosition = change.position
                        hoveredRoom = findRoomAtPosition(displayRooms, mousePosition.x, mousePosition.y, roomSize)

                        // Handle drag
                        if (isPressed && change.pressed) {
                            val delta = change.position - pressPos
                            val dist = delta.getDistance()

                            if (dist > dragThreshold || totalDragDist > dragThreshold) {
                                // It's a drag
                                totalDragDist += (change.position - (if (totalDragDist > 0) change.previousPosition else pressPos)).getDistance()
                                pendingClickRoom = null  // Cancel pending click

                                val dragDelta = change.position - change.previousPosition
                                val newOffsetX = offsetX + dragDelta.x
                                val newOffsetY = offsetY + dragDelta.y

                                val canvasW = canvasSize.first
                                val canvasH = canvasSize.second
                                val dragRooms = currentDisplayRooms

                                if (canvasW > 0 && canvasH > 0 && dragRooms.isNotEmpty()) {
                                    val margin = roomSize / 2
                                    val anyVisible = dragRooms.values.any { roomInfo ->
                                        val newX = roomInfo.screenX + dragDelta.x
                                        val newY = roomInfo.screenY + dragDelta.y
                                        newX >= -margin && newX <= canvasW + margin &&
                                        newY >= -margin && newY <= canvasH + margin
                                    }
                                    if (anyVisible) {
                                        offsetX = newOffsetX
                                        offsetY = newOffsetY
                                    }
                                } else {
                                    offsetX = newOffsetX
                                    offsetY = newOffsetY
                                }
                            }
                        }
                    }
                    .onPointerEvent(PointerEventType.Release) { event ->
                        if (event.button == PointerButton.Primary && isPressed) {
                            isPressed = false
                            val wasDrag = totalDragDist > dragThreshold

                            if (!wasDrag) {
                                val clickedRoom = findRoomAtPosition(displayRooms, pressPos.x, pressPos.y, roomSize)

                                if (clickedRoom != null) {
                                    if (pendingClickRoom?.id == clickedRoom.id) {
                                        // Double click - open dialog only
                                        pendingClickRoom = null
                                        selectedRoom = clickedRoom
                                        showRoomDialog = true
                                    } else {
                                        // Schedule single click (delayed to check for double-click)
                                        pendingClickRoom = clickedRoom
                                        pendingClickTime = System.currentTimeMillis()
                                    }
                                }
                            }
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit) {
                        hoveredRoom = null
                        isPressed = false
                    }
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val delta = event.changes.first().scrollDelta.y
                        zoom = if (delta < 0) {
                            (zoom * 1.15f).coerceAtMost(MAX_ZOOM)
                        } else {
                            (zoom / 1.15f).coerceAtLeast(MIN_ZOOM)
                        }
                    }
            ) {
                canvasSize = Pair(size.width, size.height)

                if (displayRooms.isEmpty() && rooms.isNotEmpty()) {
                    // Нет текущей комнаты, но есть данные
                    return@Canvas
                }

                if (displayRooms.isNotEmpty()) {
                    drawMap(
                        displayRooms = displayRooms,
                        allRooms = rooms,
                        currentRoomId = currentRoomId,
                        viewCenterRoomId = effectiveCenterRoomId,
                        currentZone = currentZone,
                        hoveredRoomId = hoveredRoom?.id,
                        roomSize = roomSize,
                        zoom = zoom,
                        pathRoomIds = pathRoomIds,
                        pathTargetRoomId = pathTargetRoomId,
                        colors = MapRenderColors.fromColorScheme(colorScheme)
                    )
                }

                // Индикатор направления к комнате обзора (простые стрелки в середине краёв)
                if (viewCenterDirection != null) {
                    val arrowSize = 12f
                    val margin = 15f
                    val dirX = viewCenterDirection.first
                    val dirY = viewCenterDirection.second
                    val color = colorScheme.secondary.copy(alpha = 0.7f)

                    // Определяем основное направление (только одно из 4)
                    val isHorizontal = kotlin.math.abs(dirX) > kotlin.math.abs(dirY)

                    if (isHorizontal) {
                        // Стрелка на левом или правом краю
                        val edgeX = if (dirX > 0) size.width - margin else margin
                        val centerY = size.height / 2
                        val pointDir = if (dirX > 0) 1f else -1f

                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(edgeX + pointDir * arrowSize / 2, centerY)
                            lineTo(edgeX - pointDir * arrowSize / 2, centerY - arrowSize / 2)
                            lineTo(edgeX - pointDir * arrowSize / 2, centerY + arrowSize / 2)
                            close()
                        }
                        drawPath(path, color)
                    } else {
                        // Стрелка на верхнем или нижнем краю
                        val centerX = size.width / 2
                        val edgeY = if (dirY > 0) size.height - margin else margin
                        val pointDir = if (dirY > 0) 1f else -1f

                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(centerX, edgeY + pointDir * arrowSize / 2)
                            lineTo(centerX - arrowSize / 2, edgeY - pointDir * arrowSize / 2)
                            lineTo(centerX + arrowSize / 2, edgeY - pointDir * arrowSize / 2)
                            close()
                        }
                        drawPath(path, color)
                    }
                }
            }

            // Тултип при наведении
            if (hoveredRoom != null) {
                val hoveredZoneId = hoveredRoom!!.zone ?: ""
                RoomTooltip(
                    room = hoveredRoom!!,
                    allRooms = rooms,
                    mouseX = mousePosition.x,
                    mouseY = mousePosition.y,
                    zoneNotes = zoneNotesMap[hoveredZoneId] ?: "",
                    zoneNames = zoneNamesMap,
                    maxWidth = 280,
                    canvasWidth = canvasSize.first,
                    canvasHeight = canvasSize.second
                )
            }

            // Информационная панель если нет текущей комнаты
            if (currentRoomId == null && rooms.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Карта пуста. Начните исследование!",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Context menu at mouse position
            if (showContextMenu && contextMenuRoom != null) {
                // Use Box with offset for proper positioning
                Box(
                    modifier = Modifier
                        .offset { IntOffset(contextMenuPosition.x.toInt(), contextMenuPosition.y.toInt()) }
                ) {
                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false }
                    ) {
                        // Script-registered commands (pathfinding, navigation, etc.)
                        val customCommands = clientState.getMapContextCommands()
                        customCommands.forEach { (name, _) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    contextMenuRoom?.let { room ->
                                        clientState.executeMapCommand(name, room)
                                    }
                                    showContextMenu = false
                                }
                            )
                        }

                        // Built-in: Edit room
                        if (customCommands.isNotEmpty()) {
                            Divider()
                        }
                        DropdownMenuItem(
                            text = { Text("Редактировать") },
                            onClick = {
                                selectedRoom = contextMenuRoom
                                showRoomDialog = true
                                showContextMenu = false
                            }
                        )
                    }
                }
            }

            // Direction navigation pad (6 directions: N, W, E, S, U, D)
            val viewCenterRoom = effectiveCenterRoomId?.let { rooms[it] }
            if (viewCenterRoom != null) {
                DirectionPad(
                    room = viewCenterRoom,
                    allRooms = rooms,
                    onNavigate = { targetRoomId ->
                        followPlayer = false
                        viewCenterRoomId = targetRoomId
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
            } // End of Box

            // Панель зон справа с ручкой для ресайза
            if (rooms.isNotEmpty()) {
                // Ручка для изменения ширины (между картой и панелью)
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(colorScheme.border)
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                // Drag to the left = increase panel width, to the right = decrease
                                val newWidth = zonePanelWidth - dragAmount.x
                                clientState.setZonePanelWidth(newWidth.toInt())
                            }
                        }
                )

                val visibleZones = remember(displayRooms, zoneNamesMap) {
                    ZoneList.visible(displayRooms.values.map { it.room }, zoneNamesMap)
                }
                val allZones = remember(rooms, zoneNamesMap, zoneQuery) {
                    ZoneList.all(rooms.values, zoneNamesMap, zoneQuery)
                }
                val playerZoneId = currentRoomId?.let { rooms[it]?.zone }

                ZonesPanel(
                    mode = zoneListMode,
                    onModeChange = { zoneListMode = it },
                    query = zoneQuery,
                    onQueryChange = { zoneQuery = it },
                    visibleZones = visibleZones,
                    allZones = allZones,
                    playerZoneId = playerZoneId,
                    viewZoneId = currentZoneId.takeIf { it.isNotEmpty() },
                    viewZoneTitle = currentZoneName,
                    viewZoneNotes = currentZoneNotes,
                    onSelectZone = { zoneId ->
                        // Из отрисованных комнат зоны — ближайшая к центру обзора,
                        // чтобы карта не прыгала; зоны нет на экране — её комната
                        // входа, BFS пойдёт от неё и связность не нужна
                        val shown = displayRooms.values.filter { it.room.zone == zoneId }
                        val target = shown.minByOrNull { kotlin.math.abs(it.gridX) + kotlin.math.abs(it.gridY) }?.room?.id
                            ?: ZoneList.entryRoom(zoneId, rooms.values)
                        if (target != null) {
                            followPlayer = false
                            viewCenterRoomId = target
                            offsetX = 0f
                            offsetY = 0f
                            if (shown.isNotEmpty()) {
                                val width = shown.maxOf { it.gridX } - shown.minOf { it.gridX }
                                val height = shown.maxOf { it.gridY } - shown.minOf { it.gridY }
                                zoom = ZoneList.zoomToFit(
                                    gridWidth = width, gridHeight = height,
                                    canvasWidth = canvasSize.first, canvasHeight = canvasSize.second,
                                    baseSpacing = baseRoomSpacing, min = MIN_ZOOM, max = MAX_ZOOM
                                )
                            }
                        }
                    },
                    onNotesChanged = { newNotes ->
                        clientState.setZoneNotes(currentZoneId, newNotes)
                    },
                    onFocusChanged = { focused ->
                        clientState.setSecondaryTextFieldFocused(focused)
                    },
                    width = zonePanelWidth.dp
                )
            }
        } // End of Row

        // Информация о текущей комнате
        if (currentRoomId != null) {
            val currentRoom = rooms[currentRoomId]
            if (currentRoom != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    color = colorScheme.surface,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = currentRoom.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "ID: ${currentRoom.id}",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Выходы: ${currentRoom.getAvailableDirections().joinToString(", ") { it.russianName }}",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!currentRoom.zone.isNullOrEmpty()) {
                            Text(
                                text = "Зона: ${currentRoom.zone}",
                                color = colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (currentRoom.notes.isNotEmpty()) {
                            Text(
                                text = "Заметка: ${currentRoom.notes}",
                                color = colorScheme.warning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // Статистика
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = colorScheme.surface,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Комнат на карте: ${rooms.size}",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Отображается: ${displayRooms.size}",
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // Диалог редактирования комнаты
    if (showRoomDialog && selectedRoom != null) {
        RoomDetailsDialog(
            room = selectedRoom!!,
            allRooms = rooms,
            onDismiss = { showRoomDialog = false },
            onSave = { name, note, terrain, properties, zone, exits, visited ->
                clientState.updateRoom(
                    roomId = selectedRoom!!.id,
                    name = name,
                    note = note,
                    terrain = terrain,
                    properties = properties,
                    zone = zone,
                    exits = exits,
                    visited = visited
                )
            }
        )
    }

    // Диалог перехода к комнате
    if (showGoToRoomDialog) {
        GoToRoomDialog(
            rooms = rooms,
            onDismiss = { showGoToRoomDialog = false },
            onNavigate = { roomId ->
                viewCenterRoomId = roomId
                offsetX = 0f
                offsetY = 0f
                showGoToRoomDialog = false
            }
        )
    }
}

/**
 * Direction navigation pad - 6 directions: N, W, E, S, U, D
 * Layout:
 *   [ ]  [N]  [U]
 *   [W]  [●]  [E]
 *   [ ]  [S]  [D]
 */
@Composable
private fun DirectionPad(
    room: Room,
    allRooms: Map<String, Room>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = LocalAppColorScheme.current
    // Map directions to grid positions: null = empty, "center" marker for current position
    // UP and DOWN use directions with dz != 0
    data class GridCell(val direction: Direction?, val isCenter: Boolean = false, val isEmpty: Boolean = false)

    val directionGrid = listOf(
        listOf(GridCell(null, isEmpty = true), GridCell(Direction.NORTH), GridCell(Direction.UP)),
        listOf(GridCell(Direction.WEST), GridCell(null, isCenter = true), GridCell(Direction.EAST)),
        listOf(GridCell(null, isEmpty = true), GridCell(Direction.SOUTH), GridCell(Direction.DOWN))
    )

    Surface(
        modifier = modifier,
        color = colorScheme.surface.copy(alpha = 0.8f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            directionGrid.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    row.forEach { cell ->
                        when {
                            cell.isCenter -> {
                                // Center cell - current position indicator
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(colorScheme.success, MaterialTheme.shapes.small),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("●", color = Color.White)
                                }
                            }
                            cell.isEmpty -> {
                                // Empty cell
                                Box(modifier = Modifier.size(32.dp))
                            }
                            else -> {
                                val direction = cell.direction!!
                                // For UP/DOWN, find any exit with matching dz
                                val exit = if (direction.dz != 0) {
                                    room.exits.entries.find { it.key.dz == direction.dz && it.value.targetRoomId.isNotEmpty() }?.value
                                } else {
                                    room.exits[direction]
                                }
                                val hasExit = exit != null && exit.targetRoomId.isNotEmpty()
                                val targetRoom = if (hasExit) allRooms[exit!!.targetRoomId] else null

                                // Colors for UP/DOWN - keep directional colors for up/down
                                val buttonColor = when {
                                    !hasExit -> colorScheme.divider
                                    direction.dz > 0 -> Color(0xFF00AAAA) // UP - cyan
                                    direction.dz < 0 -> Color(0xFFAA00AA) // DOWN - magenta
                                    else -> colorScheme.primary // N/S/E/W
                                }

                                Button(
                                    onClick = {
                                        if (hasExit && targetRoom != null) {
                                            onNavigate(targetRoom.id)
                                        }
                                    },
                                    enabled = hasExit && targetRoom != null,
                                    modifier = Modifier.size(32.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = buttonColor,
                                        disabledContainerColor = colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = direction.shortName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasExit) Color.White else colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for navigating to a room by ID or name
 */
@Composable
private fun GoToRoomDialog(
    rooms: Map<String, Room>,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val colorScheme = LocalAppColorScheme.current
    var searchQuery by remember { mutableStateOf("") }

    // Filter rooms by search query (match ID or name)
    val filteredRooms = remember(searchQuery, rooms) {
        if (searchQuery.isBlank()) {
            rooms.values.take(50).toList() // Show first 50 rooms when no search
        } else {
            rooms.values.filter { room ->
                room.id.contains(searchQuery, ignoreCase = true) ||
                room.name.contains(searchQuery, ignoreCase = true)
            }.take(50)
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(450.dp)
                .heightIn(max = 500.dp)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Перейти к комнате",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Поиск по ID или названию...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = colorScheme.success,
                        unfocusedBorderColor = Color.Gray,
                        focusedPlaceholderColor = Color.Gray,
                        unfocusedPlaceholderColor = Color.Gray
                    ),
                    singleLine = true
                )

                Text(
                    text = "Найдено: ${filteredRooms.size} комнат",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )

                // Room list
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    filteredRooms.forEach { room ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(room.id) },
                            color = colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text(
                                    text = room.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "#${room.id}",
                                    color = colorScheme.secondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть", color = Color.White)
                    }
                }
            }
        }
    }
}
