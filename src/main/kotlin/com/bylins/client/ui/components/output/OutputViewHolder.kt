package com.bylins.client.ui.components.output

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.bylins.client.ui.scroll.OutputScrollController
import com.bylins.client.ui.scroll.OutputSearch
import com.bylins.client.ui.scroll.OutputSelection

/**
 * Долгоживущее состояние панели вывода для одной вкладки.
 *
 * Хранится в ClientState (а не в remember внутри OutputPanel), чтобы переживать
 * уход OutputPanel из композиции при переключении верхнеуровневых вкладок —
 * тогда позиция скролла, режим follow/split и выделение не сбрасываются.
 *
 * Инкапсулирует Compose-зависимости (mutableStateOf, TextLayoutResult), поэтому
 * пакет ui.scroll остаётся чистым и тестируемым.
 */
class OutputViewHolder {
    val controller = OutputScrollController()
    val selection = OutputSelection()
    val search = OutputSearch()

    // Открыта ли строка поиска (своя на вкладку)
    private val _searchActive = mutableStateOf(false)
    var searchActive: Boolean
        get() = _searchActive.value
        set(value) { _searchActive.value = value }

    // Сигнал «активировать поле поиска» (Ctrl+F): инкремент перезапрашивает фокус
    // и выделяет весь текст в поле, даже если поиск уже был открыт.
    private val _searchOpenSignal = mutableStateOf(0)
    val searchOpenSignal: Int get() = _searchOpenSignal.value
    fun bumpSearchOpen() { _searchOpenSignal.value++ }

    // Ревизия поиска: инкремент перерисовывает подсветку совпадений в фазе draw
    private val _searchRevision = mutableStateOf(0)
    val searchRevisionState: State<Int> get() = _searchRevision
    fun bumpSearch() { _searchRevision.value++ }

    // Позиции скролла в пикселях (snapshot-state — изменение перерисовывает Canvas)
    private val _liveScrollPx = mutableStateOf(0f)
    var liveScrollPx: Float
        get() = _liveScrollPx.value
        set(value) { _liveScrollPx.value = value }

    private val _scrollbackScrollPx = mutableStateOf(0f)
    var scrollbackScrollPx: Float
        get() = _scrollbackScrollPx.value
        set(value) { _scrollbackScrollPx.value = value }

    /**
     * Скопировать выделение этой панели; true -- было что копировать.
     *
     * Ставит сама панель: только у неё есть разобранные строки. Нужен, чтобы Ctrl+C
     * работал и когда фокус в строке ввода, а это почти всегда.
     */
    var copySelection: (() -> Boolean)? = null

    // Идёт ли сейчас выделение мышью. Пока true — автоскролл (follow) к низу
    // заморожен, чтобы заякоренное выделение не уезжало за экран при новом тексте.
    var isSelecting = false

    // Идёт ли активное перетаскивание ползунка скроллбара (тоже замораживает автоскролл).
    var isScrolling = false

    // Якорь верхней видимой позиции в координатах (абсолютный seq строки, столбец).
    // Пере-привязка по нему держит один и тот же контент наверху при любом изменении
    // высоты вьюпорта/контента (точно, по символу — без «защёлкивания» к началу строки).
    var anchorSeq: Long = 0L
    var anchorCol: Int = 0
    // Суб-строчный сдвиг: пиксель скролла минус верх визуальной строки якоря.
    // Сохраняет точную позицию при пере-привязке (без «защёлкивания» к строке).
    var anchorOffsetPx: Float = 0f

    // Доля высоты под живой хвост — СВОЯ на каждую вкладку (раздельные разделители).
    private val _splitFraction = mutableStateOf(0.3f)
    var splitFraction: Float
        get() = _splitFraction.value
        set(value) { _splitFraction.value = value.coerceIn(0.1f, 0.9f) }

    // Ревизия выделения: инкремент заставляет панель перерисовать подсветку,
    // т.к. сама OutputSelection — не snapshot-state.
    private val _selectionRevision = mutableStateOf(0)
    val selectionRevision: Int get() = _selectionRevision.value
    // Состояние для чтения в фазе отрисовки Canvas (перерисовка без рекомпозиции)
    val selectionRevisionState: State<Int> get() = _selectionRevision
    fun bumpSelection() { _selectionRevision.value++ }

    // Наблюдаемое зеркало controller.isSplit (controller — чистый, не snapshot-state).
    // Синхронизируется после изменения режима, чтобы перестроить раздвоение панелей.
    private val _split = mutableStateOf(false)
    var split: Boolean
        get() = _split.value
        set(value) { _split.value = value }

    /** Синхронизирует наблюдаемый split с состоянием контроллера. */
    fun syncSplit() { _split.value = controller.isSplit }
}
