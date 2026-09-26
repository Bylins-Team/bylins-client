package com.bylins.client.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bylins.client.ClientState

private val outputPanelLogger = mu.KotlinLogging.logger("OutputPanel")

@Composable
fun OutputPanel(
    clientState: ClientState,
    modifier: Modifier = Modifier
) {
    val tabs by clientState.tabs.collectAsState()
    val activeTabId by clientState.activeTabId.collectAsState()
    val outputSearchRequest by clientState.outputSearchRequest.collectAsState()

    // Логируем вкладки при изменении
    LaunchedEffect(tabs) {
        outputPanelLogger.info { "Tabs updated: ${tabs.map { "${it.id}(${it.name})" }}" }
    }

    // Получаем настройки шрифта
    val fontFamilyName by clientState.fontFamily.collectAsState()
    val fontSize by clientState.fontSize.collectAsState()
    val fontFamily = remember(fontFamilyName) { getFontFamily(fontFamilyName) }

    var showTabDialog by remember { mutableStateOf(false) }
    var editingTab by remember { mutableStateOf<com.bylins.client.tabs.Tab?>(null) }

    Column(modifier = modifier) {
        // Вкладки с управлением
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Состояние drag-and-drop перестановки вкладок.
            // draggingTabId/dragOffsetX читаются прямо в graphicsLayer (фаза слоя),
            // поэтому перетаскиваемая вкладка визуально следует за курсором даже без
            // рекомпозиции ленивого ScrollableTabRow. Центры вкладок копим для выбора
            // целевой позиции в конце жеста.
            var draggingTabId by remember { mutableStateOf<String?>(null) }
            var dragOffsetX by remember { mutableStateOf(0f) }
            var dragOriginCenter by remember { mutableStateOf(0f) }
            val tabCenters = remember { mutableStateMapOf<String, Float>() }

            // Вкладки
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOfFirst { it.id == activeTabId }.takeIf { it >= 0 } ?: 0,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White,
                edgePadding = 0.dp,
                modifier = Modifier.weight(1f)
            ) {
                tabs.forEach { tab ->
                    // Подписываемся на индикатор непрочитанных сообщений
                    val hasUnread by tab.hasUnreadMessages.collectAsState()

                    // Системные вкладки не двигаем (main — первая, logs — последняя)
                    val movable = tab.id != "main" && tab.id != "logs"

                    // key по id: при перестановке композабл вкладки сохраняется (стабильная
                    // идентичность), drag-жест не обрывается.
                    key(tab.id) {
                    Tab(
                        selected = tab.id == activeTabId,
                        onClick = { clientState.setActiveTab(tab.id) },
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                // Центр вкладки в координатах строки (layout, без учёта
                                // визуального сдвига перетаскивания — он draw-only).
                                tabCenters[tab.id] = coords.localToWindow(Offset.Zero).x + coords.size.width / 2f
                            }
                            .graphicsLayer {
                                if (tab.id == draggingTabId) {
                                    translationX = dragOffsetX
                                    alpha = 0.85f
                                }
                            }
                            // Индикатор позиции вставки: на целевой вкладке рисуем
                            // вертикальную линию с той стороны, куда приземлится перенос.
                            // Читается в фазе рисования — обновляется без рекомпозиции.
                            .drawBehind {
                                val dragId = draggingTabId
                                if (dragId != null && dragId != tab.id) {
                                    val targetCenter = dragOriginCenter + dragOffsetX
                                    val nearest = tabs
                                        .filter { it.id != "main" && it.id != "logs" }
                                        .minByOrNull {
                                            kotlin.math.abs((tabCenters[it.id] ?: Float.MAX_VALUE) - targetCenter)
                                        }
                                    if (nearest?.id == tab.id) {
                                        val myCenter = tabCenters[tab.id] ?: 0f
                                        val barW = 3.dp.toPx()
                                        val x = if (targetCenter < myCenter) 0f else size.width - barW
                                        drawRect(
                                            color = Color(0xFF4DA3FF),
                                            topLeft = Offset(x, 0f),
                                            size = Size(barW, size.height)
                                        )
                                    }
                                }
                            }
                            .then(
                                if (movable) Modifier.pointerInput(tab.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingTabId = tab.id
                                            dragOffsetX = 0f
                                            dragOriginCenter = tabCenters[tab.id] ?: 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetX += dragAmount.x
                                        },
                                        onDragCancel = {
                                            draggingTabId = null
                                            dragOffsetX = 0f
                                        },
                                        onDragEnd = {
                                            val targetCenter = dragOriginCenter + dragOffsetX
                                            // Ближайшая по центру подвижная вкладка — туда и встаём.
                                            val nearest = tabs
                                                .filter { it.id != "main" && it.id != "logs" }
                                                .minByOrNull {
                                                    kotlin.math.abs((tabCenters[it.id] ?: Float.MAX_VALUE) - targetCenter)
                                                }
                                            if (nearest != null && nearest.id != tab.id) {
                                                // Сторона вставки — как у индикатора
                                                val placeAfter = targetCenter >= (tabCenters[nearest.id] ?: 0f)
                                                clientState.moveTabTo(tab.id, nearest.id, placeAfter)
                                            }
                                            draggingTabId = null
                                            dragOffsetX = 0f
                                        }
                                    )
                                } else Modifier
                            ),
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(tab.name)
                                // Индикатор непрочитанных сообщений (оранжевая точка)
                                if (hasUnread && tab.id != activeTabId) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFFFF9800), shape = CircleShape)
                                    )
                                }
                                // Кнопки управления (только для пользовательских вкладок, не для системных)
                                if (tab.id != "main" && tab.id != "logs" && !tab.isPluginTab) {
                                    IconButton(
                                        onClick = {
                                            editingTab = tab
                                            showTabDialog = true
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Text("✎", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = { clientState.removeTab(tab.id) },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Text("✕", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    )
                    } // конец key(tab.id)
                }
            }

            // Кнопка добавления новой вкладки
            IconButton(
                onClick = {
                    editingTab = null
                    showTabDialog = true
                }
            ) {
                Text("+", fontSize = 20.sp, color = Color.White)
            }
        }

        // Рендерим только активную вкладку
        val activeTab = tabs.find { it.id == activeTabId }
        if (activeTab != null) {
            // Снимок буфера: для главной вкладки — из TelnetClient, для прочих — из Tab
            val mainSnapshot by clientState.mainOutputSnapshot.collectAsState()
            val tabSnapshot by activeTab.snapshot.collectAsState()
            val snapshot = if (activeTab.id == "main") mainSnapshot else tabSnapshot

            val holder = remember(activeTab.id) { clientState.outputViewHolder(activeTab.id) }

            val placeholder = remember(activeTab.id, activeTab.name) {
                if (activeTab.id == "main") {
                    "Добро пожаловать в Bylins MUD Client!\nПодключитесь к серверу для начала игры.\n\n"
                } else {
                    "${activeTab.name}: пусто\n"
                }
            }

            // Доля разделителя — своя на каждую вкладку (хранится в holder)
            val splitFraction = holder.splitFraction

            // Ctrl+F из любого места: открыть/активировать поиск ТОЛЬКО на активной вкладке.
            // Ключ — только счётчик: смена вкладки эффект не перезапускает, поэтому
            // переключение между вкладками не открывает на них поиск.
            LaunchedEffect(outputSearchRequest) {
                // Реагируем только на НОВЫЙ запрос (Ctrl+F), а не на пересоздание панели
                // при возврате на вкладку «Вывод» с уже ненулевым счётчиком.
                if (outputSearchRequest > 0 && outputSearchRequest != clientState.lastHandledOutputSearchRequest) {
                    clientState.lastHandledOutputSearchRequest = outputSearchRequest
                    holder.searchActive = true
                    holder.bumpSearchOpen()
                }
            }

            // key по id вкладки: при переключении внутренних вкладок создаётся свежее
            // поддерево (иначе переиспользование по тому же месту показывало старую вкладку)
            key(activeTab.id) {
                com.bylins.client.ui.components.output.ScrollbackOutputView(
                    snapshot = snapshot,
                    holder = holder,
                    splitFraction = splitFraction,
                    onSplitFractionChange = { clientState.setOutputSplitFraction(activeTab.id, it) },
                    fontFamily = fontFamily,
                    fontSize = fontSize,
                    emptyPlaceholder = placeholder,
                    onSearchFocusChanged = { clientState.setSecondaryTextFieldFocused(it) },
                    onScreenSize = { columns, rows -> clientState.setScreenSize(columns, rows) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Диалог создания/редактирования вкладки
    if (showTabDialog) {
        TabDialog(
            tab = editingTab,
            onDismiss = {
                showTabDialog = false
                editingTab = null
            },
            onSave = { name, filters, captureMode, profileTab, profileLog, persistContent, timestamps ->
                if (editingTab != null) {
                    clientState.updateTab(editingTab!!.id, name, filters, captureMode, profileTab, profileLog, persistContent, timestamps)
                } else {
                    clientState.createTab(name, filters, captureMode, profileTab, profileLog, persistContent, timestamps)
                }
                showTabDialog = false
                editingTab = null
            }
        )
    }
}

/**
 * Преобразует строковое название семейства шрифтов в FontFamily
 */
private fun getFontFamily(familyName: String): FontFamily =
    com.bylins.client.ui.fonts.SystemFonts.resolve(familyName)
