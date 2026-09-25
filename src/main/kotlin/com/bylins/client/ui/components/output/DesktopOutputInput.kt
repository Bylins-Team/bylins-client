package com.bylins.client.ui.components.output

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.bylins.client.perf.Perf
import com.bylins.client.ui.AnsiParser
import com.bylins.client.ui.CommandModifier
import androidx.compose.ui.text.TextLayoutResult
import com.bylins.client.ui.scroll.BufferGeometry
import com.bylins.client.ui.scroll.LineLayoutCache
import com.bylins.client.ui.scroll.LineParseCache
import com.bylins.client.ui.scroll.LineSnapshot
import com.bylins.client.ui.scroll.ScrollTarget

private val SELECTION_COLOR = Color(0x804A90E2)
private val DIVIDER_COLOR = Color(0xFF555555)
private val DIVIDER_HEIGHT = 6.dp

/**
 * Панель вывода со split-scrollback и собственным выделением (desktop-слой ввода).
 *
 * Внизу — одно окно с автоскроллом. При скролле вверх делится: верхняя панель —
 * скроллбэк (заякорена), нижняя — живой хвост (всегда автоскролл), между ними
 * перетаскиваемый разделитель. Докрутка скроллбэка до низа схлопывает обратно.
 * Выделение (по всему буферу) и drag живут на корне и не рвутся при раздвоении/
 * схлопывании и приходе нового текста. Копирование Ctrl+C/Cmd+C/Ctrl+Insert, Ctrl+A.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScrollbackOutputView(
    snapshot: LineSnapshot,
    holder: OutputViewHolder,
    splitFraction: Float,
    onSplitFractionChange: (Float) -> Unit,
    fontFamily: FontFamily,
    fontSize: Int,
    emptyPlaceholder: String,
    onSearchFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val clipboard = LocalClipboardManager.current
    // Ctrl, а на macOS ещё и Cmd: выделение, копирование и поиск живут там
    // на Cmd, и прибитый к Ctrl набор оставлял мак с половиной сочетаний
    val isCommand: (KeyEvent) -> Boolean = { event ->
        CommandModifier.isPressed(event.isCtrlPressed, event.isMetaPressed)
    }
    val focusRequester = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searchQuery by remember { mutableStateOf(holder.search.query) }
    val controller = holder.controller
    val selection = holder.selection
    val ansiParser = remember { AnsiParser() }
    val parseCache = remember { LineParseCache { text, state -> ansiParser.parse(text, state) } }
    val layoutCache = remember { LineLayoutCache<TextLayoutResult>() }

    // Пустой буфер показывает подсказку — тем же путём, что и вывод, но без
    // выделения и раздвоения
    val isEmpty = snapshot.isEmpty
    val shown = remember(snapshot, emptyPlaceholder) {
        if (isEmpty) LineSnapshot.of(emptyPlaceholder, snapshot.firstSeq) else snapshot
    }

    // Разбор ANSI — по строкам, заново только изменившиеся (в норме одна).
    // «Объём» в замере — сколько строк разобрано
    val parsed = remember(shown) {
        val started = System.nanoTime()
        parseCache.update(shown).also {
            Perf.record(Perf.Stage.UI_ANSI, System.nanoTime() - started, it.parsedCount.toLong())
        }
    }
    // Видимый текст по строкам — для поиска и копирования, без склейки в одну строку
    val plainLines: List<CharSequence> = remember(parsed) {
        object : AbstractList<CharSequence>() {
            override val size: Int get() = parsed.lineCount
            override fun get(index: Int): CharSequence = parsed.lines[index].plain
        }
    }

    // Кадр с новыми данными нарисован: закрывает сквозной замер «байты пришли
    // -> игрок увидел». Ключ — снимок: эффект перезапускается на каждой порции
    LaunchedEffect(snapshot) {
        withFrameNanos { Perf.painted() }
    }
    val geometry = BufferGeometry(
        firstSeq = parsed.firstSeq,
        lineCount = if (isEmpty) 0 else parsed.lineCount
    )

    val style = remember(fontFamily, fontSize) {
        TextStyle(
            color = Color(0xFFBBBBBB),
            fontFamily = fontFamily,
            fontSize = fontSize.sp,
            lineHeight = (fontSize + 4).sp
        )
    }
    val lineHeightPx = with(density) { (fontSize + 4).sp.toPx() }

    val scrollbarStrip = 12.dp
    val scrollbarStripPx = with(density) { scrollbarStrip.toPx() }

    // Размер берём через onSizeChanged (а не BoxWithConstraints): BoxWithConstraints —
    // это SubcomposeLayout, его контент рекомпозируется лениво (только при перемере),
    // из-за чего изменения скролла/выделения не перерисовывались на «статичных» вкладках.
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .background(Color.Black)
            .padding(8.dp)
            .onSizeChanged { sizePx = it }
    ) {
        if (sizePx.width > 0 && sizePx.height > 0) {
            val fullViewportPx = sizePx.height.toFloat()
            val widthPx = (sizePx.width - scrollbarStripPx).toInt().coerceAtLeast(1)

            // Разметка есть только у строк около якоря прокрутки и у хвоста —
            // по вьюпорту запаса в каждую сторону, чтобы листание страницами не
            // выходило за размеченное. Остальные стоят в стопке по оценке высоты
            // (ширина символа и высота строки — по образцу), и оценка уточняется,
            // когда строка доезжает до вьюпорта. «Объём» в замере — сколько
            // строк размечено заново
            val constraints = remember(widthPx) { Constraints(maxWidth = widthPx) }
            val sample = remember(style) { measurer.measure(text = AnnotatedString("0"), style = style, softWrap = false) }
            val sampleHeight = sample.size.height.toFloat()
            val columns = (widthPx / sample.size.width.coerceAtLeast(1)).coerceAtLeast(1)
            val visibleLines = (fullViewportPx / lineHeightPx).toInt() + 1
            val anchorIndex = parsed.indexOfSeq(if (controller.followMode) parsed.lastSeq else holder.anchorSeq)
            val window = remember(parsed, widthPx, style, anchorIndex, visibleLines) {
                val started = System.nanoTime()
                val wanted = listOf(
                    (anchorIndex - visibleLines)..(anchorIndex + 2 * visibleLines),
                    (parsed.lineCount - 2 * visibleLines)..(parsed.lineCount - 1)
                )
                layoutCache.update(
                    parsed = parsed,
                    key = widthPx to style,
                    wanted = wanted,
                    estimate = { line -> estimateVisualLines(line.plain, columns) * sampleHeight },
                    measure = { line ->
                        measurer.measure(text = line, style = style, softWrap = true, constraints = constraints)
                    },
                    heightOf = { it.size.height.toFloat() }
                ).also {
                    Perf.record(Perf.Stage.UI_MEASURE, System.nanoTime() - started, it.measuredCount.toLong())
                }
            }
            val contentHeight = window.totalHeight

            // Всегда две стыкующиеся панели: верх (скроллбэк) + низ (живой хвост).
            // Сумма высот = вьюпорт (разделитель — лишь линия-оверлей, места не занимает).
            val bottomPaneHeightPx = fullViewportPx * splitFraction
            val topPaneHeightPx = fullViewportPx - bottomPaneHeightPx
            // Живой хвост всегда прижат к концу лога
            val bottomScrollPx = maxScrollOf(contentHeight, bottomPaneHeightPx)
            // Максимум скролла скроллбэка = обычный (как у одного окна). На нём низ верхней
            // панели стыкуется с верхом хвоста → виды непрерывны (выглядит как одно окно).
            val maxScroll = maxScrollOf(contentHeight, fullViewportPx)
            // Актуальный предел прокрутки для долгоживущих обработчиков:
            // пока пользователь читает старое, конец лога уезжает вниз
            val maxScrollRef by rememberUpdatedState(maxScroll)

            // Целевая позиция скроллбэка — прямо в композиции, из свежего окна.
            // Если следуем за низом — конец; иначе заякоренный символ (seq, col)
            // на той же высоте, его точный пиксель — по новой разметке. Во время
            // выделения и перетаскивания ползунка позиция заморожена (камень №16).
            //
            // Раньше это делал LaunchedEffect — а он выполняется уже ПОСЛЕ кадра.
            // На тот один кадр maxScroll уже вырос, а позиция ещё старая: панель
            // считала себя раздвоенной, рисовала разделитель и нижнюю панель, и
            // следующим кадром схлопывалась — моргание при каждом шаге (#19)
            //
            // Позиция держателя читается всегда: её пишет и пользователь (колесо,
            // ползунок), и панель после этого обязана перекомпоноваться —
            // раздвоение и области разметки выводятся из позиции
            val heldPx = holder.scrollbackScrollPx
            val scrollbackPx = when {
                holder.isSelecting || holder.isScrolling -> heldPx
                controller.followMode -> maxScroll
                else -> window.anchorToPx(holder.anchorSeq, holder.anchorCol) + holder.anchorOffsetPx
            }.coerceIn(0f, maxScroll)
            // Держатель узнаёт позицию до отрисовки кадра: SideEffect выполняется
            // сразу по применении композиции, а фаза draw читает holder
            SideEffect { holder.scrollbackScrollPx = scrollbackPx }
            // Раздвоение (видимость разделителя) выводим прямо из позиции скролла:
            // скроллбэк не у самого низа ⇒ есть разрыв с живым хвостом.
            val split = !isEmpty && scrollbackPx < maxScroll - lineHeightPx

            // Выделение и совпадения читаются в фазе draw (через провайдеры):
            // панель перерисовывается при каждом их изменении без рекомпозиции,
            // а пути строит сама — только для видимых строк
            val selectionProvider: () -> Pair<com.bylins.client.ui.scroll.SelPoint, com.bylins.client.ui.scroll.SelPoint>? = {
                if (isEmpty) null else selection.normalized()
            }
            val revisionState = holder.selectionRevisionState
            // Провайдер позиции скроллбэка (верхняя панель) — читается в фазе draw
            val scrollbackProvider: () -> Float = { holder.scrollbackScrollPx.coerceIn(0f, maxScroll) }

            // --- Поиск: подсветка совпадений (в фазе draw) ---
            val searchRevisionState = holder.searchRevisionState
            val matchesProvider: (Long, Long) -> List<com.bylins.client.ui.scroll.SearchMatch> = { fromSeq, toSeq ->
                if (isEmpty || !holder.searchActive) emptyList() else holder.search.matchesBetween(fromSeq, toSeq)
            }
            val currentMatchProvider: () -> com.bylins.client.ui.scroll.SearchMatch? = {
                if (isEmpty || !holder.searchActive) null else holder.search.current
            }

            // --- Действия (пересоздаются каждую рекомпозицию, видят актуальные значения) ---
            val userScrollTo: (Float) -> Unit = { target ->
                val clamped = target.coerceIn(0f, maxScroll)
                // У самого низа защёлкиваем точно в конец (чтобы виды были непрерывны)
                val atBottom = clamped >= maxScroll - lineHeightPx
                holder.scrollbackScrollPx = if (atBottom) maxScroll else clamped
                val (aseq, acol) = window.pxToAnchor(clamped)
                holder.anchorSeq = aseq
                holder.anchorCol = acol
                holder.anchorOffsetPx = clamped - window.anchorToPx(aseq, acol)
                controller.onUserScroll(atBottom, aseq)
            }
            val collapse: () -> Unit = {
                controller.jumpToBottom()
                // maxScroll берём текущий: пока пользователь читал старый вывод,
                // пришли новые строки и конец лога уехал вниз
                holder.scrollbackScrollPx = maxScrollRef
            }
            // Прокрутить к текущему совпадению (через якорь, с парой строк контекста сверху)
            val jumpToMatch: () -> Unit = {
                holder.search.current?.let { m ->
                    val targetSeq = (m.seq - 2).coerceAtLeast(parsed.firstSeq)
                    holder.anchorSeq = targetSeq
                    holder.anchorCol = 0
                    holder.anchorOffsetPx = 0f
                    controller.jumpToLine(targetSeq)
                    holder.scrollbackScrollPx =
                        window.anchorToPx(targetSeq, 0).coerceIn(0f, maxScroll)
                }
            }
            val onSearchQueryChange: (String) -> Unit = { q ->
                searchQuery = q
                holder.search.update(q, parsed.firstSeq, plainLines)
                holder.bumpSearch()
                jumpToMatch()
            }
            val nextMatch: () -> Unit = { holder.search.next(); holder.bumpSearch(); jumpToMatch() }
            val prevMatch: () -> Unit = { holder.search.prev(); holder.bumpSearch(); jumpToMatch() }
            val closeSearch: () -> Unit = {
                holder.searchActive = false
                holder.bumpSearch() // перерисовать без подсветки
                onSearchFocusChanged(false)
                runCatching { focusRequester.requestFocus() }
            }
            // Перепоиск при изменении контента/опций (без перехода — только обновить подсветку/счётчик)
            LaunchedEffect(parsed, searchQuery, holder.search.caseSensitive, holder.search.useRegex) {
                if (searchQuery.isNotEmpty()) { holder.search.update(searchQuery, parsed.firstSeq, plainLines); holder.bumpSearch() }
            }
            // Указатель -> точка выделения (с учётом того, в какой панели курсор)
            val pointToSel: (Offset) -> com.bylins.client.ui.scroll.SelPoint = { pos ->
                val inBottom = split && pos.y >= topPaneHeightPx
                val contentY = if (inBottom) {
                    (pos.y - topPaneHeightPx) + bottomScrollPx
                } else {
                    pos.y + scrollbackPx
                }
                window.pointToSelPoint(pos.x, contentY)
            }
            val copySelection: () -> Boolean = {
                val text = if (isEmpty) "" else
                    selection.copyText(parsed.firstSeq, parsed.lineCount) { parsed.lines[it].plain }
                if (text.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(text))
                    true
                } else {
                    false
                }
            }
            // Способ скопировать выделение отдаём наружу: Ctrl+C приходит в строку
            // ввода, а не сюда -- фокус почти всегда там
            SideEffect { holder.copySelection = copySelection }
            val handleKey: (KeyEvent) -> Boolean = handleKey@{ event ->
                if (event.type != KeyEventType.KeyDown) return@handleKey false
                when {
                    com.bylins.client.ui.OutputSearchShortcut.isOpen(
                        key = com.bylins.client.hotkeys.PhysicalKey.of(event),
                        isCommandPressed = isCommand(event),
                        isAltPressed = event.isAltPressed,
                        isShiftPressed = event.isShiftPressed
                    ) -> {
                        holder.searchActive = true; holder.bumpSearchOpen(); true
                    }
                    event.key == Key.F3 && event.isShiftPressed -> { prevMatch(); true }
                    event.key == Key.F3 -> { nextMatch(); true }
                    event.key == Key.Escape && holder.searchActive -> { closeSearch(); true }
                    // Клавиша — физическая: в русской раскладке Ctrl+C приходит
                    // как «с», и копирование не срабатывало
                    com.bylins.client.ui.OutputClipboardShortcut.isSelectAll(
                        key = com.bylins.client.hotkeys.PhysicalKey.of(event),
                        isCommandPressed = isCommand(event)
                    ) -> {
                        selection.selectAll(geometry.firstSeq, geometry.lineCount); holder.bumpSelection(); true
                    }
                    com.bylins.client.ui.OutputClipboardShortcut.isCopy(
                        key = com.bylins.client.hotkeys.PhysicalKey.of(event),
                        isCommandPressed = isCommand(event),
                        isCtrlPressed = event.isCtrlPressed
                    ) -> { copySelection(); true }
                    event.key == Key.PageDown -> { userScrollTo(scrollbackPx + topPaneHeightPx); true }
                    event.key == Key.PageUp -> { userScrollTo(scrollbackPx - topPaneHeightPx); true }
                    event.key == Key.DirectionDown -> { userScrollTo(scrollbackPx + lineHeightPx); true }
                    event.key == Key.DirectionUp -> { userScrollTo(scrollbackPx - lineHeightPx); true }
                    event.key == Key.MoveHome -> { userScrollTo(0f); true }
                    event.key == Key.MoveEnd -> { userScrollTo(maxScroll); true }
                    else -> false
                }
            }

            // Ссылки на актуальные значения для долгоживущего drag-жеста выделения
            val pointToSelRef by rememberUpdatedState(pointToSel)
            val copySelectionRef by rememberUpdatedState(copySelection)
            val userScrollToRef by rememberUpdatedState(userScrollTo)
            val scrollbackRef by rememberUpdatedState(scrollbackPx)
            val topPaneHeightRef by rememberUpdatedState(topPaneHeightPx)
            val isEmptyRef by rememberUpdatedState(isEmpty)
            // Актуальные значения для долгоживущего drag разделителя (иначе захватится
            // устаревшая доля и разделитель «дёргается», а не двигается)
            val splitFractionRef by rememberUpdatedState(splitFraction)
            val fullViewportRef by rememberUpdatedState(fullViewportPx.coerceAtLeast(1f))
            // Кнопка «вниз» живёт в pointerInput(Unit) и иначе захватила бы
            // обработчик на момент начала чтения, а не на момент клика
            val collapseRef by rememberUpdatedState(collapse)
            // Высота строки меняется вместе с размером шрифта: без этого
            // автопрокрутка при выделении шагала бы старым шагом
            val lineHeightRef by rememberUpdatedState(lineHeightPx)
            // Колбэк доли разделителя приходит извне и замыкает id вкладки
            val onSplitFractionChangeRef by rememberUpdatedState(onSplitFractionChange)

            // Область контента + ввод (колесо/клавиши/drag), сужена под полосу скроллбара.
            // Скроллбар — отдельный сосед справа (вне этой области), чтобы его перетаскивание
            // не проваливалось в жест выделения.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = scrollbarStrip)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent(handleKey)
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) userScrollTo(scrollbackPx + dy * lineHeightPx * 3f)
                    }
                    .pointerInput(Unit) {
                        // Единый жест: tap (без движения) — сброс выделения; drag — выделение.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            focusRequester.requestFocus()
                            val slop = viewConfiguration.touchSlop
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!moved && !selection.isEmpty) {
                                        selection.clear()
                                        holder.bumpSelection()
                                    }
                                    // Выделил мышью -- текст уже в буфере обмена, как в
                                    // консоли и в старых мад-клиентах. Ctrl+C для этого не
                                    // годится: фокус почти всегда в строке ввода, и до
                                    // панели вывода нажатие не доходит
                                    if (moved) copySelectionRef()
                                    holder.isSelecting = false
                                    break
                                }
                                if (!isEmptyRef && change.positionChanged()) {
                                    if (!moved && (change.position - down.position).getDistance() >= slop) {
                                        moved = true
                                        holder.isSelecting = true
                                        selection.start(pointToSelRef(down.position))
                                        holder.bumpSelection()
                                    }
                                    if (moved) {
                                        val pos = change.position
                                        val edge = lineHeightRef
                                        if (pos.y < edge) userScrollToRef(scrollbackRef - lineHeightRef)
                                        else if (pos.y < topPaneHeightRef && pos.y > topPaneHeightRef - edge)
                                            userScrollToRef(scrollbackRef + lineHeightRef)
                                        selection.extendTo(pointToSelRef(pos))
                                        holder.bumpSelection()
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
            ) {
                if (split) {
                    // Две стыкующиеся панели: верх (скроллбэк) + низ (живой хвост у конца).
                    Column(Modifier.fillMaxSize()) {
                        OutputCanvas(
                            window = window,
                            scrollProvider = scrollbackProvider,
                            selectionColor = SELECTION_COLOR,
                            revisionState = revisionState,
                            searchRevisionState = searchRevisionState,
                            selectionProvider = selectionProvider,
                            matchesProvider = matchesProvider,
                            currentMatchProvider = currentMatchProvider,
                            modifier = Modifier.fillMaxWidth().weight(1f - splitFraction)
                        )
                        OutputCanvas(
                            window = window,
                            scrollProvider = { bottomScrollPx },
                            selectionColor = SELECTION_COLOR,
                            revisionState = revisionState,
                            searchRevisionState = searchRevisionState,
                            selectionProvider = selectionProvider,
                            matchesProvider = matchesProvider,
                            currentMatchProvider = currentMatchProvider,
                            modifier = Modifier.fillMaxWidth().weight(splitFraction)
                        )
                    }
                } else {
                    // Одно окно: скроллбэк внизу непрерывен с хвостом — показываем как единый вид.
                    OutputCanvas(
                        window = window,
                        scrollProvider = scrollbackProvider,
                        selectionColor = SELECTION_COLOR,
                        revisionState = revisionState,
                        searchRevisionState = searchRevisionState,
                        selectionProvider = selectionProvider,
                        matchesProvider = matchesProvider,
                        currentMatchProvider = currentMatchProvider,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Разделитель — сосед области контента (вне жеста выделения, чтобы его
            // перетаскивание не выделяло текст). Линия на границе панелей, с ручкой.
            if (split) {
                val dividerHalf = with(density) { DIVIDER_HEIGHT.toPx() } / 2f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = scrollbarStrip)
                        .height(DIVIDER_HEIGHT)
                        .offset { IntOffset(0, (topPaneHeightPx - dividerHalf).roundToInt()) }
                        .background(DIVIDER_COLOR)
                        .pointerHoverIcon(PointerIcon.Default)
                        // Колесо над разделителем прокручивает так же, как над контентом
                        // (иначе разделитель перехватывает событие и скролл «не работает»).
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) userScrollTo(scrollbackPx + dy * lineHeightPx * 3f)
                        }
                        .pointerInput(Unit) {
                            // Локальный аккумулятор от старта перетаскивания — чтобы не терять
                            // движение из-за лага рекомпозиции (иначе двигался «на фракцию»).
                            var startFrac = 0f
                            var accum = 0f
                            detectVerticalDragGestures(
                                onDragStart = { startFrac = splitFractionRef; accum = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    accum += dragAmount
                                    onSplitFractionChangeRef(startFrac - accum / fullViewportRef)
                                }
                            )
                        }
                )
            }

            // Единый интерактивный скроллбар (сосед области контента — не перехватывает выделение)
            OutputScrollbar(
                scrollProvider = scrollbackProvider,
                maxScroll = maxScroll,
                viewportPx = fullViewportPx,
                contentHeightPx = contentHeight,
                onScrollTo = userScrollTo,
                onActive = { holder.isScrolling = it },
                modifier = Modifier.align(Alignment.CenterEnd).width(scrollbarStrip).fillMaxHeight()
            )

            // Кнопка возврата в одно-панельный режим одним кликом (левее скроллбара)
            if (split) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = scrollbarStrip + 4.dp, bottom = 12.dp)
                        .size(30.dp)
                        .background(Color(0xCC2E2E2E), CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(Unit) { detectTapGestures(onTap = { collapseRef() }) },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "▼",
                        style = TextStyle(color = Color.White, fontSize = 14.sp)
                    )
                }
            }

            // Строка поиска (Ctrl+F) — оверлей сверху справа
            if (holder.searchActive) {
                holder.searchRevisionState.value // подписка: обновлять счётчик/индекс
                OutputSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    count = holder.search.count,
                    currentIndex = holder.search.currentIndex,
                    regexError = holder.search.regexError,
                    caseSensitive = holder.search.caseSensitive,
                    onToggleCase = {
                        holder.search.caseSensitive = !holder.search.caseSensitive
                        holder.search.update(searchQuery, parsed.firstSeq, plainLines); holder.bumpSearch()
                    },
                    useRegex = holder.search.useRegex,
                    onToggleRegex = {
                        holder.search.useRegex = !holder.search.useRegex
                        holder.search.update(searchQuery, parsed.firstSeq, plainLines); holder.bumpSearch()
                    },
                    onNext = nextMatch,
                    onPrev = prevMatch,
                    onClose = closeSearch,
                    focusRequester = searchFocus,
                    onFocusChanged = onSearchFocusChanged,
                    activationCounter = holder.searchOpenSignal,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = scrollbarStrip + 2.dp, top = 2.dp)
                )
            }
        }
    }
}
