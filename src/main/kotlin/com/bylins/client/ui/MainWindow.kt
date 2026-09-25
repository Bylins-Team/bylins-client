package com.bylins.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bylins.client.ClientState
import com.bylins.client.ui.components.*
import com.bylins.client.ui.theme.AppTheme
import com.bylins.client.ui.theme.LocalAppColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import java.awt.Cursor

// Ниже этой ширины (dp) перетаскивание разделителя сворачивает боковую панель
private const val COLLAPSE_DRAG_THRESHOLD_DP = 120

// Ширина (dp), до которой «распрямляем» панель при разворачивании, если она
// осталась схлопнутой после утаскивания разделителя
private const val RESTORE_MIN_WIDTH_DP = 220

// Определение вкладки
data class TabDef(val id: String, val name: String)

// Все доступные вкладки
val ALL_TABS = listOf(
    TabDef("main", "Вывод"),
    TabDef("triggers", "Триггеры"),
    TabDef("aliases", "Алиасы"),
    TabDef("hotkeys", "Хоткеи"),
    TabDef("context", "Контекст"),
    TabDef("stats", "Статистика"),
    TabDef("graphs", "Графики"),
    TabDef("map", "Карта"),
    TabDef("scripts", "Скрипты"),
    TabDef("plugins", "Плагины"),
    TabDef("msdp", "MSDP"),
    TabDef("gmcp", "GMCP"),
    TabDef("settings", "Настройки"),
    TabDef("profiles", "Профили")
)

@Composable
fun MainWindow() {
    val clientState = remember { ClientState() }
    var selectedTabId by remember { mutableStateOf("main") }
    val inputFocusRequester = remember { FocusRequester() }
    val isConnected by clientState.isConnected.collectAsState()
    val msdpEnabled by clientState.msdpEnabled.collectAsState()
    val secondaryTextFieldFocused by clientState.secondaryTextFieldFocused.collectAsState()
    val hiddenTabs by clientState.hiddenTabs.collectAsState()

    // Фильтруем видимые вкладки
    val visibleTabs = ALL_TABS.filter { it.id in com.bylins.client.PERMANENT_TAB_IDS || it.id !in hiddenTabs }
    val selectedTabIndex = visibleTabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)

    // Отслеживаем последний обработанный KeyDown для поглощения KeyUp
    var lastHandledKey by remember { mutableStateOf<androidx.compose.ui.input.key.Key?>(null) }

    // Фокусируем input после подключения
    LaunchedEffect(isConnected) {
        if (isConnected) {
            inputFocusRequester.requestFocus()
        }
    }

    // Фокусируем input по запросу (например, после завершения следования)
    val requestInputFocusTrigger by clientState.requestInputFocus.collectAsState()
    LaunchedEffect(requestInputFocusTrigger) {
        if (requestInputFocusTrigger > 0) {
            inputFocusRequester.requestFocus()
        }
    }

    // Получаем текущую тему
    val currentThemeName by clientState.currentTheme.collectAsState()
    val appColorScheme = remember(currentThemeName) {
        AppTheme.getColorScheme(currentThemeName)
    }
    val materialColorScheme = remember(appColorScheme, currentThemeName) {
        AppTheme.toMaterialColorScheme(appColorScheme, isDark = currentThemeName != "LIGHT")
    }
    // Панели и диалоги — на Material 2, и без своей палитры он подставлял
    // дефолтную светлую: белые кнопки и сиреневые подписи на тёмном фоне
    val material2Colors = remember(appColorScheme, currentThemeName) {
        AppTheme.toMaterial2Colors(appColorScheme, isDark = currentThemeName != "LIGHT")
    }

    // Подписи зон и комнат — один формат на все панели и диалоги
    val scopeLabels = remember(clientState) {
        com.bylins.client.ui.components.ScopeLabels(
            zone = { id -> clientState.zoneLabel(id) },
            room = { id -> clientState.roomLabelForScope(id) }
        )
    }

    CompositionLocalProvider(
        LocalAppColorScheme provides appColorScheme,
        com.bylins.client.ui.components.LocalScopeLabels provides scopeLabels
    ) {
        androidx.compose.material.MaterialTheme(colors = material2Colors) {
        MaterialTheme(
            colorScheme = materialColorScheme
        ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            // Ctrl+F из любого места (включая поле ввода команд) открывает
                            // и фокусирует поиск по выводу на вкладке «Вывод».
                            // Сочетания с Shift и Alt свободны — они уходят в хоткеи.
                            if (
                                OutputSearchShortcut.isOpen(
                                    key = com.bylins.client.hotkeys.PhysicalKey.of(event),
                                    isCommandPressed = CommandModifier.isPressed(
                                        event.isCtrlPressed, event.isMetaPressed
                                    ),
                                    isAltPressed = event.isAltPressed,
                                    isShiftPressed = event.isShiftPressed
                                )
                            ) {
                                selectedTabId = "main"
                                clientState.requestOutputSearch()
                                lastHandledKey = event.key
                                return@onPreviewKeyEvent true
                            }
                            // Хоткей сравниваем по физической клавише: код от Compose
                            // зависит от раскладки, и назначенный в английской
                            // хоткей не срабатывал в русской
                            val handled = clientState.processHotkey(
                                key = com.bylins.client.hotkeys.PhysicalKey.of(event),
                                isCtrlPressed = event.isCtrlPressed,
                                isAltPressed = event.isAltPressed,
                                isShiftPressed = event.isShiftPressed
                            )

                            if (handled) {
                                // Запоминаем клавишу чтобы поглотить KeyUp
                                lastHandledKey = event.key
                            } else if (
                                InputFocusPolicy.shouldFocusInput(
                                    key = event.key,
                                    isCtrlPressed = event.isCtrlPressed,
                                    isAltPressed = event.isAltPressed,
                                    isMetaPressed = event.isMetaPressed,
                                    secondaryFieldFocused = secondaryTextFieldFocused,
                                    // На вкладках настроек, триггеров и прочих набранное
                                    // должно попадать в тамошние поля, а не в строку ввода
                                    gameTabFocused = selectedTabId == "main"
                                )
                            ) {
                                // Если hotkey не обработан и клавишей можно печатать,
                                // фокусируем input для обычного ввода
                                inputFocusRequester.requestFocus()
                            }

                            handled
                        }
                        KeyEventType.KeyUp -> {
                            // Поглощаем KeyUp если соответствующий KeyDown был обработан
                            if (lastHandledKey == event.key) {
                                lastHandledKey = null
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                },
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Верхняя панель подключения
                ConnectionPanel(
                    clientState = clientState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )

                Divider()

                // Вкладки
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = appColorScheme.surface,
                    contentColor = appColorScheme.onSurface,
                    edgePadding = 0.dp
                ) {
                    visibleTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabId = tab.id },
                            text = {
                                if (tab.id == "msdp") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(
                                                    color = if (msdpEnabled) appColorScheme.success else appColorScheme.error,
                                                    shape = androidx.compose.foundation.shape.CircleShape
                                                )
                                        )
                                        Text(tab.name)
                                    }
                                } else {
                                    Text(tab.name)
                                }
                            }
                        )
                    }
                }

                Divider()

                // Основная область в зависимости от выбранной вкладки
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTabId) {
                        "main" -> {
                            // Вывод от сервера с боковой панелью
                            val miniMapWidth by clientState.miniMapWidth.collectAsState()
                            val density = LocalDensity.current
                            val collapsed by clientState.sidePanelCollapsed.collectAsState()

                            // Ширина области (для авто-сворачивания в узком окне).
                            // Порог задаём по ширине, которая остаётся панели вывода:
                            // если вывод становится уже минимума — прячем боковую панель.
                            // Актуальная ширина для долгоживущего drag-жеста (pointerInput(Unit)
                            // захватил бы устаревшее значение)
                            val miniMapWidthRef by rememberUpdatedState(miniMapWidth)

                            // Идёт ли перетаскивание разделителя и предпросмотр свёрнутого вида
                            var isDraggingDivider by remember { mutableStateOf(false) }
                            var dragCollapsePreview by remember { mutableStateOf(false) }

                            var areaWidthPx by remember { mutableStateOf(0) }
                            val minOutputWidthDp = 560f
                            val areaWidthDp = areaWidthPx / density.density
                            val outputWidthIfExpanded = areaWidthDp - miniMapWidth - 18f // язычок + разделитель
                            val isNarrow = areaWidthPx > 0 && outputWidthIfExpanded < minOutputWidthDp

                            // В узком окне сворачиваем автоматически, при возврате к широкому
                            // восстанавливаем состояние, которое было до сворачивания.
                            var collapsedBeforeNarrow by remember { mutableStateOf<Boolean?>(null) }
                            LaunchedEffect(isNarrow) {
                                if (isNarrow) {
                                    if (collapsedBeforeNarrow == null) {
                                        collapsedBeforeNarrow = collapsed
                                        clientState.setSidePanelCollapsed(true)
                                    }
                                } else {
                                    collapsedBeforeNarrow?.let {
                                        clientState.setSidePanelCollapsed(it)
                                        collapsedBeforeNarrow = null
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { areaWidthPx = it.width }
                            ) {
                                OutputPanel(
                                    clientState = clientState,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )

                                // Кнопка сворачивания/разворачивания боковой панели
                                Box(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .fillMaxHeight()
                                        .background(appColorScheme.surface)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable {
                                            if (collapsed) {
                                                // Разворачиваем: если ширина осталась «схлопнутой»
                                                // после утаскивания разделителя — даём удобную.
                                                if (miniMapWidth < RESTORE_MIN_WIDTH_DP) {
                                                    clientState.setMiniMapWidth(RESTORE_MIN_WIDTH_DP)
                                                }
                                                clientState.setSidePanelCollapsed(false)
                                            } else {
                                                clientState.setSidePanelCollapsed(true)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (collapsed) "◀" else "▶",
                                        color = appColorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }

                                // Разделитель остаётся в композиции всё время жеста, даже когда
                                // панель «свёрнута» предпросмотром: иначе его удаление обрывает
                                // drag и вернуть панель, не отпуская кнопку, невозможно.
                                if (!collapsed || isDraggingDivider) {
                                    // Draggable divider
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .fillMaxHeight()
                                            .background(appColorScheme.border)
                                            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
                                            .pointerInput(Unit) {
                                                // Аккумулятор от старта жеста: dragAmount — дельта за
                                                // событие, а ширина зажата снизу (150dp). Без накопления
                                                // «утащить за минимум» невозможно — панель залипала сжатой.
                                                var startWidth = 0
                                                var accumDp = 0f
                                                detectDragGestures(
                                                    onDragStart = {
                                                        startWidth = miniMapWidthRef
                                                        accumDp = 0f
                                                        isDraggingDivider = true
                                                        dragCollapsePreview = false
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        // Ограничиваем накопление, чтобы возврат был
                                                        // отзывчивым (нет «мёртвого хода» за краем)
                                                        accumDp = (accumDp + dragAmount.x / density.density)
                                                            .coerceAtMost(startWidth.toFloat())
                                                        val requested = (startWidth - accumDp).toInt()
                                                        if (requested < COLLAPSE_DRAG_THRESHOLD_DP) {
                                                            // Предпросмотр свёрнутого вида; окончательное
                                                            // состояние применяем при отпускании
                                                            dragCollapsePreview = true
                                                        } else {
                                                            dragCollapsePreview = false
                                                            clientState.setMiniMapWidth(requested)
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        clientState.setSidePanelCollapsed(dragCollapsePreview)
                                                        dragCollapsePreview = false
                                                        isDraggingDivider = false
                                                    },
                                                    onDragCancel = {
                                                        clientState.setSidePanelCollapsed(dragCollapsePreview)
                                                        dragCollapsePreview = false
                                                        isDraggingDivider = false
                                                    }
                                                )
                                            }
                                    )

                                    // Боковая панель со статусом. Предпросмотр скрытия действует
                                    // ТОЛЬКО во время жеста — иначе после сворачивания перетаскиванием
                                    // разворот кнопкой давал пустую панель нулевой ширины.
                                    if (!(isDraggingDivider && dragCollapsePreview)) {
                                    Column(
                                        modifier = Modifier
                                            .width(miniMapWidth.dp)
                                            .fillMaxHeight()
                                    ) {
                                        StatusPanel(
                                            clientState = clientState,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight()
                                        )
                                    }
                                    }
                                }
                            }
                        }
                        "triggers" -> {
                            // Панель триггеров
                            TriggersPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "aliases" -> {
                            // Панель алиасов
                            AliasesPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "hotkeys" -> {
                            // Панель горячих клавиш
                            HotkeysPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "context" -> {
                            // Панель контекстных команд
                            ContextCommandsPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "stats" -> {
                            // Панель статистики
                            StatsPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "graphs" -> {
                            // Панель графиков
                            StatsGraphPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "map" -> {
                            // Панель карты
                            MapPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "scripts" -> {
                            // Панель скриптов
                            ScriptsPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "plugins" -> {
                            // Панель плагинов с подвкладками
                            PluginsPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "msdp" -> {
                            // Панель MSDP данных
                            MsdpPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "gmcp" -> {
                            // Панель GMCP данных
                            GmcpPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "settings" -> {
                            // Панель настроек
                            SettingsPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "profiles" -> {
                            // Панель профилей персонажей
                            ProfilesPanel(
                                clientState = clientState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Divider()

                // Поле ввода команд с контекстными командами
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    // Контекстные команды (Alt+1-0) - над полем ввода
                    ContextCommandBar(
                        clientState = clientState,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    InputPanel(
                        clientState = clientState,
                        focusRequester = inputFocusRequester,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        }
        }
    }
}
