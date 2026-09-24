package com.bylins.client.tabs

import mu.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Менеджер вкладок
 */
private val logger = KotlinLogging.logger("TabManager")
class TabManager {
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId

    // Создаём AnsiParser один раз для всех строк
    private val ansiParser = com.bylins.client.ui.AnsiParser()

    /**
     * Главная вкладка (всегда существует)
     * Уменьшенный лимит для экономии памяти
     */
    private val mainTab = Tab(
        id = "main",
        name = "Главная",
        filters = emptyList(),
        captureMode = CaptureMode.COPY,
        maxLines = 1000  // Уменьшено с 2000
    )

    /**
     * Вкладка системных логов (всегда существует)
     */
    private val logsTab = Tab(
        id = "logs",
        name = "Логи",
        filters = emptyList(),
        captureMode = CaptureMode.COPY,
        maxLines = 500
    )

    init {
        // Добавляем системные вкладки (логи в конце - системная)
        _tabs.value = listOf(mainTab, logsTab)
        _activeTabId.value = "main"
    }

    /**
     * Добавляет новую вкладку (перед системной вкладкой "Логи")
     */
    fun addTab(tab: Tab) {
        if (_tabs.value.any { it.id == tab.id }) {
            logger.info { "Tab with id ${tab.id} already exists" }
            return
        }
        // Порядок: главная, свои вкладки, вкладки плагинов, логи.
        // Свои вкладки создаются позже плагинных (плагины поднимаются раньше
        // конфига), поэтому просто «в конец» уводило чат за «ИИ» и «Ассистент».
        val current = _tabs.value.toMutableList()
        val insertAt = if (tab.isPluginTab) {
            current.indexOfFirst { it.id == "logs" }.takeIf { it >= 0 } ?: current.size
        } else {
            current.indexOfFirst { it.isPluginTab || it.id == "logs" }
                .takeIf { it >= 0 } ?: current.size
        }
        current.add(insertAt, tab)
        _tabs.value = current
        logger.info { "Tab added: ${tab.id} (${tab.name}), total tabs: ${_tabs.value.size}, ids: ${_tabs.value.map { it.id }}" }
    }

    /**
     * Обновляет вкладку
     */
    fun updateTab(
        id: String,
        name: String,
        filters: List<TabFilter>,
        captureMode: CaptureMode,
        profileTab: Boolean = false,
        profileLog: Boolean = false,
        persistContent: Boolean = false,
        timestamps: Boolean = false
    ) {
        if (id == "main") {
            logger.info { "Cannot update main tab" }
            return
        }
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == id) {
                // Создаем новую вкладку с теми же параметрами, но новыми фильтрами
                val newTab = Tab(
                    id = tab.id,
                    name = name,
                    filters = filters,
                    captureMode = captureMode,
                    maxLines = tab.maxLines,
                    profileTab = profileTab,
                    profileLog = profileLog || profileTab,
                    persistContent = persistContent,
                    timestamps = timestamps
                )
                // Копируем старое содержимое
                val oldContent = tab.content.value
                if (oldContent.isNotEmpty()) {
                    newTab.appendText(oldContent)
                }
                newTab
            } else {
                tab
            }
        }
    }

    /**
     * Текущий порядок вкладок (id, без системных main/logs) для сохранения.
     */
    fun getTabOrder(): List<String> =
        _tabs.value.filter { it.id != "main" && it.id != "logs" }.map { it.id }

    /**
     * Применяет сохранённый порядок вкладок. Вкладки из [order] идут в указанном
     * порядке, неизвестные (новые/плагинные) — после них, сохраняя относительный
     * порядок. main всегда первая, logs — последняя.
     */
    fun applyTabOrder(order: List<String>) {
        if (order.isEmpty()) return
        val list = _tabs.value
        val main = list.filter { it.id == "main" }
        val logs = list.filter { it.id == "logs" }
        val middle = list.filter { it.id != "main" && it.id != "logs" }
        val ordered = order.mapNotNull { id -> middle.find { it.id == id } }
        val rest = middle.filter { m -> order.none { it == m.id } }
        _tabs.value = main + ordered + rest + logs
    }

    /**
     * Должна ли строка быть скрыта из основного лога: её забирает вкладка
     * в режиме «Перемещать» (MOVE) — то есть строка переносится во вкладку,
     * а не дублируется. Системные вкладки игнорируются.
     */
    fun shouldGagFromMain(cleanLine: String, rawLine: String): Boolean {
        for (tab in _tabs.value) {
            if (tab.id == "main" || tab.id == "logs") continue
            if (tab.captureMode == CaptureMode.MOVE && tab.captureAndTransform(cleanLine, rawLine) != null) {
                return true
            }
        }
        return false
    }

    /**
     * Перемещает вкладку [id] на место вкладки [targetId] (drag-and-drop).
     * Системные вкладки не двигаются: "main" всегда первая, "logs" — последняя,
     * поэтому перестановка возможна только между ними.
     */
    fun moveTabTo(id: String, targetId: String, placeAfter: Boolean = false) {
        if (id == "main" || id == "logs" || id == targetId) return
        val list = _tabs.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val tab = list.removeAt(from)
        val to = list.indexOfFirst { it.id == targetId }
        if (to < 0) {
            // Цель не найдена — возвращаем на место
            list.add(from, tab)
            return
        }
        // С какой стороны от целевой встать; не раньше main (0) и не после logs (последний)
        val insertAt = (if (placeAfter) to + 1 else to).coerceIn(1, list.size - 1)
        list.add(insertAt, tab)
        _tabs.value = list
        logger.info { "Tab '$id' moved ${if (placeAfter) "after" else "before"} '$targetId': ${list.map { it.id }}" }
    }

    /**
     * Удаляет вкладку
     */
    fun removeTab(id: String) {
        if (id == "main" || id == "logs") {
            logger.info { "Cannot remove system tab: $id" }
            return
        }
        _tabs.value = _tabs.value.filter { it.id != id }

        // Если удалили активную вкладку, переключаемся на главную
        if (_activeTabId.value == id) {
            _activeTabId.value = "main"
        }
    }

    /**
     * Получает вкладку по ID
     */
    fun getTab(id: String): Tab? {
        return _tabs.value.find { it.id == id }
    }

    /**
     * Устанавливает активную вкладку
     */
    fun setActiveTab(id: String) {
        if (_tabs.value.any { it.id == id }) {
            // Принудительно обновляем содержимое вкладки перед показом
            val tab = getTab(id)
            tab?.flush()
            // Сбрасываем индикатор непрочитанных
            tab?.markAsRead()
            _activeTabId.value = id
        }
    }

    /**
     * Обрабатывает входящий текст и распределяет его по вкладкам
     * Возвращает текст, который должен остаться в главной вкладке
     */
    fun processText(text: String): String = com.bylins.client.perf.Perf.measure(
        com.bylins.client.perf.Perf.Stage.TABS_ROUTE,
        text.length.toLong()
    ) {
        // Используем общий ansiParser вместо создания нового
        val lines = text.split("\n")
        val mainLines = mutableListOf<String>()

        for (line in lines) {
            var capturedByMove = false

            // Удаляем ANSI-коды для проверки фильтров
            val cleanLine = ansiParser.stripAnsi(line)

            // Проверяем каждую вкладку (кроме главной)
            val currentActiveTabId = _activeTabId.value
            for (tab in _tabs.value) {
                if (tab.id == "main") continue

                val transformedLine = tab.captureAndTransform(cleanLine, line)
                if (transformedLine != null) {
                    // Добавляем трансформированную строку в эту вкладку
                    // Помечаем как непрочитанную если вкладка не активна
                    val isActive = tab.id == currentActiveTabId
                    tab.appendText(tab.stamp(transformedLine), markUnread = !isActive)

                    // Если режим MOVE, помечаем что не нужно добавлять в main
                    if (tab.captureMode == CaptureMode.MOVE) {
                        capturedByMove = true
                    }
                }
            }

            // Добавляем в главную вкладку, если не было захвачено с MOVE
            if (!capturedByMove) {
                mainLines.add(line)
            }
        }

        val mainText = mainLines.joinToString("\n")

        // Добавляем в главную вкладку
        if (mainText.isNotEmpty()) {
            mainTab.appendText(mainText)
        }

        // Принудительно обновляем UI для всех вкладок
        mainTab.flush()
        for (tab in _tabs.value) {
            if (tab.id != "main") {
                tab.flush()
            }
        }

        return mainText
    }

    /**
     * Добавляет текст напрямую в главную вкладку (без фильтрации)
     * Используется для системных сообщений от скриптов/плагинов
     * Примечание: для отображения используется receivedData, это только для сохранения в лог
     */
    fun addToMainTab(text: String) {
        if (text.isEmpty()) return

        mainTab.appendText(text)
        mainTab.flush()
    }

    /**
     * Добавляет текст в вкладку системных логов
     */
    fun addToLogsTab(text: String) {
        if (text.isEmpty()) return

        val isActive = _activeTabId.value == "logs"
        logsTab.appendText(text, markUnread = !isActive)
        logsTab.flush()
    }

    /**
     * Очищает все вкладки
     */
    fun clearAll() {
        _tabs.value.forEach { it.clear() }
    }

    /**
     * Очищает конкретную вкладку
     */
    fun clearTab(id: String) {
        getTab(id)?.clear()
    }

    /**
     * Загружает вкладки из списка
     */
    fun loadTabs(tabs: List<Tab>) {
        // Сохраняем существующие вкладки плагинов (они уже были созданы до loadTabs)
        val existingPluginTabs = _tabs.value.filter { it.isPluginTab }

        // Ищем сохранённые системные вкладки
        val savedMainTab = tabs.find { it.id == "main" }
        val savedLogsTab = tabs.find { it.id == "logs" }
        val otherTabs = tabs.filter { it.id != "main" && it.id != "logs" }

        // Восстанавливаем содержимое главной вкладки
        if (savedMainTab != null) {
            val savedContent = savedMainTab.content.value
            if (savedContent.isNotEmpty()) {
                mainTab.appendText(savedContent)
                mainTab.flush()
            }
        }

        // Восстанавливаем содержимое вкладки логов
        if (savedLogsTab != null) {
            val savedContent = savedLogsTab.content.value
            if (savedContent.isNotEmpty()) {
                logsTab.appendText(savedContent)
                logsTab.flush()
            }
        }

        // Добавляем welcome message после восстановленного лога
        mainTab.appendText("\nДобро пожаловать в Bylins MUD Client!\nПодключитесь к серверу для начала игры.\n")
        mainTab.flush()

        // Сохраняем: mainTab, пользовательские вкладки, вкладки плагинов, logsTab (в конце)
        // Вкладки плагинов добавляются перед logsTab, чтобы сохранить порядок
        _tabs.value = listOf(mainTab) + otherTabs + existingPluginTabs + listOf(logsTab)
        logger.info { "Tabs loaded: ${_tabs.value.map { it.id }}, plugin tabs preserved: ${existingPluginTabs.map { it.id }}" }
    }

    /**
     * Возвращает все вкладки (включая главную) для сохранения.
     * Вкладки плагинов исключаются - они создаются плагинами при загрузке.
     */
    fun getTabsForSave(): List<Tab> {
        return _tabs.value.filter { !it.isPluginTab }
    }

    /**
     * Глобальные вкладки для сохранения в общий конфиг
     * (системные main/logs + непрофильные пользовательские).
     */
    fun getGlobalTabsForSave(): List<Tab> {
        return _tabs.value.filter { !it.isPluginTab && !it.profileTab }
    }

    /**
     * Профильные вкладки текущего загруженного сервера для сохранения в его профиль.
     */
    fun getProfileTabsForSave(): List<Tab> {
        return _tabs.value.filter { !it.isPluginTab && it.profileTab }
    }

    /**
     * Лог глобальных вкладок с профильным логом (profileLog, но не profileTab) —
     * для сохранения в профиль текущего сервера. Только при persistContent.
     * Возвращает id вкладки → текущее содержимое.
     */
    fun getPerProfileLogs(): Map<String, String> {
        return _tabs.value
            .filter { !it.isPluginTab && !it.profileTab && it.profileLog && it.persistContent }
            .associate { it.id to it.content.value }
    }

    /**
     * Применяет профильные логи к глобальным вкладкам с профильным логом:
     * у каждой такой вкладки буфер заменяется на лог текущего сервера (или пустой).
     */
    fun applyPerProfileLogs(logs: Map<String, String>) {
        for (tab in _tabs.value) {
            if (tab.isPluginTab || tab.profileTab || !tab.profileLog) continue
            tab.clear()
            val content = logs[tab.id]
            if (!content.isNullOrEmpty()) {
                tab.appendText(content)
                tab.flush()
            }
        }
    }

    /**
     * Заменяет набор профильных вкладок (profileTab) на вкладки нового сервера.
     * Системные, глобальные и плагинные вкладки сохраняются.
     */
    fun setProfileTabs(profileTabs: List<Tab>) {
        val kept = _tabs.value.filter { it.profileTab.not() || it.isPluginTab }
        // Профильные вкладки вставляем перед системной вкладкой "Логи"
        val logsIndex = kept.indexOfFirst { it.id == "logs" }
        val merged = if (logsIndex >= 0) {
            kept.subList(0, logsIndex) + profileTabs + kept.subList(logsIndex, kept.size)
        } else {
            kept + profileTabs
        }
        _tabs.value = merged

        // Если активная вкладка исчезла — переключаемся на главную
        if (_tabs.value.none { it.id == _activeTabId.value }) {
            _activeTabId.value = "main"
        }
        logger.info { "Profile tabs set: ${profileTabs.map { it.id }}, total: ${_tabs.value.map { it.id }}" }
    }
}
