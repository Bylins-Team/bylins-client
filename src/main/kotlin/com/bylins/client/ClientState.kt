package com.bylins.client

import mu.KotlinLogging
import com.bylins.client.aliases.AliasManager
import com.bylins.client.commands.CommandContext
import com.bylins.client.commands.CommandProcessor
import com.bylins.client.config.ConfigManager
import com.bylins.client.config.DefaultData
import com.bylins.client.hotkeys.HotkeyManager
import com.bylins.client.logging.LogManager
import com.bylins.client.logging.UiLogBuffer
import com.bylins.client.network.TelnetClient
import com.bylins.client.stats.SessionStats
import com.bylins.client.tabs.TabManager
import com.bylins.client.triggers.TriggerManager
import com.bylins.client.variables.VariableManager
import com.bylins.client.status.StatusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.io.File

private val logger = KotlinLogging.logger("ClientState")

/**
 * Вкладки верхнего уровня, которые нельзя скрыть (иначе их не вернуть без правки конфига).
 * "settings" обязана быть всегда доступна.
 */
val PERMANENT_TAB_IDS = setOf("settings")

class ClientState {
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Отправка команд серверу — строго по одной за раз.
     *
     * Порядок команд должен совпадать с порядком, в котором их дали: игрок
     * набрал, скрипт выполнил, агент прислал пачку. Общий пул этого не
     * обещает.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sendScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val configManager = ConfigManager()

    // Флаг для предотвращения множественного сохранения при инициализации
    private var isInitializing = true

    // Debounce для сохранения конфига
    private var saveConfigJob: kotlinx.coroutines.Job? = null
    private val saveConfigDebounceMs = 500L

    // Авто-переподключение: ручной разрыв не должен запускать reconnect
    private var userInitiatedDisconnect = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private val reconnectDelayMs = 5000L

    // Время последнего срабатывания хоткея (для блокировки дублирования ввода)
    @Volatile
    private var lastHotkeyTimestamp = 0L
    private val hotkeyInputBlockMs = 200L // Блокируем ввод на 200мс после хоткея

    /**
     * Проверяет, был ли недавно обработан хоткей (для блокировки текстового ввода)
     */
    fun wasHotkeyRecentlyProcessed(): Boolean {
        return System.currentTimeMillis() - lastHotkeyTimestamp < hotkeyInputBlockMs
    }

    // Менеджеры инициализируются первыми
    private val aliasManager = AliasManager(
        onCommand = { command ->
            // Callback для отправки команд из алиасов (без рекурсии)
            // Подставляем переменные перед отправкой
            val substituted = variableManager.substituteVariables(command)
            sendRaw(substituted)
        },
        onAliasFired = { alias, command, groups ->
            // Уведомляем скрипты о срабатывании алиаса
            if (::scriptManager.isInitialized) {
                scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_ALIAS, alias, command, groups)
            }

            // Уведомляем плагины о срабатывании алиаса
            if (::pluginManager.isInitialized) {
                pluginEventBus.post(com.bylins.client.plugins.events.AliasFiredEvent(
                    aliasId = alias.id,
                    aliasName = alias.name,
                    input = command,
                    groups = groups.values.toList()
                ))
            }
        }
    )

    private val triggerManager = TriggerManager(
        onCommand = { command ->
            // Callback для отправки команд из триггеров
            send(command)
        },
        onTriggerFired = { trigger, line, groups ->
            // Уведомляем скрипты о срабатывании триггера
            if (::scriptManager.isInitialized) {
                scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_TRIGGER, trigger, line, groups)
            }

            // Уведомляем плагины о срабатывании триггера
            if (::pluginManager.isInitialized) {
                pluginEventBus.post(com.bylins.client.plugins.events.TriggerFiredEvent(
                    triggerId = trigger.id,
                    triggerName = trigger.name,
                    line = line,
                    groups = groups.values.toList()
                ))
            }
        }
    )

    private val hotkeyManager = HotkeyManager { command ->
        // Callback для отправки команд из хоткеев
        send(command)
    }

    init {
        // Без физического кода клавиши хоткеи привязаны к раскладке, в которой
        // их назначили. Это надо видеть в логе, а не выяснять наощупь.
        if (!com.bylins.client.hotkeys.PhysicalKey.available &&
            com.bylins.client.OperatingSystem.current == com.bylins.client.OperatingSystem.Windows
        ) {
            logger.warn {
                "Физический код клавиши недоступен: хоткеи будут зависеть от раскладки. " +
                    "Нужен параметр запуска --add-opens=java.desktop/java.awt.event=ALL-UNNAMED"
            }
        }

        // Триггеры и хоткеи с областью действия должны знать, где игрок сейчас
        triggerManager.getCurrentRoom = { mapManager.getCurrentRoom() }
        hotkeyManager.getCurrentRoom = { mapManager.getCurrentRoom() }
    }

    private val logManager = LogManager()
    private val sessionStats = SessionStats()
    private val statsHistory = com.bylins.client.stats.StatsHistory()
    private val chartManager = com.bylins.client.stats.ChartManager()
    private val scriptStorage = com.bylins.client.scripting.ScriptStorage()
    private val soundManager = com.bylins.client.audio.SoundManager()
    private val variableManager = VariableManager()
    val statusManager = StatusManager(variableManager)
    private val tabManager = TabManager()

    // Хранилище триггеров из скриптов
    private data class ScriptTrigger(
        val id: String,
        val pattern: Regex,
        val callback: (String, Map<Int, String>) -> Unit,
        var enabled: Boolean = true
    )
    private val scriptTriggers = java.util.concurrent.ConcurrentHashMap<String, ScriptTrigger>()

    /**
     * Проверяет скриптовые триггеры на совпадение с строкой
     */
    private fun checkScriptTriggers(line: String) {
        if (line.contains("Вых") || line.contains("[") && line.contains("]")) {
        }
        for (trigger in scriptTriggers.values) {
            if (!trigger.enabled) continue

            try {
                val matchResult = trigger.pattern.find(line)
                if (matchResult != null) {
                    // Формируем groups как Map<Int, String>
                    val groups = mutableMapOf<Int, String>()
                    matchResult.groupValues.forEachIndexed { index, value ->
                        groups[index] = value
                    }

                    // Вызываем callback
                    try {
                        trigger.callback(line, groups)
                    } catch (e: Exception) {
                        logger.error { "[ScriptAPI] Error in trigger callback: ${e.message}" }
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                logger.error { "[ScriptAPI] Error matching trigger ${trigger.id}: ${e.message}" }
            }
        }
    }

    // Для throttling звуковых уведомлений
    private var lastLowHpSoundTime = 0L

    // Callback для MapManager
    private val mapManagerOnRoomEnter: (com.bylins.client.mapper.Room) -> Unit = { room ->
        // Запускаем уведомления асинхронно чтобы избежать deadlock при вызове из API
        scope.launch {
            // Уведомляем скрипты о входе в комнату
            if (::scriptManager.isInitialized) {
                scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_ROOM_ENTER, room)
            }

            // Уведомляем плагины о входе в комнату
            if (::pluginManager.isInitialized) {
                pluginEventBus.post(com.bylins.client.plugins.events.RoomEnterEvent(
                    roomId = room.id,
                    roomName = room.name,
                    fromDirection = null // TODO: передать направление откуда пришли
                ))
            }

            // Обрабатываем контекстные команды при входе в комнату
            contextCommandManager.onRoomEnter(room)

            // Обрабатываем правила контекстных команд из профилей
            for (profile in profileManager.getActiveProfiles()) {
                if (profile.contextCommandRules.isNotEmpty()) {
                    logger.debug { "Processing ${profile.contextCommandRules.size} room/zone context rules from profile ${profile.name}" }
                }
                contextCommandManager.processRoomRules(room, profile.contextCommandRules)
            }
        }
    }

    // --- Проход по маршруту ---
    // Объявлено до mapManager намеренно: карта зовёт onPathAdvanced, а поля,
    // объявленные ниже блока init, на момент его выполнения ещё null.

    private val _walking = MutableStateFlow(false)
    /** Идёт ли сейчас автоматический проход по маршруту. */
    val walking: StateFlow<Boolean> = _walking

    private var walkWatchdog: kotlinx.coroutines.Job? = null

    /** Сколько ждать смены комнаты, прежде чем признать шаг непрошедшим. */
    private val walkStepTimeoutMs = 4000L

    private val pathWalker: com.bylins.client.mapper.PathWalker =
        com.bylins.client.mapper.PathWalker(
            currentRoomId = { mapManager.getCurrentRoom()?.id },
            send = { command ->
                // Через send(), а не sendRaw(): шаг должен вести себя как
                // обычная команда игрока — попасть в лог и в эхо
                send(command)
                armWalkWatchdog()
            },
            notify = { message -> telnetClient.addLocalOutput(message) }
        ).also { walker ->
            walker.onStopped = {
                walkWatchdog?.cancel()
                walkWatchdog = null
                _walking.value = false
                mapManager.clearPath()
            }
        }

    // MapManager - может быть пересоздан при смене профиля
    private var mapManager: com.bylins.client.mapper.MapManager = com.bylins.client.mapper.MapManager(
        onRoomEnter = mapManagerOnRoomEnter
    ).also { manager ->
        // Ходок узнаёт о смене комнаты последним — когда маршрут уже пересчитан
        manager.onPathAdvanced = { pathWalker.onRoomChanged() }
    }

    // Менеджер контекстных команд (инициализируется в init)
    var contextCommandManager: com.bylins.client.contextcommands.ContextCommandManager =
        com.bylins.client.contextcommands.ContextCommandManager(
            onCommand = { command -> send(command) },
            getCurrentRoom = { mapManager.getCurrentRoom() }
        )
        private set

    // CommandProcessor для обработки # команд
    private val commandContext = object : CommandContext {
        override fun addLocalOutput(text: String) {
            telnetClient.addLocalOutput(text)
        }
        override fun sendRaw(command: String) {
            this@ClientState.sendRaw(command)
        }
        override fun startWalk(targetRoomId: String): Boolean = this@ClientState.startWalk(targetRoomId)
        override fun walkDirections(
            directions: List<com.bylins.client.mapper.Direction>,
            label: String
        ): Boolean = this@ClientState.walkDirections(directions, label)
        override fun stopWalk() = this@ClientState.stopWalk()
        override fun getAllZones(): List<String> = this@ClientState.getAllZones()
        override fun getZoneStatistics(): Map<String, Int> = this@ClientState.getZoneStatistics()
        override fun detectAndAssignZones() = this@ClientState.detectAndAssignZones()
        override fun clearAllZones() = this@ClientState.clearAllZones()
    }
    private lateinit var commandProcessor: CommandProcessor



    // Кодировка для telnet (конфигурируется пользователем)
    private var _encoding = "UTF-8"
    val encoding: String
        get() = _encoding

    // Ширина боковой панели с миникартой
    private val _miniMapWidth = MutableStateFlow(250)
    val miniMapWidth: StateFlow<Int> = _miniMapWidth

    // Высота миникарты в статус-панели
    private val _miniMapHeight = MutableStateFlow(300)
    val miniMapHeight: StateFlow<Int> = _miniMapHeight

    // Ширина панели заметок зоны на вкладке Карта
    private val _zonePanelWidth = MutableStateFlow(220)
    val zonePanelWidth: StateFlow<Int> = _zonePanelWidth

    // Тема оформления (DARK, LIGHT, DARK_BLUE, SOLARIZED_DARK, MONOKAI)
    private val _currentTheme = MutableStateFlow("DARK")
    val currentTheme: StateFlow<String> = _currentTheme

    // Настройки шрифта
    private val _fontFamily = MutableStateFlow("MONOSPACE")
    val fontFamily: StateFlow<String> = _fontFamily

    private val _fontSize = MutableStateFlow(14)
    val fontSize: StateFlow<Int> = _fontSize

    // Игнорировать состояние NumLock для хоткеев
    private val _ignoreNumLock = MutableStateFlow(false)
    val ignoreNumLock: StateFlow<Boolean> = _ignoreNumLock

    // Скрытые вкладки
    private val _hiddenTabs = MutableStateFlow<Set<String>>(emptySet())
    val hiddenTabs: StateFlow<Set<String>> = _hiddenTabs

    // Состояние свёрнутости групп статус-панели
    private val _statusGroupCollapsed = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    // --- Управление клиентом из плагинов и разрешения на него ---

    /** Реализация «нажатий кнопок» для плагинов (профили, триггеры, соединение). */
    private val clientControl: com.bylins.client.plugins.ClientControl by lazy {
        com.bylins.client.plugins.ClientControlImpl(this)
    }

    /** Выданные пользователем разрешения: id плагина → набор id разрешений. */
    private val _pluginPermissions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val pluginPermissions: StateFlow<Map<String, Set<String>>> = _pluginPermissions

    fun hasPluginPermission(pluginId: String, permission: com.bylins.client.plugins.PluginPermission): Boolean =
        _pluginPermissions.value[pluginId]?.contains(permission.id) == true

    /** Выдаёт/отзывает разрешение плагину (действует немедленно). */
    fun setPluginPermission(
        pluginId: String,
        permission: com.bylins.client.plugins.PluginPermission,
        granted: Boolean
    ) {
        val current = _pluginPermissions.value[pluginId] ?: emptySet()
        val updated = if (granted) current + permission.id else current - permission.id
        _pluginPermissions.value = _pluginPermissions.value + (pluginId to updated)
        saveConfig()
        logger.info { "Plugin '$pluginId' permission '${permission.id}' granted=$granted" }
    }

    // Свёрнута ли правая (боковая) панель на вкладке «Вывод»
    /**
     * Сколько прошлых версий config.json держать рядом (config.json.1 и далее).
     * Копии — единственное, что спасает, если клиент записал в файл неполное
     * состояние: заметить это можно много позже.
     */
    private val _configBackups = MutableStateFlow(com.bylins.client.config.DEFAULT_CONFIG_BACKUPS)
    val configBackups: StateFlow<Int> = _configBackups

    fun setConfigBackups(count: Int) {
        val value = count.coerceIn(0, com.bylins.client.config.MAX_CONFIG_BACKUPS)
        if (_configBackups.value == value) return
        _configBackups.value = value
        saveConfig()
    }

    private val _sidePanelCollapsed = MutableStateFlow(false)
    val sidePanelCollapsed: StateFlow<Boolean> = _sidePanelCollapsed
    fun setSidePanelCollapsed(collapsed: Boolean) {
        if (_sidePanelCollapsed.value == collapsed) return
        _sidePanelCollapsed.value = collapsed
        saveConfig()
    }

    // Целевой профиль для добавления хоткеев/триггеров/алиасов в UI панелях (null = база)
    private val _panelTargetProfileId = MutableStateFlow<String?>(null)
    val panelTargetProfileId: StateFlow<String?> = _panelTargetProfileId

    fun setPanelTargetProfileId(profileId: String?) {
        _panelTargetProfileId.value = profileId
    }

    // Объявлены до telnetClient: он сразу получает обработчик локального
    // вывода, и тот не должен опираться на ещё не созданные поля
    private val pluginEventBus = com.bylins.client.plugins.events.EventBus()
    private val localOutputAnsi = com.bylins.client.ui.AnsiParser()

    private val telnetClient = TelnetClient(this, _encoding).apply {
        // Собственный вывод клиента плагинам тоже виден: иначе агент не
        // получает ответа на свои же #-команды и считает, что они не сработали
        onLocalOutput = { text -> postLocalOutput(text) }
    }

    /** Локальный вывод — плагинам, построчно и без ANSI, как строки сервера. */
    private fun postLocalOutput(text: String) {
        text.split("\n").forEach { line ->
            val clean = localOutputAnsi.stripAnsi(line)
            if (clean.isNotBlank()) {
                pluginEventBus.post(com.bylins.client.plugins.events.LocalOutputEvent(clean))
            }
        }
    }

    // Скриптинг - инициализируется позже
    private lateinit var scriptManager: com.bylins.client.scripting.ScriptManager

    // Плагины - инициализируются после скриптинга
    private lateinit var pluginManager: com.bylins.client.plugins.PluginManager
    val pluginTabManager = com.bylins.client.plugins.ui.PluginTabManager()

    // Профили персонажей - инициализируется после скриптинга
    lateinit var profileManager: com.bylins.client.profiles.ProfileManager
        private set

    val isConnected: StateFlow<Boolean> = telnetClient.isConnected
    val receivedData: StateFlow<String> = telnetClient.receivedData
    // Снимок главной вкладки с абсолютной нумерацией строк (для панели вывода)
    val mainOutputSnapshot = telnetClient.snapshot

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _msdpData = MutableStateFlow<Map<String, Any>>(emptyMap())
    val msdpData: StateFlow<Map<String, Any>> = _msdpData

    // Флаг: фокус на вторичном текстовом поле (заметки зоны и т.д.)
    private val _secondaryTextFieldFocused = MutableStateFlow(false)
    val secondaryTextFieldFocused: StateFlow<Boolean> = _secondaryTextFieldFocused
    fun setSecondaryTextFieldFocused(focused: Boolean) {
        _secondaryTextFieldFocused.value = focused
    }

    // Событие для запроса фокуса на поле ввода (инкрементируется для trigger)
    private val _requestInputFocus = MutableStateFlow(0)
    val requestInputFocus: StateFlow<Int> = _requestInputFocus
    fun requestInputFocus() {
        _requestInputFocus.value++
    }

    // Запрос на открытие/фокус поиска по выводу (Ctrl+F из любого места)
    private val _outputSearchRequest = MutableStateFlow(0)
    val outputSearchRequest: StateFlow<Int> = _outputSearchRequest
    fun requestOutputSearch() {
        _outputSearchRequest.value++
    }
    // Последний обработанный счётчик запроса поиска. Переживает пересоздание
    // OutputPanel (смена верхних вкладок), чтобы повторная композиция не открывала
    // поиск из-за уже ненулевого счётчика.
    var lastHandledOutputSearchRequest: Int = 0

    // MSDP статус (включён ли протокол)
    private val _msdpEnabled = MutableStateFlow(false)
    val msdpEnabled: StateFlow<Boolean> = _msdpEnabled

    // Список reportable переменных MSDP (полученный от сервера)
    private val _msdpReportableVariables = MutableStateFlow<List<String>>(emptyList())
    val msdpReportableVariables: StateFlow<List<String>> = _msdpReportableVariables

    // Список переменных, на которые включён REPORT
    private val _msdpReportedVariables = MutableStateFlow<Set<String>>(emptySet())
    val msdpReportedVariables: StateFlow<Set<String>> = _msdpReportedVariables

    // Ref-counted MSDP subscriptions: variable -> set of subscriber IDs
    private val msdpSubscribers = mutableMapOf<String, MutableSet<String>>()

    // GMCP данные (Generic MUD Communication Protocol)
    private val _gmcpData = MutableStateFlow<Map<String, kotlinx.serialization.json.JsonElement>>(emptyMap())
    val gmcpData: StateFlow<Map<String, kotlinx.serialization.json.JsonElement>> = _gmcpData

    // Map context menu commands (registered by scripts)
    private val mapContextCommands = mutableMapOf<String, (com.bylins.client.mapper.Room) -> Unit>()

    // Профили подключений
    private val _connectionProfiles = MutableStateFlow<List<com.bylins.client.connection.ConnectionProfile>>(
        com.bylins.client.connection.ConnectionProfile.createDefaultProfiles()
    )
    val connectionProfiles: StateFlow<List<com.bylins.client.connection.ConnectionProfile>> = _connectionProfiles

    private val _currentProfileId = MutableStateFlow<String?>(null)
    val currentProfileId: StateFlow<String?> = _currentProfileId

    // Доступ к менеджерам
    val triggers = triggerManager.triggers
    val aliases = aliasManager.aliases
    val hotkeys = hotkeyManager.hotkeys

    // Доступ к логированию
    val isLogging = logManager.isLogging
    val currentLogFile = logManager.currentLogFile

    // Доступ к статистике
    val stats = sessionStats.stats
    val hpHistory = statsHistory.hpHistory
    val manaHistory = statsHistory.manaHistory
    val movementHistory = statsHistory.movementHistory

    // Доступ к динамическим графикам
    val dynamicCharts = chartManager.charts

    // Доступ к звукам
    val soundEnabled = soundManager.soundEnabled
    val soundVolume = soundManager.volume

    // Доступ к переменным
    val variables = variableManager.variables

    // Доступ к вкладкам
    val tabs = tabManager.tabs
    val activeTabId = tabManager.activeTabId

    // Долгоживущее состояние прокрутки/выделения панелей вывода (по id вкладки).
    // Живёт в ClientState, чтобы переживать уход OutputPanel из композиции при
    // переключении верхнеуровневых вкладок (позиция/режим/выделение не сбрасываются).
    private val outputViewHolders =
        mutableMapOf<String, com.bylins.client.ui.components.output.OutputViewHolder>()

    // Доля разделителя панели вывода по id вкладки (персистится в конфиг)
    private val outputSplitFractions = mutableMapOf<String, Float>()

    fun outputViewHolder(tabId: String): com.bylins.client.ui.components.output.OutputViewHolder =
        outputViewHolders.getOrPut(tabId) {
            com.bylins.client.ui.components.output.OutputViewHolder().also { h ->
                outputSplitFractions[tabId]?.let { h.splitFraction = it }
            }
        }

    /** Снимок сохранённых долей разделителя (для записи в конфиг). */
    fun getOutputSplitFractions(): Map<String, Float> = outputSplitFractions.toMap()

    /** Восстанавливает доли разделителя из конфига (на загрузке). */
    fun loadOutputSplitFractions(map: Map<String, Float>) {
        outputSplitFractions.clear()
        outputSplitFractions.putAll(map)
        // Применяем к уже созданным holder'ам (если есть)
        map.forEach { (id, f) -> outputViewHolders[id]?.splitFraction = f }
    }

    /** Меняет долю разделителя вкладки и персистит. */
    fun setOutputSplitFraction(tabId: String, fraction: Float) {
        val clamped = fraction.coerceIn(0.1f, 0.9f)
        outputSplitFractions[tabId] = clamped
        outputViewHolders[tabId]?.splitFraction = clamped
        saveConfig()
    }

    // Выбранная подвкладка в панели плагинов (сохраняется между переключениями)
    var selectedPluginSubTab: String = "config"

    // Доступ к карте (используем getters для поддержки переключения mapManager)
    val mapRooms get() = mapManager.rooms
    val currentRoomId get() = mapManager.currentRoomId
    val mapEnabled get() = mapManager.mapEnabled
    val activePath get() = mapManager.activePath
    val pathTargetRoomId get() = mapManager.targetRoomId
    val pathHighlightRoomIds get() = mapManager.pathHighlightRoomIds
    val pathHighlightTargetId get() = mapManager.pathHighlightTargetId
    val zoneNotes get() = mapManager.zoneNotes
    val zoneNames get() = mapManager.zoneNames

    /**
     * Подпись зоны для UI: «Название (53)», а при отсутствии имени — «Зона 53».
     *
     * Имя есть только у зон, заведённых в карте; комнаты же ссылаются на любой
     * номер, поэтому голый id встречается регулярно и его нужно показывать
     * осмысленно.
     */
    fun zoneLabel(zoneId: String?): String {
        if (zoneId.isNullOrEmpty()) return "неизвестная зона"
        val name = mapManager.zoneNames.value[zoneId]
        // Имя «Зона 1022» номер уже содержит — «Зона 1022 (1022)» ни к чему
        return when {
            name.isNullOrBlank() -> "Зона $zoneId"
            name == "Зона $zoneId" -> name
            else -> "$name ($zoneId)"
        }
    }
    val mapViewCenterRoomId get() = mapManager.viewCenterRoomId

    fun getZoneNotes(zoneName: String): String = mapManager.getZoneNotes(zoneName)
    fun getZoneProperties(zoneId: String): Map<String, String> = mapManager.getZoneProperties(zoneId)
    fun setZoneNotes(zoneName: String, notes: String) = mapManager.setZoneNotes(zoneName, notes)
    fun getZoneName(zoneId: String): String? = mapManager.getZoneName(zoneId)
    fun setZoneName(zoneId: String, areaName: String) = mapManager.setZoneName(zoneId, areaName)
    fun setMapViewCenterRoom(roomId: String?) = mapManager.setViewCenterRoom(roomId)

    /**
     * Возвращает список существующих файлов карт
     */
    fun getExistingMapFiles(): List<String> = com.bylins.client.mapper.MapDatabase.getExistingMapFiles()

    /**
     * Переключает базу данных карт на указанный файл
     * Вызывается при смене профиля подключения
     */
    fun switchMapDatabase(mapFile: String) {
        val currentMapFile = mapManager.getDbFileName()
        if (currentMapFile == mapFile) {
            logger.debug { "Map database already using $mapFile, skipping switch" }
            return
        }

        logger.info { "Switching map database from $currentMapFile to $mapFile" }

        // Закрываем старый MapManager
        mapManager.shutdown()

        // Создаём новый MapManager с новым файлом БД
        mapManager = com.bylins.client.mapper.MapManager(
            dbFileName = mapFile,
            onRoomEnter = mapManagerOnRoomEnter
        )
        // Ходок ходит по новой карте: без перевешивания он остался бы слушать
        // закрытый MapManager и молча перестал делать шаги
        mapManager.onPathAdvanced = { pathWalker.onRoomChanged() }
        if (pathWalker.isWalking) pathWalker.stop("Сменилась карта")

        // Обновляем ссылку на getCurrentRoom в contextCommandManager
        contextCommandManager.updateGetCurrentRoom { mapManager.getCurrentRoom() }

        logger.info { "Switched to map database: $mapFile (${mapManager.rooms.value.size} rooms)" }
    }

    init {
        // Регистрируем shutdown hook для корректного завершения
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
        })

        // Конфиг читаем ДО инициализации плагинов: выданные разрешения должны
        // быть известны уже в onEnable, иначе плагин стартует «без прав» и
        // вынужден их потом переспрашивать.
        val configData = configManager.loadConfig()
        _pluginPermissions.value = configData.pluginPermissions
        _configBackups.value = configData.configBackups

        // Инициализируем скриптинг
        initializeScripting()

        // Инициализируем плагины
        initializePlugins()

        // Инициализируем обработчик команд
        commandProcessor = CommandProcessor(
            scope = scope,
            context = commandContext,
            mapManager = mapManager,
            soundManager = soundManager,
            contextCommandManager = contextCommandManager,
            getScriptManager = { if (::scriptManager.isInitialized) scriptManager else null },
            getPluginManager = { if (::pluginManager.isInitialized) pluginManager else null }
        )

        // Инициализируем профили персонажей
        initializeProfiles()

        // Конфиг уже прочитан выше (до инициализации плагинов)

        // Загружаем кодировку из конфига
        _encoding = configData.encoding
        telnetClient.setEncoding(_encoding)

        // Загружаем размеры миникарты из конфига
        _miniMapWidth.value = configData.miniMapWidth
        _miniMapHeight.value = configData.miniMapHeight
        _zonePanelWidth.value = configData.zonePanelWidth

        // Загружаем тему из конфига
        _currentTheme.value = configData.theme

        // Загружаем настройки шрифта из конфига
        _fontFamily.value = configData.fontFamily
        _fontSize.value = configData.fontSize
        _ignoreNumLock.value = configData.ignoreNumLock
        // Постоянные вкладки не могут быть скрыты даже через конфиг
        _hiddenTabs.value = configData.hiddenTabs - PERMANENT_TAB_IDS
        _statusGroupCollapsed.value = configData.statusGroupCollapsed
        _sidePanelCollapsed.value = configData.sidePanelCollapsed
        loadOutputSplitFractions(configData.outputSplitFractions)
        logManager.setLogWithColors(configData.logWithColors)

        // Устанавливаем callback для сохранения состояния свёрнутости групп
        statusManager.onCollapsedStateChanged = { groupId, collapsed ->
            _statusGroupCollapsed.value = _statusGroupCollapsed.value + (groupId to collapsed)
            saveConfig()
        }

        // Загружаем профили подключений из конфига (должно быть до lastMapRoomId)
        _connectionProfiles.value = configData.connectionProfiles
        _currentProfileId.value = configData.currentProfileId

        // Переключаемся на карту из текущего профиля и грузим доли разделителя сервера
        configData.currentProfileId?.let { profileId ->
            val profile = _connectionProfiles.value.find { it.id == profileId }
            profile?.let {
                switchMapDatabase(it.mapFile)
                // Доли разделителя из профиля; если пусто — миграция из глобального конфига
                loadOutputSplitFractions(it.outputSplitFractions.ifEmpty { configData.outputSplitFractions })
            }
        }

        // Загружаем последнюю просмотренную комнату карты из конфига
        // (делаем ПОСЛЕ переключения на правильную карту)
        logger.info { "Loading lastMapRoomId from config: ${configData.lastMapRoomId}, map has ${mapManager.rooms.value.size} rooms" }
        configData.lastMapRoomId?.let { roomId ->
            // Устанавливаем комнату центра обзора в mapManager, если комната существует на карте
            val room = mapManager.getRoom(roomId)
            if (room != null) {
                mapManager.setViewCenterRoom(roomId)
                logger.info { "Restored last map view center: $roomId (${room.name})" }
            } else {
                logger.warn { "Last map room $roomId not found on map" }
            }
        }

        if (configData.triggers.isEmpty() && configData.aliases.isEmpty() && configData.hotkeys.isEmpty() && configData.tabs.isEmpty()) {
            // Если конфига нет, загружаем стандартные триггеры, алиасы, хоткеи и вкладки
            DefaultData.getDefaultAliases().forEach { addAlias(it) }
            DefaultData.getDefaultTriggers().forEach { addTrigger(it) }
            DefaultData.getDefaultHotkeys().forEach { addHotkey(it) }
            DefaultData.getDefaultTabs().forEach { addTab(it) }
        } else {
            // Загружаем сохранённую конфигурацию
            configData.triggers.forEach { addTrigger(it) }
            configData.aliases.forEach { addAlias(it) }
            configData.hotkeys.forEach { addHotkey(it) }
            variableManager.loadVariables(configData.variables)
            tabManager.loadTabs(configData.tabs)
        }

        // Подгружаем профильные вкладки текущего сервера поверх глобальных
        // и применяем сохранённый порядок вкладок этого сервера
        configData.currentProfileId?.let { profileId ->
            _connectionProfiles.value.find { it.id == profileId }?.let { profile ->
                tabManager.setProfileTabs(profile.tabs.map { it.toTab() })
                tabManager.applyPerProfileLogs(profile.tabLogs)
                tabManager.applyTabOrder(profile.tabOrder)
            }
        }

        // Загружаем правила контекстных команд
        val contextRules = configData.contextCommandRules.mapNotNull { it.toRule() }
        contextCommandManager.loadRules(contextRules)
        contextCommandManager.setMaxQueueSize(configData.contextCommandMaxQueueSize)

        // Восстанавливаем стек профилей персонажей
        if (::profileManager.isInitialized && configData.activeProfileStack.isNotEmpty()) {
            profileManager.restoreStack(configData.activeProfileStack)
        }

        // Подписываемся на системные логи и пересылаем их в вкладку "Логи"
        scope.launch {
            var lastSize = 0
            UiLogBuffer.entries.collect { entries ->
                if (entries.size > lastSize) {
                    // Добавляем только новые записи
                    val newEntries = entries.drop(lastSize)
                    newEntries.forEach { entry ->
                        tabManager.addToLogsTab(entry.formatted())
                    }
                    lastSize = entries.size
                }
            }
        }

        // Завершаем инициализацию и сохраняем конфиг один раз
        isInitializing = false
        saveConfig()

        // Мониторинг состояния соединения для автосохранения карты при разрыве
        scope.launch {
            var wasConnected = false
            isConnected.collect { connected ->
                if (wasConnected && !connected) {
                    // Соединение было разорвано - карта уже сохраняется автоматически в SQLite
                    logger.info { "Connection lost" }
                    // Останавливаем сбор статистики
                    sessionStats.stopSession()
                    // Останавливаем логирование
                    logManager.stopLogging()
                    // Уведомляем скрипты
                    if (::scriptManager.isInitialized) {
                        scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_DISCONNECT)
                    }
                    // Уведомляем плагины
                    firePluginEvent(com.bylins.client.plugins.events.DisconnectEvent(
                        reason = com.bylins.client.plugins.events.DisconnectReason.SERVER_CLOSED
                    ))

                    // Авто-переподключение, если разрыв неожиданный и опция включена
                    if (!userInitiatedDisconnect && getCurrentProfile()?.autoReconnect == true) {
                        scheduleReconnect()
                    }
                }
                wasConnected = connected
            }
        }
    }

    fun connect(host: String, port: Int) {
        // Любое подключение снимает признак «ручного отключения»
        userInitiatedDisconnect = false
        scope.launch {
            try {
                _errorMessage.value = null
                telnetClient.connect(host, port)
                // Начинаем сбор статистики
                sessionStats.startSession()
                // Автоматически запускаем логирование
                logManager.startLogging(stripAnsi = true)
                // Устанавливаем системные переменные
                variableManager.setSystemVariable("host", host)
                variableManager.setSystemVariable("port", port)
                variableManager.setSystemVariable("connected", 1)
                // Карта уже загружена из SQLite при инициализации MapManager

                // Уведомляем скрипты о подключении
                if (::scriptManager.isInitialized) {
                    scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_CONNECT)
                }

                // Уведомляем плагины о подключении
                firePluginEvent(com.bylins.client.plugins.events.ConnectEvent(host, port))
            } catch (e: Exception) {
                val userFriendlyError = when {
                    e is java.net.ConnectException && e.message?.contains("Connection refused") == true ->
                        "Не удалось подключиться к $host:$port — сервер недоступен или порт закрыт"
                    e is java.net.UnknownHostException ->
                        "Неизвестный хост: $host"
                    e is java.net.SocketTimeoutException ->
                        "Превышено время ожидания подключения к $host:$port"
                    e is java.net.NoRouteToHostException ->
                        "Нет маршрута до хоста $host"
                    e is java.io.IOException ->
                        "Ошибка сети: ${e.message ?: "неизвестная ошибка"}"
                    else ->
                        "Ошибка подключения: ${e.message ?: "неизвестная ошибка"}"
                }
                _errorMessage.value = userFriendlyError
            }
        }
    }

    fun disconnect() {
        // Ручной разрыв: не запускать авто-переподключение и отменить текущее
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null

        // Карта сохраняется автоматически в SQLite при каждом изменении
        telnetClient.disconnect()

        // Сбрасываем MSDP состояние
        _msdpEnabled.value = false
        _msdpData.value = emptyMap()
        _msdpReportableVariables.value = emptyList()
        _msdpReportedVariables.value = emptySet()
        msdpSubscribers.clear()

        // Обновляем системные переменные
        variableManager.setSystemVariable("connected", 0)
        // Очищаем MSDP переменные
        variableManager.clearBySource(com.bylins.client.variables.VariableSource.MSDP)

        // Останавливаем сбор статистики
        sessionStats.stopSession()
        // Останавливаем логирование
        logManager.stopLogging()
        // Уведомляем скрипты
        if (::scriptManager.isInitialized) {
            scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_DISCONNECT)
        }

        // Уведомляем плагины об отключении
        firePluginEvent(com.bylins.client.plugins.events.DisconnectEvent(
            reason = com.bylins.client.plugins.events.DisconnectReason.USER_REQUEST
        ))
    }

    /**
     * Запускает цикл авто-переподключения к текущему профилю.
     * Останавливается при успешном подключении, ручном отключении,
     * выключенной опции или смене/отсутствии профиля.
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val profile = getCurrentProfile()
                if (profile == null || !profile.autoReconnect) break
                if (userInitiatedDisconnect || isConnected.value) break

                attempt++
                telnetClient.addLocalOutput(
                    "\n[Соединение потеряно. Переподключение через ${reconnectDelayMs / 1000} с (попытка $attempt)...]\n"
                )
                delay(reconnectDelayMs)
                if (userInitiatedDisconnect || isConnected.value) break
                if (getCurrentProfile()?.autoReconnect != true) break

                connect(profile.host, profile.port)
                // Ждём, пока соединение установится или провалится
                delay(2000)
            }
        }
    }

    /**
     * Вызывается при закрытии приложения
     */
    fun shutdown() {
        logger.info { "Shutting down..." }
        // Сохраняем конфигурацию ПЕРВЫМ делом, пока всё живо. Раньше сначала шёл
        // disconnect(), и его исключение (события плагинам/скриптам при тире-дауне)
        // могло сорвать сохранение — добавленные в сессии вкладки терялись.
        try { saveConfigNow() } catch (e: Exception) { logger.error { "Save on shutdown failed: ${e.message}" } }
        // Отключаемся если подключены
        try { if (isConnected.value) disconnect() } catch (e: Exception) { logger.error { "Disconnect on shutdown failed: ${e.message}" } }
        // Завершаем работу маппера (сохраняет снапшот)
        try { mapManager.shutdown() } catch (e: Exception) { logger.error { "Map shutdown failed: ${e.message}" } }
        // Выгружаем плагины
        try { pluginManager.shutdown() } catch (e: Exception) { logger.error { "Plugin shutdown failed: ${e.message}" } }
        logger.info { "Shutdown complete" }
    }

    fun send(command: String) {
        // Если команда содержит ";", разделяем на несколько и отправляем по очереди
        if (command.contains(";")) {
            val commands = command.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            for (cmd in commands) {
                sendSingleCommand(cmd)
            }
            return
        }
        sendSingleCommand(command)
    }

    private fun sendSingleCommand(command: String) {
        // Команду, которую обработает сам клиент, эхаем заранее и перед
        // промптом. Раньше эхо шло после обработки и в конец буфера: вывод
        // команды оказывался выше самой команды, а эхо вставало на место
        // промпта — и следующие строки шли уже без него.
        val localCommand = command.startsWith("#")
        if (localCommand) {
            telnetClient.addLocalOutput("[1;36m$command[0m")
            logManager.log(command)
        }

        // Сначала проверяем команды управления переменными
        val varHandled = variableManager.processCommand(command) { message ->
            // Выводим сообщения от VariableManager с сохранением промпта
            telnetClient.addLocalOutput(message)
        }
        if (varHandled) {
            if (!localCommand) {
                telnetClient.echoCommand(command)
                logManager.log(command)
            }
            return
        }

        // Проверяем команды навигации по карте
        val navHandled = commandProcessor.processNavigationCommand(command)
        if (navHandled) {
            if (!localCommand) {
                telnetClient.echoCommand(command)
                logManager.log(command)
            }
            return
        }

        // Подставляем переменные в команду
        val commandWithVars = variableManager.substituteVariables(command)

        // Проверяем алиасы из профилей (наложение - последний профиль приоритетнее)
        var handled = false
        if (::profileManager.isInitialized) {
            // Проверяем профили в обратном порядке (последний в стеке - приоритетнее)
            for (profile in profileManager.getActiveProfiles().reversed()) {
                if (aliasManager.processCommandWithAliases(commandWithVars, profile.aliases)) {
                    handled = true
                    break
                }
            }
        }

        // Если профильные алиасы не сработали - проверяем базовые
        if (!handled) {
            handled = aliasManager.processCommand(commandWithVars)
        }

        if (handled) {
            // Алиас сработал
            sessionStats.incrementAliasesExecuted()
        } else {
            // Алиас не сработал, отправляем команду как есть
            sendRaw(commandWithVars)
        }
    }

    private fun sendRaw(command: String) {
        // Проверяем алиасы плагинов
        if (::pluginManager.isInitialized) {
            val manager = pluginManager as? com.bylins.client.plugins.PluginManagerImpl
            if (manager?.checkPluginAliases(command) == true) {
                return // Алиас плагина обработал команду
            }
        }

        // Уведомляем скрипты
        if (::scriptManager.isInitialized) {
            scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_COMMAND, command)
        }

        // Уведомляем плагины о команде (cancellable)
        val commandEvent = com.bylins.client.plugins.events.CommandSendEvent(command)
        pluginEventBus.post(commandEvent)
        if (commandEvent.isCancelled) {
            return // Команда отменена плагином
        }

        // Эхо команды в лог (через TelnetClient для правильной работы с промптом).
        // Команды клиента (#...) уже эхнуты в sendSingleCommand до обработки —
        // сюда они попадают, только если ни один локальный обработчик их не взял
        // и они уходят на сервер как есть.
        if (!command.startsWith("#")) {
            telnetClient.echoCommand(command)
            logManager.log(command)
        }

        // Увеличиваем счетчик отправленных команд
        sessionStats.incrementCommandsSent()

        // Отправляем в одном потоке: каждая команда своей корутиной в общем пуле
        // означала гонку за сокет, и пачка команд (например, от ИИ) приходила
        // серверу перемешанной. Порядок отправки должен повторять порядок команд.
        sendScope.launch {
            try {
                telnetClient.send(command)
            } catch (e: Exception) {
                _errorMessage.value = "Ошибка отправки: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    /**
     * Устанавливает статус MSDP (вызывается из TelnetClient при согласовании)
     */
    fun setMsdpEnabled(enabled: Boolean) {
        val wasEnabled = _msdpEnabled.value
        _msdpEnabled.value = enabled
        if (enabled && !wasEnabled) {
            logger.info { "MSDP protocol enabled" }
            // Уведомляем скрипты о включении MSDP
            if (::scriptManager.isInitialized) {
                scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_MSDP_ENABLED)
            }
            // Уведомляем плагины
            firePluginEvent(com.bylins.client.plugins.events.MsdpEnabledEvent)
        }
    }

    /**
     * Отправляет MSDP команду LIST для запроса списка
     * listType: "COMMANDS", "LISTS", "REPORTABLE_VARIABLES", "CONFIGURABLE_VARIABLES", "REPORTED_VARIABLES"
     */
    fun sendMsdpList(listType: String) {
        if (!_msdpEnabled.value) {
            logger.warn { "MSDP not enabled, LIST command ignored" }
            return
        }
        logger.info { "MSDP LIST $listType sending..." }
        telnetClient.sendMsdpCommand("LIST", listType)
    }

    /**
     * Включает REPORT для переменной (автоматические обновления)
     */
    fun sendMsdpReport(variableName: String) {
        if (!_msdpEnabled.value) {
            logger.warn { "MSDP not enabled, REPORT command ignored" }
            return
        }
        telnetClient.sendMsdpCommand("REPORT", variableName)
        _msdpReportedVariables.value = _msdpReportedVariables.value + variableName
        logger.debug { "MSDP REPORT $variableName sent" }
    }

    /**
     * Выключает REPORT для переменной
     */
    fun sendMsdpUnreport(variableName: String) {
        if (!_msdpEnabled.value) {
            logger.warn { "MSDP not enabled, UNREPORT command ignored" }
            return
        }
        telnetClient.sendMsdpCommand("UNREPORT", variableName)
        _msdpReportedVariables.value = _msdpReportedVariables.value - variableName
        logger.debug { "MSDP UNREPORT $variableName sent" }
    }

    /**
     * Запрашивает текущее значение переменной (разовый запрос)
     */
    fun sendMsdpSend(variableName: String) {
        if (!_msdpEnabled.value) {
            logger.warn { "MSDP not enabled, SEND command ignored" }
            return
        }
        telnetClient.sendMsdpCommand("SEND", variableName)
        logger.debug { "MSDP SEND $variableName sent" }
    }

    /**
     * Подписывается на MSDP переменную с ref-counting.
     * Если никто ещё не подписан - отправляет REPORT.
     * @param variableName имя переменной
     * @param subscriberId уникальный ID подписчика (plugin ID, script name и т.д.)
     */
    fun subscribeMsdpVariable(variableName: String, subscriberId: String) {
        val subscribers = msdpSubscribers.getOrPut(variableName) { mutableSetOf() }
        val wasEmpty = subscribers.isEmpty()
        subscribers.add(subscriberId)

        if (wasEmpty && _msdpEnabled.value) {
            sendMsdpReport(variableName)
            logger.info { "MSDP subscribed to $variableName (first subscriber: $subscriberId)" }
        } else {
            logger.debug { "MSDP $variableName: added subscriber $subscriberId (total: ${subscribers.size})" }
        }
    }

    /**
     * Отписывается от MSDP переменной с ref-counting.
     * Если больше никто не подписан - отправляет UNREPORT.
     * @param variableName имя переменной
     * @param subscriberId уникальный ID подписчика
     */
    fun unsubscribeMsdpVariable(variableName: String, subscriberId: String) {
        val subscribers = msdpSubscribers[variableName] ?: return
        subscribers.remove(subscriberId)

        if (subscribers.isEmpty()) {
            msdpSubscribers.remove(variableName)
            if (_msdpEnabled.value) {
                sendMsdpUnreport(variableName)
                logger.info { "MSDP unsubscribed from $variableName (last subscriber: $subscriberId)" }
            }
        } else {
            logger.debug { "MSDP $variableName: removed subscriber $subscriberId (remaining: ${subscribers.size})" }
        }
    }

    /**
     * Отписывает подписчика от всех MSDP переменных.
     * Используется при выгрузке плагина/скрипта.
     */
    fun unsubscribeMsdpAll(subscriberId: String) {
        val variablesToUnsubscribe = msdpSubscribers.filter { it.value.contains(subscriberId) }.keys.toList()
        for (varName in variablesToUnsubscribe) {
            unsubscribeMsdpVariable(varName, subscriberId)
        }
    }

    /**
     * Проверяет, подписан ли кто-то на переменную.
     */
    fun isMsdpVariableSubscribed(variableName: String): Boolean {
        return msdpSubscribers[variableName]?.isNotEmpty() == true
    }

    /**
     * Возвращает количество подписчиков для переменной.
     */
    fun getMsdpSubscriberCount(variableName: String): Int {
        return msdpSubscribers[variableName]?.size ?: 0
    }

    /**
     * Обновляет карту по MSDP-переменной ROOM: комната, зона, выходы.
     * Раньше это делал только плагин-ассистент, поэтому без него местоположение
     * определялось лишь парсингом текста.
     */
    @Suppress("UNCHECKED_CAST")
    private fun handleMsdpRoom(value: Any) {
        val roomData = value as? Map<String, Any> ?: return
        val vnum = roomData["VNUM"]?.toString() ?: return

        // Автомаппинг выключен — карту не дополняем, но если комната уже
        // известна, отмечаем, что игрок в ней: иначе «где я» замирает,
        // и правила с областью действия перестают срабатывать
        if (!mapManager.mapEnabled.value) {
            if (mapManager.getRoom(vnum) != null) mapManager.setCurrentRoom(vnum)
            return
        }

        val exits = mutableMapOf<String, String>()
        (roomData["EXITS"] as? Map<*, *>)?.forEach { (dir, target) ->
            exits[dir.toString().lowercase()] = target.toString()
        }

        runCatching {
            handleRoomFromMsdpInternal(
                vnum = vnum,
                name = roomData["NAME"]?.toString() ?: "",
                zone = roomData["ZONE"]?.toString(),
                area = roomData["AREA"]?.toString(),
                terrain = roomData["TERRAIN"]?.toString(),
                exits = exits
            )
        }.onFailure { logger.warn { "Не удалось обновить комнату из MSDP: ${it.message}" } }
    }

    /**
     * Общая обработка комнаты из MSDP: и для плагинов (PluginAPI), и для самого
     * клиента. Держать это в двух местах значило бы расхождение поведения.
     */
    private fun handleRoomFromMsdpInternal(
        vnum: String,
        name: String,
        zone: String?,
        area: String?,
        terrain: String?,
        exits: Map<String, String>
    ): Map<String, Any> {
        // Имя зоны (area) запоминаем по её id — иначе в UI виден голый номер
        if (!zone.isNullOrBlank() && !area.isNullOrBlank()) {
            mapManager.setZoneName(zone, area)
        }

        val exitsWithTargets: Map<com.bylins.client.mapper.Direction, String> =
            exits.mapNotNull { (key, value) ->
                val dir = com.bylins.client.mapper.Direction.fromCommand(key)
                if (dir != null) dir to value else null
            }.toMap()

        val existingRoom = mapManager.getRoom(vnum)
        val room = if (existingRoom != null) {
            existingRoom.copy(
                name = name.ifBlank { existingRoom.name },
                zone = zone ?: existingRoom.zone,
                terrain = terrain ?: existingRoom.terrain,
                visited = true
            )
        } else {
            com.bylins.client.mapper.Room(
                id = vnum,
                name = name,
                zone = zone,
                terrain = terrain,
                visited = true
            )
        }

        exitsWithTargets.forEach { (direction, targetVnum) ->
            room.addExit(direction, targetVnum)
            // Соседнюю комнату заводим сразу, чтобы карта знала о ней как о неисследованной
            if (mapManager.getRoom(targetVnum) == null) {
                mapManager.addRoom(
                    com.bylins.client.mapper.Room(id = targetVnum, name = "", visited = false)
                )
            }
        }

        mapManager.addRoom(room)
        mapManager.setCurrentRoom(vnum)
        return room.toMap()
    }

    /** Когда каждая MSDP-переменная обновлялась последний раз (epoch ms). */
    private val _msdpUpdatedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val msdpUpdatedAt: StateFlow<Map<String, Long>> = _msdpUpdatedAt

    fun updateMsdpData(data: Map<String, Any>) {
        // Свежесть важна потребителям: по снимку не видно, пришло значение
        // секунду назад или полчаса назад
        val now = System.currentTimeMillis()
        _msdpUpdatedAt.value = _msdpUpdatedAt.value + data.keys.associateWith { now }

        _msdpData.value = _msdpData.value + data

        // Проверяем специальные переменные (ответы на LIST)
        data["REPORTABLE_VARIABLES"]?.let { value ->
            if (value is List<*>) {
                val variables = value.filterIsInstance<String>()
                _msdpReportableVariables.value = variables
                logger.info { "Received REPORTABLE_VARIABLES list: ${variables.size} variables" }
                // Уведомляем плагины
                firePluginEvent(com.bylins.client.plugins.events.MsdpReportableVariablesEvent(variables))
            }
        }

        // Комната из MSDP — источник правдивее парсинга текста: при обычном
        // перемещении сервер часто шлёт краткое описание без списка выходов,
        // парсер его не распознаёт, и «вход в комнату» не наступает (из-за чего
        // не срабатывали контекстные команды и правила с областью действия).
        data["ROOM"]?.let { handleMsdpRoom(it) }

        // Автоматически обновляем переменные из MSDP (сохраняем оригинальные значения)
        data.forEach { (key, value) ->
            variableManager.setMsdpVariable(key.lowercase(), value)
        }

        // Обновляем историю статистики для графиков
        val allData = _msdpData.value
        val hp = (allData["HEALTH"] as? String)?.toIntOrNull() ?: 0
        val maxHp = (allData["HEALTH_MAX"] as? String)?.toIntOrNull() ?: 1
        val mana = (allData["MANA"] as? String)?.toIntOrNull() ?: 0
        val maxMana = (allData["MANA_MAX"] as? String)?.toIntOrNull() ?: 1
        val movement = (allData["MOVEMENT"] as? String)?.toIntOrNull() ?: 0
        val maxMovement = (allData["MOVEMENT_MAX"] as? String)?.toIntOrNull() ?: 1

        if (hp > 0 || mana > 0 || movement > 0) {
            statsHistory.addHpData(hp, maxHp)
            statsHistory.addManaData(mana, maxMana)
            statsHistory.addMovementData(movement, maxMovement)

            // Звуковое уведомление при низком HP (меньше 30%) - не чаще раза в 10 секунд
            val hpPercent = if (maxHp > 0) (hp.toFloat() / maxHp * 100) else 0f
            val currentTime = System.currentTimeMillis()
            if (hpPercent > 0 && hpPercent < 30 && (currentTime - lastLowHpSoundTime) > 10000) {
                soundManager.playSound(com.bylins.client.audio.SoundManager.SoundType.LOW_HP)
                lastLowHpSoundTime = currentTime
            }
        }

        // Уведомляем скрипты
        if (::scriptManager.isInitialized) {
            scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_MSDP, data)
        }

        // Уведомляем плагины
        data.forEach { (key, value) ->
            firePluginEvent(com.bylins.client.plugins.events.MsdpEvent(key, value))
        }
    }

    /**
     * Обновляет GMCP данные
     */
    fun updateGmcpData(message: com.bylins.client.network.GmcpMessage) {
        // Обновляем хранилище GMCP данных
        _gmcpData.value = _gmcpData.value + (message.packageName to message.data)

        logger.debug { "GMCP: ${message.packageName} = ${message.data}" }

        // Парсим JSON в Map для переменных
        val parser = com.bylins.client.network.GmcpParser()
        val dataMap = parser.jsonToMap(message.data)

        // Автоматически обновляем переменные из GMCP
        if (dataMap != null) {
            dataMap.forEach { (key, value) ->
                variableManager.setVariable("gmcp_${message.packageName.lowercase().replace(".", "_")}_$key", value.toString())
            }
        }

        // Уведомляем скрипты о GMCP событии
        if (::scriptManager.isInitialized) {
            val eventData = mapOf(
                "package" to message.packageName,
                "data" to message.data.toString()
            )
            scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_GMCP, eventData)
        }

        // Уведомляем плагины о GMCP событии
        firePluginEvent(com.bylins.client.plugins.events.GmcpEvent(message.packageName, message.data.toString()))
    }

    /**
     * Обрабатывает входящую строку текста (вызывается из TelnetClient)
     * Возвращает модифицированный текст с примененными colorize от триггеров
     */
    fun processIncomingText(text: String): String {
        // Логируем весь полученный текст
        if (text.isNotEmpty()) {
            logManager.log(text)

            // Добавляем полученные байты в статистику
            sessionStats.addBytesReceived(text.toByteArray(Charsets.UTF_8).size)
        }

        // Распределяем текст по вкладкам
        tabManager.processText(text)

        // Разбиваем на строки и обрабатываем каждую триггерами
        val ansiParser = com.bylins.client.ui.AnsiParser()
        val lines = text.lines()
        val modifiedLines = mutableListOf<String>()

        for (i in lines.indices) {
            val line = lines[i]

            if (line.isEmpty()) {
                modifiedLines.add(line)
                continue
            }

            // Удаляем ANSI-коды перед проверкой триггерами
            val cleanLine = ansiParser.stripAnsi(line)

            // Уведомляем плагины о новой строке (cancellable)
            val lineEvent = com.bylins.client.plugins.events.LineReceivedEvent(
                line = cleanLine,
                rawLine = line
            )
            pluginEventBus.post(lineEvent)
            if (lineEvent.isCancelled) {
                continue // Строка отменена плагином (gag)
            }

            // Проверяем триггеры плагинов
            if (::pluginManager.isInitialized) {
                val manager = pluginManager as? com.bylins.client.plugins.PluginManagerImpl
                val triggerResult = manager?.checkPluginTriggers(cleanLine, line)
                if (triggerResult == com.bylins.client.plugins.TriggerResult.GAG) {
                    continue // Строка скрыта триггером плагина
                }
                if (triggerResult == com.bylins.client.plugins.TriggerResult.STOP) {
                    modifiedLines.add(line)
                    continue // Дальнейшая обработка триггеров не нужна
                }
            }

            // Уведомляем скрипты о новой строке
            if (::scriptManager.isInitialized) {
                scriptManager.fireEvent(com.bylins.client.scripting.ScriptEvent.ON_LINE, cleanLine)
            }

            // Проверяем триггеры из скриптов
            checkScriptTriggers(cleanLine)

            val matches = triggerManager.processLine(cleanLine)

            // Обрабатываем триггеры из профилей
            val profileMatches = mutableListOf<com.bylins.client.triggers.TriggerMatch>()
            if (::profileManager.isInitialized) {
                for (profile in profileManager.getActiveProfiles()) {
                    val profileTriggerMatches = triggerManager.processLineWithTriggers(cleanLine, profile.triggers)
                    profileMatches.addAll(profileTriggerMatches)
                }
            }

            val allMatches = matches + profileMatches

            // Увеличиваем счетчик на количество сработавших триггеров
            if (allMatches.isNotEmpty()) {
                sessionStats.incrementTriggersActivated()
            }

            // Gag: строка скрывается из основного лога, если её прячет сработавший
            // триггер (галка «Gag») или её забирает вкладка в режиме «Перемещать».
            // Команды/скрипты триггеров уже выполнены выше — side-effects сохраняются.
            val gaggedByTrigger = allMatches.any { it.trigger.gag }
            val gaggedByTab = tabManager.shouldGagFromMain(cleanLine, line)
            if (!gaggedByTrigger && !gaggedByTab) {
                // Применяем colorize от первого сработавшего триггера с colorize
                val triggerWithColor = allMatches.firstOrNull { it.trigger.colorize != null }
                if (triggerWithColor != null) {
                    val colorize = triggerWithColor.trigger.colorize!!
                    val colorizedLine = applyColorize(cleanLine, colorize)
                    modifiedLines.add(colorizedLine)
                } else {
                    modifiedLines.add(line)
                }
            }

            // Обрабатываем контекстные команды по паттернам
            contextCommandManager.processLine(cleanLine)

            // Обрабатываем контекстные команды из профилей
            if (::profileManager.isInitialized) {
                val activeProfiles = profileManager.getActiveProfiles()
                for (profile in activeProfiles) {
                    if (profile.contextCommandRules.isNotEmpty()) {
                        logger.debug { "Processing ${profile.contextCommandRules.size} context rules from profile ${profile.name}" }
                    }
                    contextCommandManager.processLineWithRules(cleanLine, profile.contextCommandRules)
                }
            }
        }

        // Восстанавливаем переводы строк
        val result = StringBuilder()
        for (i in modifiedLines.indices) {
            result.append(modifiedLines[i])
            if (i < modifiedLines.size - 1) {
                result.append("\n")
            }
        }

        // Если оригинальный текст заканчивался на \n, добавляем его
        if (text.endsWith("\n") || text.endsWith("\r\n") || text.endsWith("\r")) {
            result.append("\n")
        }

        return result.toString()
    }

    /**
     * Применяет colorize к строке, добавляя ANSI escape-коды
     */
    private fun applyColorize(text: String, colorize: com.bylins.client.triggers.TriggerColorize): String {
        val codes = mutableListOf<Int>()

        // Bold
        if (colorize.bold) {
            codes.add(1)
        }

        // Foreground color
        if (colorize.foreground != null) {
            val color = parseColor(colorize.foreground)
            if (color != null) {
                codes.addAll(listOf(38, 2, color.red, color.green, color.blue))
            }
        }

        // Background color
        if (colorize.background != null) {
            val color = parseColor(colorize.background)
            if (color != null) {
                codes.addAll(listOf(48, 2, color.red, color.green, color.blue))
            }
        }

        if (codes.isEmpty()) {
            return text
        }

        val codeString = codes.joinToString(";")
        return "\u001B[${codeString}m${text}\u001B[0m"
    }

    private data class RGB(val red: Int, val green: Int, val blue: Int)

    /**
     * Парсит hex-цвет в RGB
     */
    private fun parseColor(hex: String): RGB? {
        return try {
            val cleanHex = hex.trim().removePrefix("#")
            if (cleanHex.length != 6) return null
            val r = cleanHex.substring(0, 2).toInt(16)
            val g = cleanHex.substring(2, 4).toInt(16)
            val b = cleanHex.substring(4, 6).toInt(16)
            RGB(r, g, b)
        } catch (e: Exception) {
            null
        }
    }

    // Управление триггерами
    fun addTrigger(trigger: com.bylins.client.triggers.Trigger) {
        triggerManager.addTrigger(trigger)
        saveConfig()
    }

    fun updateTrigger(trigger: com.bylins.client.triggers.Trigger) {
        triggerManager.removeTrigger(trigger.id)
        triggerManager.addTrigger(trigger)
        saveConfig()
    }

    fun removeTrigger(id: String) {
        triggerManager.removeTrigger(id)
        saveConfig()
    }

    fun enableTrigger(id: String) {
        triggerManager.enableTrigger(id)
        saveConfig()
    }

    fun disableTrigger(id: String) {
        triggerManager.disableTrigger(id)
        saveConfig()
    }

    fun exportTriggers(triggerIds: List<String>): String {
        return triggerManager.exportTriggers(triggerIds)
    }

    fun importTriggers(json: String, merge: Boolean = true): Int {
        val count = triggerManager.importTriggers(json, merge)
        saveConfig()
        return count
    }

    // Управление алиасами
    fun addAlias(alias: com.bylins.client.aliases.Alias) {
        aliasManager.addAlias(alias)
        saveConfig()
    }

    fun updateAlias(alias: com.bylins.client.aliases.Alias) {
        aliasManager.removeAlias(alias.id)
        aliasManager.addAlias(alias)
        saveConfig()
    }

    fun removeAlias(id: String) {
        aliasManager.removeAlias(id)
        saveConfig()
    }

    fun enableAlias(id: String) {
        aliasManager.enableAlias(id)
        saveConfig()
    }

    fun disableAlias(id: String) {
        aliasManager.disableAlias(id)
        saveConfig()
    }

    fun exportAliases(aliasIds: List<String>): String {
        return aliasManager.exportAliases(aliasIds)
    }

    fun importAliases(json: String, merge: Boolean = true): Int {
        val count = aliasManager.importAliases(json, merge)
        saveConfig()
        return count
    }

    // Управление хоткеями
    fun addHotkey(hotkey: com.bylins.client.hotkeys.Hotkey) {
        hotkeyManager.addHotkey(hotkey)
        saveConfig()
    }

    fun updateHotkey(hotkey: com.bylins.client.hotkeys.Hotkey) {
        hotkeyManager.removeHotkey(hotkey.id)
        hotkeyManager.addHotkey(hotkey)
        saveConfig()
    }

    fun removeHotkey(id: String) {
        hotkeyManager.removeHotkey(id)
        saveConfig()
    }

    fun enableHotkey(id: String) {
        hotkeyManager.enableHotkey(id)
        saveConfig()
    }

    fun disableHotkey(id: String) {
        hotkeyManager.disableHotkey(id)
        saveConfig()
    }

    fun exportHotkeys(hotkeyIds: List<String>): String {
        return hotkeyManager.exportHotkeys(hotkeyIds)
    }

    fun importHotkeys(json: String, merge: Boolean = true): Int {
        val count = hotkeyManager.importHotkeys(json, merge)
        saveConfig()
        return count
    }

    /**
     * Обрабатывает нажатие горячей клавиши
     * Возвращает true, если хоткей сработал
     */
    fun processHotkey(
        key: androidx.compose.ui.input.key.Key,
        isCtrlPressed: Boolean,
        isAltPressed: Boolean,
        isShiftPressed: Boolean
    ): Boolean {
        // Проверяем зарезервированные хоткеи контекстных команд (Alt+1-0)
        if (contextCommandManager.processReservedHotkey(key, isAltPressed)) {
            sessionStats.incrementHotkeysUsed()
            lastHotkeyTimestamp = System.currentTimeMillis()
            return true
        }

        // Проверяем хоткеи из профилей (наложение - последний профиль приоритетнее)
        var handled = false
        if (::profileManager.isInitialized) {
            // Проверяем профили в обратном порядке (последний в стеке - приоритетнее)
            for (profile in profileManager.getActiveProfiles().reversed()) {
                if (hotkeyManager.processKeyPressWithHotkeys(
                        key, isCtrlPressed, isAltPressed, isShiftPressed,
                        _ignoreNumLock.value, profile.hotkeys
                    )) {
                    handled = true
                    break
                }
            }
        }

        // Если профильные хоткеи не сработали - проверяем базовые
        if (!handled) {
            handled = hotkeyManager.processKeyPress(key, isCtrlPressed, isAltPressed, isShiftPressed, _ignoreNumLock.value)
        }

        if (handled) {
            sessionStats.incrementHotkeysUsed()
            // Записываем время для блокировки дублирования текстового ввода
            lastHotkeyTimestamp = System.currentTimeMillis()
        }
        return handled
    }

    // Управление вкладками
    fun addTab(tab: com.bylins.client.tabs.Tab) {
        tabManager.addTab(tab)
        // Структурные изменения вкладок сохраняем сразу (без дебаунса), чтобы не
        // потерять их при закрытии/сбое.
        saveConfigNow()
    }

    fun createTab(
        name: String,
        filters: List<com.bylins.client.tabs.TabFilter>,
        captureMode: com.bylins.client.tabs.CaptureMode,
        profileTab: Boolean = false,
        profileLog: Boolean = false,
        persistContent: Boolean = false,
        timestamps: Boolean = false
    ) {
        val tab = com.bylins.client.tabs.Tab(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            filters = filters,
            captureMode = captureMode,
            profileTab = profileTab,
            profileLog = profileLog || profileTab,
            persistContent = persistContent,
            timestamps = timestamps
        )
        addTab(tab)
    }

    fun updateTab(
        id: String,
        name: String,
        filters: List<com.bylins.client.tabs.TabFilter>,
        captureMode: com.bylins.client.tabs.CaptureMode,
        profileTab: Boolean = false,
        profileLog: Boolean = false,
        persistContent: Boolean = false,
        timestamps: Boolean = false
    ) {
        tabManager.updateTab(id, name, filters, captureMode, profileTab, profileLog, persistContent, timestamps)
        saveConfigNow()
    }

    fun removeTab(id: String) {
        tabManager.removeTab(id)
        saveConfigNow()
    }

    fun moveTabTo(id: String, targetId: String, placeAfter: Boolean = false) {
        tabManager.moveTabTo(id, targetId, placeAfter)
        saveConfigNow()
    }

    fun setActiveTab(id: String) {
        tabManager.setActiveTab(id)
    }

    fun getTab(id: String): com.bylins.client.tabs.Tab? {
        return tabManager.getTab(id)
    }

    fun clearTab(id: String) {
        tabManager.clearTab(id)
    }

    fun clearAllTabs() {
        tabManager.clearAll()
    }

    // Управление конфигурацией
    fun saveConfig() {
        // Не сохраняем во время инициализации, чтобы не создавать множество записей
        if (isInitializing) return

        // Debounce: отменяем предыдущий запрос и ждём перед сохранением
        saveConfigJob?.cancel()
        saveConfigJob = scope.launch(Dispatchers.IO) {
            delay(saveConfigDebounceMs)
            doSaveConfig()
        }
    }

    /**
     * Немедленное сохранение конфига (без debounce)
     */
    fun saveConfigNow() {
        saveConfigJob?.cancel()
        doSaveConfig()
    }

    private fun doSaveConfig() {
        // Инициализация не завершена — значит конфиг прочитан, но ещё не разложен
        // по менеджерам, и сохранять сейчас означает записать пустоту поверх.
        // Проверка стояла только в saveConfig(), а shutdown зовёт saveConfigNow():
        // падение в конструкторе уводило JVM в shutdown hook, и тот затирал файл
        // со всеми триггерами, хоткеями и контекстными правилами.
        if (isInitializing) {
            logger.warn { "Конфиг не сохранён: инициализация клиента не завершена" }
            return
        }

        // Синхронизируем стек профилей с текущим профилем подключения перед сохранением
        if (::profileManager.isInitialized) {
            saveActiveProfileStackToCurrentConnectionProfile()
        }

        // Сохраняем комнату, на которую центрирована карта (или текущую комнату игрока как fallback)
        val lastMapRoomId = mapManager.viewCenterRoomId.value ?: mapManager.currentRoomId.value
        logger.info { "Saving config, lastMapRoomId: $lastMapRoomId" }
        configManager.saveConfig(
            triggers = triggers.value,
            aliases = aliases.value,
            hotkeys = hotkeys.value,
            variables = variableManager.getAllVariables(),
            tabs = tabManager.getGlobalTabsForSave(),
            contextCommandRules = contextCommandManager.rules.value,
            contextCommandMaxQueueSize = contextCommandManager.maxQueueSize.value,
            encoding = _encoding,
            miniMapWidth = _miniMapWidth.value,
            miniMapHeight = _miniMapHeight.value,
            zonePanelWidth = _zonePanelWidth.value,
            theme = _currentTheme.value,
            fontFamily = _fontFamily.value,
            fontSize = _fontSize.value,
            connectionProfiles = _connectionProfiles.value,
            currentProfileId = _currentProfileId.value,
            ignoreNumLock = _ignoreNumLock.value,
            activeProfileStack = if (::profileManager.isInitialized) profileManager.activeStack.value else emptyList(),
            hiddenTabs = _hiddenTabs.value,
            lastMapRoomId = lastMapRoomId,
            logWithColors = logManager.logWithColors.value,
            statusGroupCollapsed = _statusGroupCollapsed.value,
            sidePanelCollapsed = _sidePanelCollapsed.value,
            configBackups = _configBackups.value,
            pluginPermissions = _pluginPermissions.value,
            outputSplitFractions = getOutputSplitFractions()
        )
    }

    /**
     * Устанавливает кодировку для telnet соединения
     */
    fun setEncoding(newEncoding: String) {
        _encoding = newEncoding
        telnetClient.setEncoding(newEncoding)
        saveConfig()
    }

    /**
     * Устанавливает ширину боковой панели с миникартой
     */
    fun setMiniMapWidth(width: Int) {
        val clampedWidth = width.coerceAtLeast(150)
        _miniMapWidth.value = clampedWidth
        saveConfig()
    }

    /**
     * Устанавливает высоту миникарты в статус-панели
     */
    fun setMiniMapHeight(height: Int) {
        val clampedHeight = height.coerceIn(100, 800)
        _miniMapHeight.value = clampedHeight
        saveConfig()
    }

    /**
     * Устанавливает ширину панели заметок зоны на вкладке Карта
     */
    fun setZonePanelWidth(width: Int) {
        val clampedWidth = width.coerceIn(150, 500)
        _zonePanelWidth.value = clampedWidth
        saveConfig()
    }

    /**
     * Устанавливает тему оформления
     */
    fun setTheme(themeName: String) {
        _currentTheme.value = themeName
        saveConfig()
        logger.info { "Theme changed to: $themeName" }
    }

    /**
     * Устанавливает семейство шрифтов
     */
    fun setFontFamily(family: String) {
        _fontFamily.value = family
        saveConfig()
        logger.info { "Font family changed to: $family" }
    }

    /**
     * Устанавливает размер шрифта
     */
    fun setFontSize(size: Int) {
        val clampedSize = size.coerceIn(10, 24)
        _fontSize.value = clampedSize
        saveConfig()
        logger.info { "Font size changed to: $clampedSize" }
    }

    fun setIgnoreNumLock(ignore: Boolean) {
        _ignoreNumLock.value = ignore
        saveConfig()
        logger.info { "Ignore NumLock changed to: $ignore" }
    }

    // Управление видимостью вкладок
    fun setTabVisible(tabId: String, visible: Boolean) {
        // Постоянные вкладки нельзя скрывать
        if (!visible && tabId in PERMANENT_TAB_IDS) {
            logger.info { "Tab '$tabId' is permanent and cannot be hidden" }
            return
        }
        _hiddenTabs.value = if (visible) {
            _hiddenTabs.value - tabId
        } else {
            _hiddenTabs.value + tabId
        }
        saveConfig()
        logger.info { "Tab '$tabId' visibility changed to: $visible" }
    }

    fun isTabVisible(tabId: String): Boolean {
        if (tabId in PERMANENT_TAB_IDS) return true
        return tabId !in _hiddenTabs.value
    }

    // Управление профилями подключений
    fun addConnectionProfile(profile: com.bylins.client.connection.ConnectionProfile) {
        _connectionProfiles.value = _connectionProfiles.value + profile
        saveConfigNow()
        logger.info { "Added connection profile: ${profile.name}" }
    }

    fun updateConnectionProfile(profile: com.bylins.client.connection.ConnectionProfile) {
        _connectionProfiles.value = _connectionProfiles.value.map {
            if (it.id == profile.id) profile else it
        }
        saveConfigNow()
        logger.info { "Updated connection profile: ${profile.name}" }
    }

    fun removeConnectionProfile(profileId: String) {
        _connectionProfiles.value = _connectionProfiles.value.filter { it.id != profileId }
        // Если удаляем текущий профиль, сбрасываем выбор
        if (_currentProfileId.value == profileId) {
            _currentProfileId.value = null
        }
        saveConfigNow()
        logger.info { "Removed connection profile: $profileId" }
    }

    fun setCurrentProfile(profileId: String?) {
        // Смена сервера — прекращаем переподключение к прежнему
        reconnectJob?.cancel()
        reconnectJob = null

        // Сохраняем текущий стек профилей в старый профиль подключения
        saveActiveProfileStackToCurrentConnectionProfile()

        _currentProfileId.value = profileId

        // При выборе профиля обновляем кодировку, карту и стек профилей
        profileId?.let { id ->
            val profile = _connectionProfiles.value.find { it.id == id }
            profile?.let {
                setEncoding(it.encoding)
                switchMapDatabase(it.mapFile)
                // Переключаем стек профилей персонажей
                switchProfileStack(it.activeProfileStack)
                // Доли разделителя — свои на этот сервер
                loadOutputSplitFractions(it.outputSplitFractions)
                // Профильные вкладки — свои на этот сервер
                tabManager.setProfileTabs(it.tabs.map { dto -> dto.toTab() })
                // Профильные логи глобальных вкладок — свои на этот сервер
                tabManager.applyPerProfileLogs(it.tabLogs)
                // Сохранённый порядок вкладок этого сервера
                tabManager.applyTabOrder(it.tabOrder)
            }
        } ?: run {
            // Если профиль не выбран - очищаем стек и профильные вкладки
            profileManager.clearStack()
            tabManager.setProfileTabs(emptyList())
        }

        saveConfig()
        logger.info { "Set current profile: $profileId" }
    }

    /**
     * Сохраняет текущий стек профилей персонажей в текущий профиль подключения
     */
    fun saveActiveProfileStackToCurrentConnectionProfile() {
        val currentConnProfileId = _currentProfileId.value ?: return
        val currentStack = profileManager.activeStack.value

        _connectionProfiles.value = _connectionProfiles.value.map { connProfile ->
            if (connProfile.id == currentConnProfileId) {
                connProfile.copy(
                    activeProfileStack = currentStack,
                    outputSplitFractions = getOutputSplitFractions(),
                    tabs = tabManager.getProfileTabsForSave().map { com.bylins.client.tabs.TabDto.fromTab(it) },
                    tabLogs = tabManager.getPerProfileLogs(),
                    tabOrder = tabManager.getTabOrder()
                )
            } else {
                connProfile
            }
        }
    }

    /**
     * Переключает стек профилей персонажей
     */
    private fun switchProfileStack(newStack: List<String>) {
        // Очищаем текущий стек
        profileManager.clearStack()

        // Восстанавливаем новый стек
        profileManager.restoreStack(newStack)

        logger.info { "Switched profile stack to: $newStack" }
    }

    fun getCurrentProfile(): com.bylins.client.connection.ConnectionProfile? {
        return _currentProfileId.value?.let { id ->
            _connectionProfiles.value.find { it.id == id }
        }
    }

    fun exportConfig(file: File) {
        configManager.exportConfig(
            file,
            triggers.value,
            aliases.value,
            hotkeys.value,
            variableManager.getAllVariables(),
            tabManager.getTabsForSave(),
            _encoding,
            _miniMapWidth.value,
            _miniMapHeight.value,
            _currentTheme.value,
            _fontFamily.value,
            _fontSize.value
        )
    }

    fun importConfig(file: File) {
        val configData = configManager.importConfig(file)

        // Очищаем текущие триггеры, алиасы, хоткеи, переменные и вкладки
        triggerManager.clear()
        aliasManager.clear()
        hotkeyManager.clear()
        variableManager.clear()
        // tabManager не очищаем полностью, т.к. главная вкладка всегда должна быть

        // Загружаем импортированные
        configData.triggers.forEach { addTrigger(it) }
        configData.aliases.forEach { addAlias(it) }
        configData.hotkeys.forEach { addHotkey(it) }
        variableManager.loadVariables(configData.variables)
        tabManager.loadTabs(configData.tabs)

        // Загружаем кодировку
        _encoding = configData.encoding
        telnetClient.setEncoding(_encoding)

        // Загружаем размеры миникарты
        _miniMapWidth.value = configData.miniMapWidth
        _miniMapHeight.value = configData.miniMapHeight
        _zonePanelWidth.value = configData.zonePanelWidth

        // Загружаем тему
        _currentTheme.value = configData.theme

        // Загружаем настройки шрифта
        _fontFamily.value = configData.fontFamily
        _fontSize.value = configData.fontSize

        // Загружаем настройку логирования цветов
        logManager.setLogWithColors(configData.logWithColors)

        // Сохраняем в основной конфиг
        saveConfig()
    }

    fun getConfigPath(): String = configManager.getConfigFile()

    // Управление логированием
    fun startLogging(stripAnsi: Boolean = true) {
        logManager.startLogging(stripAnsi)
    }

    fun stopLogging() {
        logManager.stopLogging()
    }

    fun getLogFiles(): List<File> {
        return logManager.getLogFiles()
    }

    fun getLogsDirectory(): String {
        return logManager.getLogsDirectory()
    }

    val logWithColors = logManager.logWithColors

    fun setLogWithColors(enabled: Boolean) {
        logManager.setLogWithColors(enabled)
        saveConfig()
    }

    fun cleanOldLogs(daysToKeep: Int = 30) {
        logManager.cleanOldLogs(daysToKeep)
    }

    // Доступ к статистике
    fun getSessionDuration(): String {
        return sessionStats.getFormattedDuration()
    }

    fun getFormattedBytes(): String {
        return sessionStats.getFormattedBytes()
    }

    fun setMapEnabled(enabled: Boolean) {
        mapManager.setMapEnabled(enabled)
    }

    fun clearMap() {
        mapManager.clearMap()
    }

    fun setRoomNote(roomId: String, note: String) {
        mapManager.setRoomNote(roomId, note)
    }

    /**
     * Зоны карты для выбора области действия: пары (id, «Название (53)»).
     *
     * Комнаты без зоны (zone пустой) зоной не считаются — иначе в списке
     * появляется безымянная строка «Зона» без номера, выбрать которую нельзя
     * осмысленно. Имя вида «Зона 1022» не дублируем номером второй раз.
     */
    fun zonesForScope(): List<Pair<String, String>> =
        mapManager.rooms.value.values
            .mapNotNull { it.zone }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            .map { id -> id to zoneLabel(id) }

    /**
     * Подпись комнаты для UI: «Название [4341]», а без имени — «Комната 4341».
     * Один голый номер ни о чём не говорит, а имя без номера неоднозначно —
     * одинаковых названий на карте полно.
     */
    fun roomLabelForScope(roomId: String): String {
        val room = mapManager.getRoom(roomId)
        return if (room == null || room.name.isBlank()) "Комната $roomId" else "${room.name} [$roomId]"
    }

    /**
     * Комнаты для выбора области действия правила: пары (id, «Название [id]»).
     * Ищет и по названию, и по номеру — номер часто единственное, что известно.
     */
    fun searchRoomsForScope(query: String): List<Pair<String, String>> {
        val q = query.trim()
        val rooms = mapManager.rooms.value.values
        val matched = if (q.isEmpty()) rooms
            else rooms.filter { it.id.contains(q) || it.name.contains(q, ignoreCase = true) }
        // Безымянные заготовки — в конец: они мало о чём говорят
        return matched
            .sortedWith(compareBy({ it.name.isBlank() }, { it.name }, { it.id }))
            .map { room -> room.id to roomLabelForScope(room.id) }
    }

    /** Поля комнаты, доступные для правки снаружи (плагины, ИИ). */
    val editableRoomFields = setOf("name", "zone", "terrain", "visited", "notes", "color")

    /**
     * Правит поля комнаты по набору изменений.
     * Неизвестные ключи отсеивает вызывающий (ему же их и показывать).
     */
    fun updateRoom(roomId: String, changes: Map<String, Any?>): Boolean {
        fun text(key: String): String? = if (changes.containsKey(key)) changes[key]?.toString() ?: "" else null
        val visited = when (val raw = changes["visited"]) {
            null -> null
            is Boolean -> raw
            else -> raw.toString().equals("true", ignoreCase = true)
        }
        return mapManager.updateRoom(
            roomId = roomId,
            name = text("name"),
            zone = text("zone"),
            terrain = text("terrain"),
            visited = visited,
            notes = text("notes"),
            color = text("color")
        )
    }

    fun setRoomColor(roomId: String, color: String?) {
        mapManager.setRoomColor(roomId, color)
    }

    fun setRoomTerrain(roomId: String, terrain: String?) {
        mapManager.setRoomTerrain(roomId, terrain)
    }

    fun setRoomProperty(roomId: String, key: String, value: String) {
        mapManager.setRoomProperty(roomId, key, value)
    }

    fun removeRoomProperty(roomId: String, key: String) {
        mapManager.removeRoomProperty(roomId, key)
    }

    fun setRoomProperties(roomId: String, properties: Map<String, String>) {
        mapManager.setRoomProperties(roomId, properties)
    }

    fun getRoomProperties(roomId: String): Map<String, String> {
        return mapManager.getRoom(roomId)?.properties ?: emptyMap()
    }

    /**
     * Полное обновление комнаты - название, заметки, terrain, свойства, зона, выходы, visited
     */
    fun updateRoom(
        roomId: String,
        name: String,
        note: String,
        terrain: String?,
        properties: Map<String, String>,
        zone: String,
        exits: Map<com.bylins.client.mapper.Direction, com.bylins.client.mapper.Exit>,
        visited: Boolean
    ) {
        mapManager.updateRoom(roomId, name, note, terrain, properties, zone, exits, visited)
    }

    fun exportMap(): Map<String, com.bylins.client.mapper.Room> {
        return mapManager.exportMap()
    }

    fun importMap(rooms: Map<String, com.bylins.client.mapper.Room>) {
        mapManager.importMap(rooms)
    }

    // Работа с зонами
    fun detectAndAssignZones() {
        mapManager.detectAndAssignZones()
    }

    fun getZoneStatistics(): Map<String, Int> {
        return mapManager.getZoneStatistics()
    }

    fun getRoomsByZone(zoneName: String): List<com.bylins.client.mapper.Room> {
        return mapManager.getRoomsByZone(zoneName)
    }

    fun getAllZones(): List<String> {
        return mapManager.getAllZones()
    }

    fun setRoomZone(roomId: String, zoneName: String) {
        mapManager.setRoomZone(roomId, zoneName)
    }

    fun clearAllZones() {
        mapManager.clearAllZones()
    }

    // Map context menu commands
    fun getMapContextCommands(): Map<String, (com.bylins.client.mapper.Room) -> Unit> {
        return mapContextCommands.toMap()
    }

    fun registerMapCommand(name: String, callback: (com.bylins.client.mapper.Room) -> Unit) {
        mapContextCommands[name] = callback
    }

    fun unregisterMapCommand(name: String) {
        mapContextCommands.remove(name)
    }

    fun executeMapCommand(name: String, room: com.bylins.client.mapper.Room) {
        mapContextCommands[name]?.invoke(room)
    }

    private fun armWalkWatchdog() {
        walkWatchdog?.cancel()
        walkWatchdog = scope.launch {
            kotlinx.coroutines.delay(walkStepTimeoutMs)
            pathWalker.onTimeout()
        }
    }

    /**
     * Идёт к комнате: строит маршрут и шагает по нему, подтверждая каждый шаг
     * сменой комнаты. Возвращает false, если идти некуда.
     */
    fun startWalk(targetRoomId: String): Boolean {
        if (pathWalker.isWalking) pathWalker.stop("Прерван прежний маршрут")
        if (!mapManager.setPathTo(targetRoomId)) {
            telnetClient.addLocalOutput("[Маршрут] Путь к комнате $targetRoomId не найден")
            return false
        }
        val steps = mapManager.activePath.value.size
        val name = mapManager.getRoom(targetRoomId)?.name?.takeIf { it.isNotBlank() } ?: targetRoomId
        telnetClient.addLocalOutput("[Маршрут] Иду к «$name», шагов: $steps")
        // Подсветка живёт вместе с маршрутом и сама сокращается по дороге
        mapManager.setPathHighlight(
            mapManager.getPathRoomIds(mapManager.getCurrentRoom()?.id ?: "", targetRoomId)?.toSet() ?: emptySet(),
            targetRoomId
        )
        _walking.value = true
        return pathWalker.start(
            next = { mapManager.getNextPathDirection() },
            arrived = { mapManager.getCurrentRoom()?.id == targetRoomId }
        )
    }

    /**
     * Идёт по готовому списку направлений (speedwalk-строка, «к ближайшей
     * непосещённой»). Цель как комната неизвестна, поэтому пересчёта нет —
     * но подтверждение каждого шага работает так же.
     */
    fun walkDirections(directions: List<com.bylins.client.mapper.Direction>, label: String): Boolean {
        if (directions.isEmpty()) {
            telnetClient.addLocalOutput("[Маршрут] Идти некуда")
            return false
        }
        if (pathWalker.isWalking) pathWalker.stop("Прерван прежний маршрут")
        telnetClient.addLocalOutput("[Маршрут] $label, шагов: ${directions.size}")
        val queue = ArrayDeque(directions)
        _walking.value = true
        return pathWalker.start(
            next = { queue.removeFirstOrNull() },
            arrived = { queue.isEmpty() }
        )
    }

    /** Останавливает проход по маршруту (например, по команде игрока). */
    fun stopWalk() {
        if (!pathWalker.isWalking) {
            telnetClient.addLocalOutput("[Маршрут] Никуда не идём")
            return
        }
        pathWalker.stop("Остановлено")
        clearPathHighlight()
    }

    fun findPathTo(roomId: String) {
        if (mapManager.setPathTo(roomId)) {
            val path = mapManager.activePath.value
            val targetRoom = mapManager.getRoom(roomId)
            val targetName = targetRoom?.name ?: roomId
            // Используем addLocalOutput чтобы сообщение появилось ПЕРЕД промптом
            val preview = if (path.isNotEmpty()) {
                path.take(10).joinToString(" ") { it.shortName } + if (path.size > 10) " ..." else ""
            } else ""
            telnetClient.addLocalOutput("[Путь к '$targetName': ${path.size} шагов]" + if (preview.isNotEmpty()) "\n[Направления: $preview]" else "")
        } else {
            telnetClient.addLocalOutput("[Путь к комнате $roomId не найден]")
        }
    }

    fun clearPath() {
        mapManager.clearPath()
        telnetClient.addLocalOutput("[Путь очищен]")
    }

    fun setPathHighlight(roomIds: Set<String>, targetRoomId: String?) {
        mapManager.setPathHighlight(roomIds, targetRoomId)
    }

    fun clearPathHighlight() {
        mapManager.clearPathHighlight()
    }

    fun findPathDirections(targetRoomId: String): List<com.bylins.client.mapper.Direction>? {
        return mapManager.findPathFromCurrent(targetRoomId)
    }

    fun getNextPathDirection(): com.bylins.client.mapper.Direction? {
        return mapManager.getNextPathDirection()
    }

    fun getPathPreview(steps: Int = 5): List<com.bylins.client.mapper.Direction> {
        return mapManager.getPathPreview(steps)
    }

    // Управление скриптами
    private fun initializeScripting() {
        try {
            logger.info { "Initializing scripting system..." }

            // Создаем реализацию ScriptAPI
            val scriptAPI = com.bylins.client.scripting.ScriptAPIImpl(
                sendCommand = { command -> send(command) },
                echoText = { text ->
                    telnetClient.addToOutputRaw(text)
                    tabManager.addToMainTab(text + "\n")
                },
                logMessage = { message ->
                    // log() идёт только в системный лог, не в главное окно
                    // Для вывода пользователю используйте print()
                    logger.info { message }
                },
                requestFocus = { requestInputFocus() },
                triggerActions = createTriggerActions(),
                aliasActions = createAliasActions(),
                timerActions = createTimerActions(),
                variableActions = createVariableActions(),
                msdpActions = createMsdpActions(),
                gmcpActions = createGmcpActions(),
                mapperActions = createMapperActions(),
                statusActions = createStatusActions(),
                chartActions = createChartActions(),
                storageActions = createStorageActions()
            )

            // Создаем ScriptManager
            scriptManager = com.bylins.client.scripting.ScriptManager(scriptAPI)

            // Регистрируем движки
            scriptManager.registerEngine(com.bylins.client.scripting.engines.JavaScriptEngine())
            scriptManager.registerEngine(com.bylins.client.scripting.engines.PythonEngine())
            scriptManager.registerEngine(com.bylins.client.scripting.engines.LuaEngine())

            // Автозагрузка скриптов
            scriptManager.autoLoadScripts()

            logger.info { "Scripting system initialized successfully" }
        } catch (e: Exception) {
            logger.error { "Failed to initialize scripting: ${e.message}" }
            e.printStackTrace()
        }
    }

    /**
     * Инициализация системы плагинов
     */
    private fun initializePlugins() {
        logger.info { "Initializing plugin system..." }

        pluginManager = com.bylins.client.plugins.PluginManagerImpl(
            eventBus = pluginEventBus,
            apiFactory = { pluginId, dataFolder ->
                createPluginAPI(pluginId, dataFolder)
            }
        )

        // Автозагрузка плагинов
        pluginManager.autoLoadPlugins()
    }

    /**
     * Инициализация системы профилей персонажей
     */
    private fun initializeProfiles() {
        try {
            logger.info { "Initializing profile system..." }

            val configDir = java.nio.file.Paths.get(configManager.getConfigDir())
            profileManager = com.bylins.client.profiles.ProfileManager(configDir, scriptManager)

            // Загружаем все доступные профили
            profileManager.loadProfiles()

            logger.info { "Profile system initialized successfully, ${profileManager.profiles.value.size} profiles found" }
        } catch (e: Exception) {
            logger.error { "Failed to initialize profiles: ${e.message}" }
            e.printStackTrace()
        }
    }

    // === Эффективные настройки (с учётом активных профилей) ===

    /**
     * Все триггеры в порядке выполнения (база + профили)
     * Возвращает пары (триггер, ID профиля или null для базы)
     */
    fun getAllTriggersWithSource(): List<Pair<com.bylins.client.triggers.Trigger, String?>> {
        val result = mutableListOf<Pair<com.bylins.client.triggers.Trigger, String?>>()

        // Сначала базовые триггеры
        triggers.value.forEach { trigger ->
            result.add(trigger to null)
        }

        // Затем триггеры из каждого профиля в порядке стека
        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                profile.triggers.forEach { trigger ->
                    result.add(trigger to profile.id)
                }
            }
        }

        return result
    }

    /**
     * Все правила контекстных команд с источниками (для UI)
     * Возвращает пары (правило, ID профиля или null для базы)
     */
    fun getAllContextRulesWithSource(): List<Pair<com.bylins.client.contextcommands.ContextCommandRule, String?>> {
        val result = mutableListOf<Pair<com.bylins.client.contextcommands.ContextCommandRule, String?>>()

        // Сначала базовые правила
        contextCommandManager.rules.value.forEach { rule ->
            result.add(rule to null)
        }

        // Затем правила из каждого профиля в порядке стека
        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                profile.contextCommandRules.forEach { rule ->
                    result.add(rule to profile.id)
                }
            }
        }

        return result
    }

    /**
     * Все эффективные правила контекстных команд (база + профили)
     */
    fun getEffectiveContextRules(): List<com.bylins.client.contextcommands.ContextCommandRule> {
        val result = contextCommandManager.rules.value.toMutableList()

        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                result.addAll(profile.contextCommandRules)
            }
        }

        return result
    }

    /**
     * Эффективные алиасы (с наложением по паттерну)
     */
    fun getEffectiveAliases(): List<com.bylins.client.aliases.Alias> {
        val result = aliases.value
            .associateBy { it.pattern.pattern }
            .toMutableMap()

        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                for (alias in profile.aliases) {
                    result[alias.pattern.pattern] = alias
                }
            }
        }

        return result.values.toList()
    }

    /**
     * Все алиасы с источниками (для UI)
     * Возвращает пары (алиас, ID профиля или null для базы)
     */
    fun getAllAliasesWithSource(): List<Pair<com.bylins.client.aliases.Alias, String?>> {
        val result = mutableListOf<Pair<com.bylins.client.aliases.Alias, String?>>()

        // Сначала базовые алиасы
        aliases.value.forEach { alias ->
            result.add(alias to null)
        }

        // Затем алиасы из каждого профиля в порядке стека
        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                profile.aliases.forEach { alias ->
                    result.add(alias to profile.id)
                }
            }
        }

        return result
    }

    /**
     * Эффективные хоткеи (с наложением по комбинации клавиш)
     */
    fun getEffectiveHotkeys(): List<com.bylins.client.hotkeys.Hotkey> {
        val result = hotkeys.value
            .associateBy { getHotkeySignature(it) }
            .toMutableMap()

        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                for (hotkey in profile.hotkeys) {
                    result[getHotkeySignature(hotkey)] = hotkey
                }
            }
        }

        return result.values.toList()
    }

    /**
     * Все хоткеи с источниками (для UI)
     * Возвращает пары (хоткей, ID профиля или null для базы)
     */
    fun getAllHotkeysWithSource(): List<Pair<com.bylins.client.hotkeys.Hotkey, String?>> {
        val result = mutableListOf<Pair<com.bylins.client.hotkeys.Hotkey, String?>>()

        // Сначала базовые хоткеи
        hotkeys.value.forEach { hotkey ->
            result.add(hotkey to null)
        }

        // Затем хоткеи из каждого профиля в порядке стека
        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                profile.hotkeys.forEach { hotkey ->
                    result.add(hotkey to profile.id)
                }
            }
        }

        return result
    }

    /**
     * Эффективные переменные (с наложением по имени)
     */
    fun getEffectiveVariables(): Map<String, String> {
        val result = variableManager.getAllVariables().toMutableMap()

        if (::profileManager.isInitialized) {
            for (profile in profileManager.getActiveProfiles()) {
                result.putAll(profile.variables)
            }
        }

        return result
    }

    /**
     * Сигнатура хоткея для сравнения
     */
    private fun getHotkeySignature(hotkey: com.bylins.client.hotkeys.Hotkey): String {
        return "${hotkey.key.keyCode}_${hotkey.ctrl}_${hotkey.alt}_${hotkey.shift}"
    }

    /**
     * Находит источник триггера (ID профиля или null для базы)
     */
    fun findTriggerSource(triggerId: String): String? {
        // Проверяем базовые
        if (triggers.value.any { it.id == triggerId }) {
            return null
        }

        // Проверяем профили
        if (::profileManager.isInitialized) {
            for (profile in profileManager.profiles.value) {
                if (profile.triggers.any { it.id == triggerId }) {
                    return profile.id
                }
            }
        }

        return null
    }

    /**
     * Находит источник алиаса (ID профиля или null для базы)
     */
    fun findAliasSource(aliasId: String): String? {
        if (aliases.value.any { it.id == aliasId }) {
            return null
        }

        if (::profileManager.isInitialized) {
            for (profile in profileManager.profiles.value) {
                if (profile.aliases.any { it.id == aliasId }) {
                    return profile.id
                }
            }
        }

        return null
    }

    /**
     * Находит источник хоткея (ID профиля или null для базы)
     */
    fun findHotkeySource(hotkeyId: String): String? {
        if (hotkeys.value.any { it.id == hotkeyId }) {
            return null
        }

        if (::profileManager.isInitialized) {
            for (profile in profileManager.profiles.value) {
                if (profile.hotkeys.any { it.id == hotkeyId }) {
                    return profile.id
                }
            }
        }

        return null
    }

    /**
     * Создаёт PluginAPI для плагина
     */
    private fun createPluginAPI(pluginId: String, dataFolder: java.io.File): com.bylins.client.plugins.PluginAPIImpl {
        return com.bylins.client.plugins.PluginAPIImpl(
            pluginId = pluginId,
            sendCommand = { command -> send(command) },
            echoText = { text ->
                telnetClient.addToOutputRaw(text)
                tabManager.addToMainTab(text + "\n")
            },
            eventBus = pluginEventBus,
            variableGetter = { name -> variableManager.getVariable(name)?.asString() },
            variableSetter = { name, value -> variableManager.setVariable(name, value) },
            variableDeleter = { name -> variableManager.removeVariable(name) },
            getAllVariablesFunc = { variableManager.getAllVariables() },
            msdpGetter = { key -> _msdpData.value[key] },
            getAllMsdpFunc = { _msdpData.value },
            isMsdpEnabledFunc = { _msdpEnabled.value },
            getMsdpReportableFunc = { _msdpReportableVariables.value },
            subscribeMsdpFunc = { varName, subId -> subscribeMsdpVariable(varName, subId) },
            unsubscribeMsdpFunc = { varName, subId -> unsubscribeMsdpVariable(varName, subId) },
            unsubscribeMsdpAllFunc = { subId -> unsubscribeMsdpAll(subId) },
            requestMsdpListFunc = { listType -> sendMsdpList(listType) },
            sendMsdpRequestFunc = { varName -> sendMsdpSend(varName) },
            gmcpGetter = { packageName -> _gmcpData.value[packageName]?.toString() },
            getAllGmcpFunc = { _gmcpData.value.mapValues { it.value.toString() } },
            gmcpSender = { _, _ -> /* TODO: отправка GMCP */ },
            // Маппер - чтение
            getCurrentRoomFunc = { mapManager.getCurrentRoom()?.toMap() },
            getRoomFunc = { roomId -> mapManager.getRoom(roomId)?.toMap() },
            searchRoomsFunc = { query -> mapManager.searchRooms(query).map { it.toMap() } },
            findPathFunc = { targetId -> mapManager.findPathFromCurrent(targetId)?.map { it.shortName } },
            findPathRoomIdsFunc = { targetId -> mapManager.findPathRoomIds(targetId) },
            startWalkFunc = { targetId -> startWalk(targetId) },
            stopWalkFunc = { stopWalk() },
            isWalkingFunc = { walking.value },
            // Маппер - модификация
            updateRoomFunc = { roomId, changes -> updateRoom(roomId, changes) },
            setRoomExitFunc = { roomId, direction, targetRoomId, door, both ->
                val parsed = com.bylins.client.mapper.Direction.parse(direction)
                if (parsed == null) "Неизвестное направление: $direction"
                else mapManager.setExit(roomId, parsed, targetRoomId, door, both)
            },
            removeRoomExitFunc = { roomId, direction ->
                val parsed = com.bylins.client.mapper.Direction.parse(direction)
                parsed != null && mapManager.removeExit(roomId, parsed)
            },
            setRoomNoteFunc = { roomId, note -> mapManager.setRoomNote(roomId, note) },
            setRoomColorFunc = { roomId, color -> mapManager.setRoomColor(roomId, color) },
            setRoomZoneFunc = { roomId, zone -> mapManager.setRoomZone(roomId, zone) },
            setRoomPropertyFunc = { roomId, key, value -> mapManager.setRoomProperty(roomId, key, value) },
            removeRoomPropertyFunc = { roomId, key -> mapManager.removeRoomProperty(roomId, key) },
            getRoomPropertiesFunc = { roomId -> mapManager.getRoom(roomId)?.properties ?: emptyMap() },
            setZonePropertyFunc = { zoneId, key, value -> mapManager.setZoneProperty(zoneId, key, value) },
            removeZonePropertyFunc = { zoneId, key -> mapManager.removeZoneProperty(zoneId, key) },
            getZonePropertiesFunc = { zoneId -> mapManager.getZoneProperties(zoneId) },
            // Маппер - создание
            createRoomFunc = { id, name ->
                if (mapManager.getRoom(id) != null) false
                else {
                    mapManager.addRoom(com.bylins.client.mapper.Room(id = id, name = name))
                    true
                }
            },
            createRoomWithExitsFunc = { id, name, exits ->
                if (mapManager.getRoom(id) != null) false
                else {
                    val room = com.bylins.client.mapper.Room(id = id, name = name)
                    exits.forEach { (dirName, targetId) ->
                        com.bylins.client.mapper.Direction.fromCommand(dirName)?.let { dir ->
                            room.addExit(dir, targetId)
                        }
                    }
                    mapManager.addRoom(room)
                    true
                }
            },
            linkRoomsFunc = { fromId, direction, toId ->
                val fromRoom = mapManager.getRoom(fromId)
                val toRoom = mapManager.getRoom(toId)
                val dir = com.bylins.client.mapper.Direction.fromCommand(direction)
                if (fromRoom != null && toRoom != null && dir != null) {
                    val updated = fromRoom.copy()
                    updated.addExit(dir, toId)
                    mapManager.addRoom(updated)
                    val reverseUpdated = toRoom.copy()
                    reverseUpdated.addExit(dir.getOpposite(), fromId)
                    mapManager.addRoom(reverseUpdated)
                }
            },
            handleMovementFunc = { direction, roomName, exits, roomId ->
                val dir = com.bylins.client.mapper.Direction.fromCommand(direction)
                if (dir != null) {
                    val exitDirs = exits.mapNotNull { com.bylins.client.mapper.Direction.fromCommand(it) }
                    mapManager.handleMovement(dir, roomName, exitDirs, roomId)?.toMap()
                } else null
            },
            // Маппер - управление
            setMapEnabledFunc = { enabled -> mapManager.setMapEnabled(enabled) },
            isMapEnabledFunc = { mapManager.mapEnabled.value },
            clearMapFunc = { mapManager.clearMap() },
            setCurrentRoomFunc = { roomId -> mapManager.setCurrentRoom(roomId) },
            isPluginLoadedFunc = { id -> pluginManager.isPluginLoaded(id) },
            // Вкладки плагинов
            registerPluginTabFunc = { tab -> pluginTabManager.registerTab(tab) },
            unregisterPluginTabFunc = { tabId -> pluginTabManager.unregisterTab(tabId) },
            // Callback-поиск комнат
            findRoomsMatchingFunc = { predicate, maxResults ->
                mapManager.findRoomsMatching(predicate, maxResults)
            },
            findNearestMatchingFunc = { predicate ->
                mapManager.findNearestMatching(predicate)
            },
            // События для скриптов
            fireScriptEventFunc = { event, data ->
                if (::scriptManager.isInitialized) {
                    scriptManager.fireEvent(event, data)
                }
            },
            dataFolder = dataFolder,
            // Вкладки вывода (текстовые)
            createOutputTabFunc = { id, title ->
                // Если вкладка уже существует (из сохранённых), удаляем и создаём заново
                // с флагом isPluginTab=true
                val existingTab = tabManager.getTab(id)
                if (existingTab != null && !existingTab.isPluginTab) {
                    tabManager.removeTab(id)
                }
                if (tabManager.getTab(id) == null) {
                    tabManager.addTab(com.bylins.client.tabs.Tab(
                        id = id,
                        name = title,
                        filters = emptyList(),
                        captureMode = com.bylins.client.tabs.CaptureMode.COPY,
                        maxLines = 500,
                        isPluginTab = true  // Вкладки плагинов не редактируются
                    ))
                    true
                } else {
                    false  // Вкладка уже существует и уже isPluginTab
                }
            },
            appendToOutputTabFunc = { id, text ->
                val tab = tabManager.getTab(id)
                if (tab != null) {
                    val isActive = tabManager.activeTabId.value == id
                    tab.appendText(text, markUnread = !isActive)
                    tab.flush()
                }
            },
            closeOutputTabFunc = { id ->
                tabManager.removeTab(id)
            },
            // Панель статуса
            addStatusBarFunc = { id, label, value, max, color, showText, showMax, order ->
                statusManager.addBar(id, label, value, max, color, showText, showMax, order)
            },
            addStatusTextFunc = { id, label, value, color, bold, background, order ->
                statusManager.addText(id, label, value, color, bold, background, order)
            },
            addStatusModifiedValueFunc = { id, label, value, base, modifier, color, order ->
                statusManager.addModifiedValue(id, label, value, base, modifier, color, order)
            },
            addStatusPanelFunc = { id, label, content, order ->
                // Свёрнутость восстанавливаем из конфига — как для обычных групп
                val savedCollapsed = _statusGroupCollapsed.value[id] ?: false
                statusManager.addPluginPanel(id, label, content, savedCollapsed, order)
            },
            addStatusGroupFunc = { id, label, elements, collapsed, order ->
                // Преобразуем StatusElementData в StatusElement
                val statusElements = elements.map { data ->
                    when (data) {
                        is com.bylins.client.plugins.StatusElementData.Bar ->
                            com.bylins.client.status.StatusElement.Bar(
                                data.id, data.label, data.value, data.max,
                                data.color, data.showText, data.showMax, data.order, data.hint
                            )
                        is com.bylins.client.plugins.StatusElementData.Text ->
                            com.bylins.client.status.StatusElement.Text(
                                data.id, data.label, data.value, data.color,
                                data.bold, data.background, data.order, data.hint
                            )
                        is com.bylins.client.plugins.StatusElementData.ModifiedValue ->
                            com.bylins.client.status.StatusElement.ModifiedValue(
                                data.id, data.label, data.value, data.base,
                                data.modifier, data.color, data.order, data.hint
                            )
                    }
                }
                // Используем сохранённое состояние свёрнутости, если есть
                val effectiveCollapsed = _statusGroupCollapsed.value[id] ?: collapsed
                statusManager.addGroup(id, label, statusElements, effectiveCollapsed, order)
            },
            removeStatusFunc = { id -> statusManager.remove(id) },
            clearStatusFunc = { statusManager.clear() },
            updateStatusFunc = { id, updates -> statusManager.update(id, updates) },
            addMiniMapFunc = { id, currentRoomId, visible, order ->
                val actualOrder = if (order < 0) statusManager.elements.value.size else order
                statusManager.addMiniMap(id, currentRoomId, visible, actualOrder)
            },
            handleRoomFromMsdpFunc = { vnum, name, zone, area, terrain, exits ->
                handleRoomFromMsdpInternal(vnum, name, zone, area, terrain, exits)
            },
            registerMapCommandFunc = { name, callback ->
                registerMapCommand(name) { room -> callback(room.toMap()) }
            },
            unregisterMapCommandFunc = { name -> unregisterMapCommand(name) },
            setPathHighlightFunc = { roomIds, targetRoomId ->
                mapManager.setPathHighlight(roomIds.toSet(), targetRoomId)
            },
            clearPathHighlightFunc = { mapManager.clearPathHighlight() },
            // Контекстные команды
            addContextCommandFunc = { command, description, ttlStr ->
                val ttl = when {
                    ttlStr == "room" -> com.bylins.client.contextcommands.ContextCommandTTL.UntilRoomChange
                    ttlStr == "zone" -> com.bylins.client.contextcommands.ContextCommandTTL.UntilZoneChange
                    ttlStr == "permanent" -> com.bylins.client.contextcommands.ContextCommandTTL.Permanent
                    ttlStr == "once" -> com.bylins.client.contextcommands.ContextCommandTTL.OneTime
                    ttlStr.toIntOrNull() != null -> com.bylins.client.contextcommands.ContextCommandTTL.FixedTime(ttlStr.toInt())
                    else -> com.bylins.client.contextcommands.ContextCommandTTL.UntilRoomChange
                }
                contextCommandManager.addManualCommand(command, ttl, description)
            },
            removeContextCommandFunc = { command ->
                val queue = contextCommandManager.commandQueue.value
                queue.filter { it.command == command }.forEach { contextCommandManager.removeCommand(it.id) }
            },
            // Управление клиентом: доступно только с разрешением, выданным пользователем
            clientControl = clientControl,
            permissionChecker = { id, permission -> hasPluginPermission(id, permission) }
        )
    }

    /**
     * Отправляет событие всем плагинам
     */
    private fun firePluginEvent(event: com.bylins.client.plugins.events.PluginEvent) {
        if (::pluginManager.isInitialized) {
            pluginEventBus.post(event)
        }
    }

    private fun createTriggerActions() = object : com.bylins.client.scripting.TriggerActions {
        override fun addTrigger(pattern: String, callback: (String, Map<Int, String>) -> Unit): String {
            val triggerId = java.util.UUID.randomUUID().toString()
            try {
                val regex = pattern.toRegex()
                val trigger = ScriptTrigger(
                    id = triggerId,
                    pattern = regex,
                    callback = callback,
                    enabled = true
                )
                scriptTriggers[triggerId] = trigger
            } catch (e: Exception) {
                logger.error { "[ScriptAPI] Error adding trigger: ${e.message}" }
            }
            return triggerId
        }

        override fun removeTrigger(id: String) {
            scriptTriggers.remove(id)
        }

        override fun enableTrigger(id: String) {
            scriptTriggers[id]?.enabled = true
        }

        override fun disableTrigger(id: String) {
            scriptTriggers[id]?.enabled = false
        }
    }

    private fun createAliasActions() = object : com.bylins.client.scripting.AliasActions {
        override fun addAlias(pattern: String, replacement: String): String {
            val aliasId = java.util.UUID.randomUUID().toString()
            // TODO: Добавить алиас из скрипта
            return aliasId
        }

        override fun removeAlias(id: String) {
            removeAlias(id)
        }
    }

    private fun createTimerActions() = object : com.bylins.client.scripting.TimerActions {
        private val timers = mutableMapOf<String, kotlinx.coroutines.Job>()

        override fun setTimeout(delayMs: Long, callback: () -> Unit): String {
            val timerId = java.util.UUID.randomUUID().toString()
            val job = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                try {
                    callback()
                } catch (e: Exception) {
                    logger.error { "[Timer] Error in setTimeout: ${e.message}" }
                }
                timers.remove(timerId)
            }
            timers[timerId] = job
            return timerId
        }

        override fun setInterval(intervalMs: Long, callback: () -> Unit): String {
            val timerId = java.util.UUID.randomUUID().toString()
            val job = scope.launch {
                while (coroutineContext.isActive) {
                    kotlinx.coroutines.delay(intervalMs)
                    try {
                        callback()
                    } catch (e: Exception) {
                        logger.error { "[Timer] Error in setInterval: ${e.message}" }
                    }
                }
            }
            timers[timerId] = job
            return timerId
        }

        override fun clearTimer(id: String) {
            timers[id]?.cancel()
            timers.remove(id)
        }
    }

    private fun createVariableActions() = object : com.bylins.client.scripting.VariableActions {
        override fun getVariable(name: String): String? {
            return variableManager.getVariableValue(name)
        }

        override fun setVariable(name: String, value: String) {
            variableManager.setVariable(name, value)
            saveConfig()
        }

        override fun deleteVariable(name: String) {
            variableManager.removeVariable(name)
            saveConfig()
        }

        override fun getAllVariables(): Map<String, String> {
            return variableManager.getAllVariables()
        }
    }

    private fun createMsdpActions() = object : com.bylins.client.scripting.MsdpActions {
        override fun getMsdpValue(key: String): Any? {
            return _msdpData.value[key]
        }

        override fun getAllMsdpData(): Map<String, Any> {
            return _msdpData.value
        }

        override fun getReportableVariables(): List<String> {
            return _msdpReportableVariables.value
        }

        override fun getReportedVariables(): List<String> {
            return _msdpReportedVariables.value.toList()
        }

        override fun isEnabled(): Boolean {
            return _msdpEnabled.value
        }

        override fun report(variableName: String) {
            sendMsdpReport(variableName)
        }

        override fun unreport(variableName: String) {
            sendMsdpUnreport(variableName)
        }

        override fun send(variableName: String) {
            sendMsdpSend(variableName)
        }

        override fun list(listType: String) {
            sendMsdpList(listType)
        }
    }

    private fun createGmcpActions() = object : com.bylins.client.scripting.GmcpActions {
        override fun getGmcpValue(packageName: String): String? {
            val jsonElement = _gmcpData.value[packageName]
            return jsonElement?.toString()
        }

        override fun getAllGmcpData(): Map<String, String> {
            return _gmcpData.value.mapValues { it.value.toString() }
        }
    }

    private fun createMapperActions() = object : com.bylins.client.scripting.MapperActions {
        override fun getCurrentRoom(): Map<String, Any>? {
            return mapManager.getCurrentRoom()?.toMap()
        }

        override fun getRoom(roomId: String): Map<String, Any>? {
            return mapManager.getRoom(roomId)?.toMap()
        }

        override fun searchRooms(query: String): List<Map<String, Any>> {
            return mapManager.searchRooms(query).map { it.toMap() }
        }

        override fun findPath(targetRoomId: String): List<String>? {
            return mapManager.findPathFromCurrent(targetRoomId)?.map { it.shortName }
        }

        override fun setRoomNote(roomId: String, note: String) {
            mapManager.setRoomNote(roomId, note)
        }

        override fun setRoomColor(roomId: String, color: String?) {
            mapManager.setRoomColor(roomId, color)
        }

        override fun setRoomZone(roomId: String, zone: String) {
            mapManager.setRoomZone(roomId, zone)
        }

        override fun setRoomProperty(roomId: String, key: String, value: String) {
            mapManager.setRoomProperty(roomId, key, value)
        }

        override fun removeRoomProperty(roomId: String, key: String) {
            mapManager.removeRoomProperty(roomId, key)
        }

        override fun getRoomProperties(roomId: String): Map<String, String> {
            return mapManager.getRoom(roomId)?.properties ?: emptyMap()
        }

        override fun setZoneProperty(zoneId: String, key: String, value: String) {
            mapManager.setZoneProperty(zoneId, key, value)
        }

        override fun removeZoneProperty(zoneId: String, key: String) {
            mapManager.removeZoneProperty(zoneId, key)
        }

        override fun getZoneProperties(zoneId: String): Map<String, String> {
            return mapManager.getZoneProperties(zoneId)
        }

        override fun createRoom(id: String, name: String): Boolean {
            // Проверяем, что комната с таким ID не существует
            if (mapManager.getRoom(id) != null) {
                return false
            }
            val room = com.bylins.client.mapper.Room(
                id = id,
                name = name
            )
            mapManager.addRoom(room)
            return true
        }

        override fun createRoomWithExits(id: String, name: String, exits: Map<String, String>): Boolean {
            if (mapManager.getRoom(id) != null) {
                return false
            }
            val room = com.bylins.client.mapper.Room(
                id = id,
                name = name
            )
            // Добавляем выходы
            exits.forEach { (dirName, targetId) ->
                val direction = com.bylins.client.mapper.Direction.fromCommand(dirName)
                if (direction != null) {
                    room.addExit(direction, targetId)
                }
            }
            mapManager.addRoom(room)
            return true
        }

        override fun linkRooms(fromRoomId: String, direction: String, toRoomId: String) {
            val fromRoom = mapManager.getRoom(fromRoomId) ?: return
            val toRoom = mapManager.getRoom(toRoomId) ?: return
            val dir = com.bylins.client.mapper.Direction.fromCommand(direction) ?: return

            // Добавляем выход из fromRoom в toRoom
            val updated = fromRoom.copy()
            updated.addExit(dir, toRoomId)
            mapManager.addRoom(updated)

            // Добавляем обратный выход
            val reverseUpdated = toRoom.copy()
            reverseUpdated.addExit(dir.getOpposite(), fromRoomId)
            mapManager.addRoom(reverseUpdated)
        }

        override fun addUnexploredExits(roomId: String, exits: List<String>) {
            val room = mapManager.getRoom(roomId) ?: return
            val updated = room.copy()
            exits.forEach { exitStr ->
                val dir = com.bylins.client.mapper.Direction.fromCommand(exitStr)
                if (dir != null) {
                    updated.addUnexploredExit(dir)
                }
            }
            mapManager.addRoom(updated)
        }

        override fun handleMovement(direction: String, roomName: String, exits: List<String>, roomId: String?): Map<String, Any>? {
            val dir = com.bylins.client.mapper.Direction.fromCommand(direction) ?: run {
                return null
            }
            val exitDirections = exits.mapNotNull { exitStr ->
                val d = com.bylins.client.mapper.Direction.fromCommand(exitStr)
                d
            }
            val room = mapManager.handleMovement(dir, roomName, exitDirections, roomId)
            return room?.toMap()
        }

        override fun handleRoom(params: Map<String, Any>): Map<String, Any>? {
            logger.info { "handleRoom called with params: $params" }
            // Извлекаем параметры из MSDP-подобной структуры
            val vnum = params["vnum"] as? String
            if (vnum == null) {
                logger.warn { "handleRoom: vnum is null, params keys: ${params.keys}" }
                return null
            }
            val name = params["name"] as? String ?: ""
            val zone = params["zone"] as? String
            val area = params["area"] as? String  // Только для setZoneName
            val terrain = params["terrain"] as? String

            // Сохраняем имя зоны (area name) по zone_id
            if (!zone.isNullOrBlank() && !area.isNullOrBlank()) {
                mapManager.setZoneName(zone, area)
            }

            // Обрабатываем выходы - формат Map<direction, targetVnum>
            val exitsRaw = params["exits"]
            val exitsWithTargets: Map<com.bylins.client.mapper.Direction, String> = when (exitsRaw) {
                is Map<*, *> -> exitsRaw.mapNotNull { (key, value) ->
                    val dir = com.bylins.client.mapper.Direction.fromCommand(key.toString())
                    val target = value?.toString()
                    if (dir != null && target != null) dir to target else null
                }.toMap()
                else -> emptyMap()
            }

            // Получаем или создаём комнату
            val existingRoom = mapManager.getRoom(vnum)
            val room = if (existingRoom != null) {
                // Обновляем существующую комнату
                existingRoom.copy(
                    name = name,
                    zone = zone ?: existingRoom.zone,
                    terrain = terrain ?: existingRoom.terrain,
                    visited = true
                )
            } else {
                // Создаём новую комнату
                com.bylins.client.mapper.Room(
                    id = vnum,
                    name = name,
                    zone = zone,
                    terrain = terrain,
                    visited = true
                )
            }

            // Добавляем выходы с целевыми комнатами
            exitsWithTargets.forEach { (direction, targetVnum) ->
                room.addExit(direction, targetVnum)

                // Создаём целевую комнату если её нет (статус "неисследовано")
                if (mapManager.getRoom(targetVnum) == null) {
                    val unexploredRoom = com.bylins.client.mapper.Room(
                        id = targetVnum,
                        name = "",  // Пустое имя = неисследовано
                        visited = false
                    )
                    mapManager.addRoom(unexploredRoom)
                }
            }

            mapManager.addRoom(room)
            mapManager.setCurrentRoom(vnum)

            logger.info { "handleRoom: room added, currentRoomId=${mapManager.currentRoomId.value}" }

            return room.toMap()
        }

        override fun setMapEnabled(enabled: Boolean) {
            mapManager.setMapEnabled(enabled)
        }

        override fun isMapEnabled(): Boolean {
            return mapManager.mapEnabled.value
        }

        override fun clearMap() {
            mapManager.clearMap()
        }

        override fun setCurrentRoom(roomId: String) {
            mapManager.setCurrentRoom(roomId)
        }

        @Suppress("UNCHECKED_CAST")
        override fun registerMapCommand(name: String, callback: Any) {
            // Check if callback is already a Kotlin function (from MapperHelper wrapper)
            val kotlinCallback: (com.bylins.client.mapper.Room) -> Unit = when (callback) {
                is Function1<*, *> -> { room ->
                    try {
                        (callback as Function1<Map<String, Any>, Unit>).invoke(room.toMap())
                    } catch (e: Exception) {
                        logger.error { "Error executing map command callback: ${e.message}" }
                    }
                }
                else -> { room ->
                    // Fallback for raw JS callbacks (shouldn't happen with MapperHelper)
                    try {
                        invokeJsCallback(callback, room.toMap())
                    } catch (e: Exception) {
                        logger.error { "Error executing map command callback: ${e.message}" }
                    }
                }
            }
            this@ClientState.registerMapCommand(name, kotlinCallback)
        }

        override fun unregisterMapCommand(name: String) {
            this@ClientState.unregisterMapCommand(name)
        }

        override fun setPathHighlight(roomIds: List<String>, targetRoomId: String?) {
            mapManager.setPathHighlight(roomIds.toSet(), targetRoomId)
        }

        override fun clearPathHighlight() {
            mapManager.clearPathHighlight()
        }

        override fun searchRoomsWithFilter(filter: Any, maxResults: Int): List<Map<String, Any>> {
            // Convert JS callback to Kotlin predicate
            val predicate: (Map<String, Any>) -> Boolean = { roomMap ->
                invokeJsCallbackWithResult(filter, roomMap) as? Boolean ?: false
            }
            return mapManager.findRoomsMatching(predicate, maxResults)
        }

        override fun findNearestRoomMatching(filter: Any): Map<String, Any>? {
            // Convert JS callback to Kotlin predicate
            val predicate: (Map<String, Any>) -> Boolean = { roomMap ->
                invokeJsCallbackWithResult(filter, roomMap) as? Boolean ?: false
            }
            val result = mapManager.findNearestMatching(predicate) ?: return null
            return mapOf(
                "room" to result.first,
                "path" to result.second
            )
        }

        /**
         * Invokes JavaScript callback and returns the result.
         */
        private fun invokeJsCallbackWithResult(callback: Any, vararg args: Any?): Any? {
            return try {
                // Try call method with return value
                val methods = callback.javaClass.methods.filter { it.name == "call" }
                for (method in methods) {
                    try {
                        if (method.parameterCount == 2) {
                            return method.invoke(callback, null, args)
                        }
                    } catch (_: Exception) { }
                }
                // Fallback: try invoke
                val invokeMethods = callback.javaClass.methods.filter { it.name == "invoke" }
                for (method in invokeMethods) {
                    try {
                        return method.invoke(callback, *args)
                    } catch (_: Exception) { }
                }
                null
            } catch (e: Exception) {
                logger.error { "Error invoking callback with result: ${e.message}" }
                null
            }
        }

        /**
         * Invokes JavaScript callback using reflection (works with Nashorn and GraalVM)
         */
        private fun invokeJsCallback(callback: Any, vararg args: Any?) {
            try {
                // Try to find call(Object, Object...) method
                val callMethod = callback.javaClass.getMethod("call", Object::class.java, Array<Any>::class.java)
                callMethod.invoke(callback, null, args)
            } catch (e: NoSuchMethodException) {
                try {
                    // Alternative - find any call method
                    val methods = callback.javaClass.methods.filter { it.name == "call" }
                    for (method in methods) {
                        try {
                            if (method.parameterCount == 2) {
                                method.invoke(callback, null, args)
                                return
                            } else if (method.isVarArgs) {
                                method.invoke(callback, null, *args)
                                return
                            }
                        } catch (_: Exception) { }
                    }
                    logger.warn { "Could not find suitable call method for callback" }
                } catch (ex: Exception) {
                    logger.error { "Error invoking callback: ${ex.message}" }
                }
            } catch (e: Exception) {
                logger.error { "Error in callback: ${e.message}" }
            }
        }
    }

    /**
     * Invokes JavaScript callback using reflection (works with Nashorn and GraalVM)
     */
    private fun invokeJsCallback(callback: Any, vararg args: Any?) {
        try {
            // Try to find call(Object, Object...) method
            val callMethod = callback.javaClass.getMethod("call", Object::class.java, Array<Any>::class.java)
            callMethod.invoke(callback, null, args)
        } catch (e: NoSuchMethodException) {
            try {
                // Alternative - find any call method
                val methods = callback.javaClass.methods.filter { it.name == "call" }
                for (method in methods) {
                    try {
                        if (method.parameterCount == 2) {
                            method.invoke(callback, null, args)
                            return
                        } else if (method.isVarArgs) {
                            method.invoke(callback, null, *args)
                            return
                        }
                    } catch (_: Exception) { }
                }
                logger.warn { "Could not find suitable call method for callback" }
            } catch (ex: Exception) {
                logger.error { "Error invoking callback: ${ex.message}" }
            }
        } catch (e: Exception) {
            logger.error { "Error in callback: ${e.message}" }
        }
    }

    private fun createStatusActions() = object : com.bylins.client.scripting.StatusActions {
        override fun addBar(id: String, label: String, value: Int, max: Int, color: String, showText: Boolean, showMax: Boolean, order: Int) {
            val actualOrder = if (order < 0) statusManager.elements.value.size else order
            statusManager.addBar(id, label, value, max, color, showText, showMax, actualOrder)
        }

        override fun addText(id: String, label: String, value: String?, color: String?, bold: Boolean, background: String?, order: Int) {
            val actualOrder = if (order < 0) statusManager.elements.value.size else order
            statusManager.addText(id, label, value, color, bold, background, actualOrder)
        }

        override fun addFlags(id: String, label: String, flags: List<Map<String, Any>>, order: Int) {
            val actualOrder = if (order < 0) statusManager.elements.value.size else order
            val flagItems = flags.map { flagMap ->
                com.bylins.client.status.FlagItem(
                    name = flagMap["name"] as? String ?: "",
                    active = flagMap["active"] as? Boolean ?: true,
                    color = flagMap["color"] as? String ?: "white",
                    timer = flagMap["timer"] as? String
                )
            }
            statusManager.addFlags(id, label, flagItems, actualOrder)
        }

        override fun addMiniMap(id: String, currentRoomId: String?, visible: Boolean, order: Int) {
            val actualOrder = if (order < 0) statusManager.elements.value.size else order
            statusManager.addMiniMap(id, currentRoomId, visible, actualOrder)
        }

        override fun addPathPanel(id: String, targetName: String, stepsCount: Int, directions: List<String>, onClear: (() -> Unit)?, onFollow: (() -> Unit)?, order: Int) {
            val actualOrder = if (order < 0) statusManager.elements.value.size else order
            statusManager.addPathPanel(id, targetName, stepsCount, directions, onClear, onFollow, actualOrder)
        }

        override fun invokeJsCallback(callback: Any) {
            this@ClientState.invokeJsCallback(callback)
        }

        override fun update(id: String, updates: Map<String, Any>) {
            statusManager.update(id, updates)
        }

        override fun remove(id: String) {
            // Проверяем, удаляется ли path panel - если да, вернём фокус на командную строку
            val element = statusManager.get(id)
            val isPathPanel = element is com.bylins.client.status.StatusElement.PathPanel

            statusManager.remove(id)

            // Возвращаем фокус после удаления path panel
            if (isPathPanel) {
                requestInputFocus()
            }
        }

        override fun clear() {
            statusManager.clear()
        }

        override fun get(id: String): Map<String, Any>? {
            val element = statusManager.get(id) ?: return null
            return when (element) {
                is com.bylins.client.status.StatusElement.Bar -> mapOf(
                    "type" to "bar",
                    "id" to element.id,
                    "label" to element.label,
                    "value" to element.value,
                    "max" to element.max,
                    "color" to element.color,
                    "showText" to element.showText,
                    "order" to element.order
                )
                is com.bylins.client.status.StatusElement.Text -> buildMap {
                    put("type", "text")
                    put("id", element.id)
                    put("label", element.label)
                    element.value?.let { put("value", it) }
                    element.color?.let { put("color", it) }
                    put("bold", element.bold)
                    element.background?.let { put("background", it) }
                    put("order", element.order)
                }
                is com.bylins.client.status.StatusElement.Flags -> mapOf(
                    "type" to "flags",
                    "id" to element.id,
                    "label" to element.label,
                    "flags" to element.flags.map { flag ->
                        mapOf(
                            "name" to flag.name,
                            "active" to flag.active,
                            "color" to flag.color,
                            "timer" to (flag.timer ?: "")
                        )
                    },
                    "order" to element.order
                )
                is com.bylins.client.status.StatusElement.MiniMap -> mapOf(
                    "type" to "minimap",
                    "id" to element.id,
                    "currentRoomId" to (element.currentRoomId ?: ""),
                    "visible" to element.visible,
                    "order" to element.order
                )
                is com.bylins.client.status.StatusElement.PathPanel -> mapOf(
                    "type" to "pathpanel",
                    "id" to element.id,
                    "targetName" to element.targetName,
                    "stepsCount" to element.stepsCount,
                    "directions" to element.directions,
                    "order" to element.order
                )
                is com.bylins.client.status.StatusElement.Group -> mapOf(
                    "type" to "group",
                    "id" to element.id,
                    "label" to element.label,
                    "elements" to element.elements.size,
                    "collapsed" to element.collapsed,
                    "order" to element.order
                )
                is com.bylins.client.status.StatusElement.ModifiedValue -> buildMap {
                    put("type", "modified")
                    put("id", element.id)
                    put("label", element.label)
                    put("value", element.value)
                    element.base?.let { put("base", it) }
                    element.modifier?.let { put("modifier", it) }
                    element.color?.let { put("color", it) }
                    put("order", element.order)
                }
                is com.bylins.client.status.StatusElement.PluginPanel -> mapOf(
                    "type" to "plugin_panel",
                    "id" to element.id,
                    "label" to element.label
                )
            }
        }

        override fun exists(id: String): Boolean {
            return statusManager.exists(id)
        }
    }

    private fun createChartActions() = object : com.bylins.client.scripting.ChartActions {
        override fun createChart(id: String, options: Map<String, Any>) {
            chartManager.createChart(id, options)
        }

        override fun removeChart(id: String) {
            chartManager.removeChart(id)
        }

        override fun clearChart(id: String) {
            chartManager.clearChart(id)
        }

        override fun addSeries(chartId: String, seriesId: String, options: Map<String, Any>) {
            chartManager.addSeries(chartId, seriesId, options)
        }

        override fun removeSeries(chartId: String, seriesId: String) {
            chartManager.removeSeries(chartId, seriesId)
        }

        override fun addDataPoint(chartId: String, seriesId: String, value: Double) {
            chartManager.addDataPoint(chartId, seriesId, value)
        }

        override fun addDataPointExt(chartId: String, seriesId: String, value: Double, displayValue: Double) {
            chartManager.addDataPoint(chartId, seriesId, value, displayValue)
        }

        override fun addChartEvent(chartId: String, label: String, color: String?) {
            chartManager.addChartEvent(chartId, label, color)
        }
    }

    /**
     * Creates storage actions for script data persistence.
     * Uses the script name as the script ID for storage isolation.
     */
    private fun createStorageActions() = object : com.bylins.client.scripting.StorageActions {
        // Get current script ID from the API (set by ScriptManager when loading scripts)
        private fun getCurrentScriptId(): String {
            // Access the scriptManager to get current script name from API
            // If not available, use "global" as fallback
            return "global"  // Scripts can override this by passing their name
        }

        override fun setData(key: String, value: Any): Boolean {
            return scriptStorage.setData(getCurrentScriptId(), key, value)
        }

        override fun getData(key: String): Any? {
            return scriptStorage.getData(getCurrentScriptId(), key)
        }

        override fun deleteData(key: String): Boolean {
            return scriptStorage.deleteData(getCurrentScriptId(), key)
        }

        override fun listDataKeys(prefix: String?): List<String> {
            return scriptStorage.listDataKeys(getCurrentScriptId(), prefix)
        }
    }

    fun getScripts() = if (::scriptManager.isInitialized) scriptManager.scripts else kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    fun getAvailableScriptEngines() = if (::scriptManager.isInitialized) scriptManager.getAvailableEngines() else emptyList()
    fun loadScript(file: java.io.File) = if (::scriptManager.isInitialized) scriptManager.loadScript(file) else null
    fun unloadScript(scriptId: String) = if (::scriptManager.isInitialized) scriptManager.unloadScript(scriptId) else Unit
    fun enableScript(scriptId: String) = if (::scriptManager.isInitialized) scriptManager.enableScript(scriptId) else Unit
    fun disableScript(scriptId: String) = if (::scriptManager.isInitialized) scriptManager.disableScript(scriptId) else Unit
    fun reloadScript(scriptId: String) = if (::scriptManager.isInitialized) scriptManager.reloadScript(scriptId) else Unit
    fun getScriptsDirectory() = if (::scriptManager.isInitialized) scriptManager.getScriptsDirectory() else "scripts"

    // === Управление плагинами ===
    fun getPlugins() = if (::pluginManager.isInitialized) pluginManager.plugins else kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    fun loadPlugin(file: java.io.File) = if (::pluginManager.isInitialized) pluginManager.loadPlugin(file) else null
    fun unloadPlugin(pluginId: String) = if (::pluginManager.isInitialized) pluginManager.unloadPlugin(pluginId) else false
    fun enablePlugin(pluginId: String) = if (::pluginManager.isInitialized) pluginManager.enablePlugin(pluginId) else false
    fun disablePlugin(pluginId: String) = if (::pluginManager.isInitialized) pluginManager.disablePlugin(pluginId) else false
    fun reloadPlugin(pluginId: String) = if (::pluginManager.isInitialized) pluginManager.reloadPlugin(pluginId) else false
    fun getPluginsDirectory() = if (::pluginManager.isInitialized) pluginManager.pluginsDirectory.absolutePath else "plugins"

    // Управление звуками
    fun setSoundEnabled(enabled: Boolean) = soundManager.setSoundEnabled(enabled)
    fun setSoundVolume(volume: Float) = soundManager.setVolume(volume)
    fun playSound(type: com.bylins.client.audio.SoundManager.SoundType) = soundManager.playSound(type)
}
