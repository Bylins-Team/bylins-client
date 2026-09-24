package com.bylins.client.tabs

import com.bylins.client.ui.scroll.LineBuffer
import com.bylins.client.ui.scroll.LineSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Представляет вкладку с выводом текста
 */
data class Tab(
    val id: String,
    val name: String,
    val filters: List<TabFilter> = emptyList(),
    val captureMode: CaptureMode = CaptureMode.COPY,
    val maxLines: Int = 2000,  // Уменьшено с 10000 до 2000 для экономии памяти
    val isPluginTab: Boolean = false,  // Вкладка создана плагином (не редактируется пользователем)
    val profileTab: Boolean = false,   // Видна только на своём сервере (определение в профиле)
    val profileLog: Boolean = false,   // Лог свой на каждый сервер (profileTab ⟹ profileLog)
    val persistContent: Boolean = false, // Сохранять лог вкладки между запусками (по умолчанию нет)
    // Помечать каждую пойманную строку временем. Для чата и торговли это
    // единственный способ понять, когда сообщение пришло: в самом выводе
    // сервера времени нет
    val timestamps: Boolean = false
) {
    // Снимок буфера по строкам с абсолютной нумерацией (для автоскролла и выделения)
    private val _snapshot = MutableStateFlow(LineSnapshot.EMPTY)
    val snapshot: StateFlow<LineSnapshot> = _snapshot

    // Индикатор непрочитанных сообщений (для неактивных вкладок)
    private val _hasUnreadMessages = MutableStateFlow(false)
    val hasUnreadMessages: StateFlow<Boolean> = _hasUnreadMessages

    private val buffer = LineBuffer(maxLines)

    // Во вкладку пишут разные потоки: читающий сокет раскладывает вывод сервера,
    // плагины (в том числе команды ИИ) добавляют свой текст из своих потоков.
    // Без замка параллельные правки списка теряют строки или ломают его.
    private val linesLock = Any()

    /**
     * Добавляет текст во вкладку
     * @param markUnread если true, помечает вкладку как имеющую непрочитанные сообщения
     */
    fun appendText(text: String, markUnread: Boolean = false) = synchronized(linesLock) {
        for (line in text.split("\n")) {
            // Пропускаем дублирующиеся пустые строки
            if (line.isEmpty() && buffer.lastLine?.isEmpty() == true) continue
            buffer.addLine(line)
        }

        // Помечаем непрочитанные сообщения
        if (markUnread) {
            _hasUnreadMessages.value = true
        }

        // Снимок — копия ссылок на строки, дешёвая: публикуем на каждое добавление
        _snapshot.value = buffer.snapshot()
    }

    /** Весь текст вкладки одной строкой — для сохранения лога, не для показа. */
    fun contentText(): String = _snapshot.value.text()

    /**
     * Очищает содержимое вкладки
     */
    fun clear() = synchronized(linesLock) {
        // Сохраняем монотонность seq: очищенные строки считаются вытесненными
        buffer.clear()
        _snapshot.value = buffer.snapshot()
    }

    /**
     * Сбрасывает индикатор непрочитанных сообщений
     */
    fun markAsRead() {
        _hasUnreadMessages.value = false
    }

    /**
     * Приписывает время к строке, если вкладка этого просит.
     *
     * Метка ставится при захвате, а не при выводе: сохранённый лог должен
     * помнить, когда сообщение пришло, а не когда его показали.
     */
    fun stamp(line: String): String {
        if (!timestamps) return line
        val now = java.time.LocalTime.now()
        val time = "%02d:%02d:%02d".format(now.hour, now.minute, now.second)
        // Серым, чтобы метка не спорила с цветами самого сообщения
        return "\u001B[90m[$time]\u001B[0m $line"
    }

    /**
     * Проверяет, должна ли строка попасть в эту вкладку
     * @param cleanLine строка без ANSI-кодов
     * @param rawLine оригинальная строка с ANSI-кодами
     * @return трансформированная строка или null если не матчит
     */
    fun captureAndTransform(cleanLine: String, rawLine: String): String? {
        if (filters.isEmpty()) return null
        for (filter in filters) {
            val result = filter.transform(cleanLine, rawLine)
            if (result != null) return result
        }
        return null
    }
}

/**
 * Фильтр для захвата текста во вкладку
 * @param pattern regex паттерн для матчинга
 * @param replacement строка замены (null = копировать как есть, иначе применить замену с $1, $2...)
 * @param matchWithColors true = матчить по строке с ANSI-кодами цветов
 */
data class TabFilter(
    val pattern: Regex,
    val replacement: String? = null,  // null = копировать как есть
    val matchWithColors: Boolean = false,
    val includeMatched: Boolean = true  // deprecated, kept for compatibility
) {
    /**
     * Трансформирует строку если она матчит паттерн
     * @param cleanLine строка без ANSI-кодов
     * @param rawLine оригинальная строка с ANSI-кодами
     * @return трансформированная строка или null если не матчит
     */
    fun transform(cleanLine: String, rawLine: String): String? {
        val lineToMatch = if (matchWithColors) rawLine else cleanLine
        val match = pattern.find(lineToMatch) ?: return null

        // Если замена не задана - возвращаем оригинальную строку
        if (replacement == null) {
            return rawLine
        }

        // Применяем замену с поддержкой $0, $1, $2...
        var result = replacement
        match.groupValues.forEachIndexed { index, value ->
            result = result!!.replace("\$$index", value)
        }
        return result
    }
}

/**
 * Режим захвата текста
 */
enum class CaptureMode {
    /**
     * Копирует текст в эту вкладку, оставляя в основной
     */
    COPY,

    /**
     * Перемещает текст в эту вкладку, удаляя из основной
     */
    MOVE
}

/**
 * DTO для сериализации
 */
@Serializable
data class TabDto(
    val id: String,
    val name: String,
    val filters: List<TabFilterDto> = emptyList(),
    val captureMode: String = "COPY",
    val maxLines: Int = 10000,
    val content: String? = null,  // Сохранённое содержимое вкладки (только если persistContent)
    val perProfile: Boolean = false,   // legacy: старое поле, мигрируется в profileTab
    val profileTab: Boolean = false,
    val profileLog: Boolean = false,
    val persistContent: Boolean = false,
    val timestamps: Boolean = false
) {
    fun toTab(): Tab {
        // ONLY был удалён, старые конфиги с ONLY будут использовать COPY
        val mode = try {
            CaptureMode.valueOf(captureMode)
        } catch (e: IllegalArgumentException) {
            CaptureMode.COPY
        }
        // Миграция: старое perProfile == профильная вкладка; каскад profileTab ⟹ profileLog
        val pt = profileTab || perProfile
        val pl = profileLog || pt
        val tab = Tab(
            id = id,
            name = name,
            filters = filters.map { it.toTabFilter() },
            captureMode = mode,
            maxLines = maxLines,
            profileTab = pt,
            profileLog = pl,
            persistContent = persistContent,
            timestamps = timestamps
        )
        // Восстанавливаем содержимое
        if (!content.isNullOrEmpty()) {
            tab.appendText(content)
        }
        return tab
    }

    companion object {
        fun fromTab(tab: Tab): TabDto {
            return TabDto(
                id = tab.id,
                name = tab.name,
                filters = tab.filters.map { TabFilterDto.fromTabFilter(it) },
                captureMode = tab.captureMode.name,
                maxLines = tab.maxLines,
                // Лог в самом TabDto храним, только если он НЕ профильный (профильный
                // лог глобальной вкладки лежит в profile.tabLogs). Для профильной вкладки
                // (profileTab) её лог хранится здесь же, в её TabDto внутри профиля.
                content = if (tab.persistContent && (tab.profileTab || !tab.profileLog))
                    tab.contentText().takeIf { it.isNotEmpty() } else null,
                profileTab = tab.profileTab,
                profileLog = tab.profileLog,
                persistContent = tab.persistContent,
                timestamps = tab.timestamps
            )
        }
    }
}

@Serializable
data class TabFilterDto(
    val pattern: String,
    val replacement: String? = null,
    val matchWithColors: Boolean = false,
    val includeMatched: Boolean = true  // deprecated
) {
    fun toTabFilter(): TabFilter {
        return TabFilter(
            pattern = pattern.toRegex(),
            replacement = replacement,
            matchWithColors = matchWithColors
        )
    }

    companion object {
        fun fromTabFilter(filter: TabFilter): TabFilterDto {
            return TabFilterDto(
                pattern = filter.pattern.pattern,
                replacement = filter.replacement,
                matchWithColors = filter.matchWithColors
            )
        }
    }
}
