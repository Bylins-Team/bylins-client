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
import com.bylins.client.ui.scroll.BufferGeometry
import com.bylins.client.ui.scroll.ContentSnapshot
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
    snapshot: ContentSnapshot,
    holder: OutputViewHolder,
    splitFraction: Float,
    onSplitFractionChange: (Float) -> Unit,
    fontFamily: FontFamily,
    fontSize: Int,
    emptyPlaceholder: AnnotatedString,
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

    val limitedRaw = remember(snapshot.text) {
        if (snapshot.text.length > 100_000) lastLines(snapshot.text, 1000) else snapshot.text
    }
    val effectiveFirstSeq = remember(snapshot, limitedRaw) {
        snapshot.firstSeq + (snapshot.lineCount - ContentSnapshot.countLines(limitedRaw))
    }
    val annotated = remember(limitedRaw, emptyPlaceholder) {
        Perf.measure(Perf.Stage.UI_ANSI, limitedRaw.length.toLong()) {
            if (limitedRaw.isEmpty()) emptyPlaceholder else ansiParser.parse(limitedRaw)
        }
    }

    // Кадр с новыми данными нарисован: закрывает сквозной замер «байты пришли
    // -> игрок увидел». Ключ — снимок: эффект перезапускается на каждой порции
    LaunchedEffect(snapshot) {
        withFrameNanos { Perf.painted() }
    }
    val plainText = annotated.text
    val isEmpty = limitedRaw.isEmpty()
    val geometry = BufferGeometry(
        firstSeq = effectiveFirstSeq,
        lineCount = if (isEmpty) 0 else ContentSnapshot.countLines(limitedRaw)
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

            val layout = remember(annotated, widthPx, style) {
                Perf.measure(Perf.Stage.UI_MEASURE, annotated.length.toLong()) {
                measurer.measure(
                    text = annotated,
                    style = style,
                    softWrap = true,
                    constraints = Constraints(maxWidth = widthPx)
                )
                }
            }
            holder.lastLayout = layout
            val contentHeight = layout.size.height.toFloat()

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

            // Применяем целевую позицию скроллбэка при ЛЮБОМ изменении контента/вьюпорта.
            // Если следуем за низом — прижимаем к концу; иначе держим заякоренный символ
            // (seq,col) на той же высоте, пересчитывая его точный пиксель в новом layout.
            LaunchedEffect(snapshot, layout, fullViewportPx, splitFraction) {
                if (holder.isSelecting || holder.isScrolling) return@LaunchedEffect
                if (controller.followMode) {
                    holder.scrollbackScrollPx = maxScroll
                } else {
                    holder.scrollbackScrollPx =
                        (anchorToPx(layout, plainText, effectiveFirstSeq, holder.anchorSeq, holder.anchorCol)
                            + holder.anchorOffsetPx).coerceIn(0f, maxScroll)
                }
            }

            val scrollbackPx = holder.scrollbackScrollPx.coerceIn(0f, maxScroll)
            // Раздвоение (видимость разделителя) выводим прямо из позиции скролла:
            // скроллбэк не у самого низа ⇒ есть разрыв с живым хвостом.
            val split = !isEmpty && scrollbackPx < maxScroll - lineHeightPx

            // Путь подсветки вычисляется в фазе draw (через провайдер), чтобы
            // перерисовываться при каждом изменении выделения без рекомпозиции.
            val selectionPathProvider: () -> androidx.compose.ui.graphics.Path? = {
                if (isEmpty) null
                else selection.charRange(effectiveFirstSeq, plainText)
                    ?.let { r -> layout.getPathForRange(r.first, r.last + 1) }
            }
            val revisionState = holder.selectionRevisionState
            // Провайдер позиции скроллбэка (верхняя панель) — читается в фазе draw
            val scrollbackProvider: () -> Float = { holder.scrollbackScrollPx.coerceIn(0f, maxScroll) }

            // --- Поиск: подсветка совпадений (в фазе draw) ---
            val searchRevisionState = holder.searchRevisionState
            fun matchPath(m: com.bylins.client.ui.scroll.SearchMatch): androidx.compose.ui.graphics.Path =
                layout.getPathForRange(m.start.coerceIn(0, plainText.length), m.end.coerceIn(0, plainText.length))
            val searchAllProvider: () -> androidx.compose.ui.graphics.Path? = {
                val ms = holder.search.matches
                if (isEmpty || !holder.searchActive || ms.isEmpty()) null
                else androidx.compose.ui.graphics.Path().apply { ms.forEach { addPath(matchPath(it)) } }
            }
            val searchCurrentProvider: () -> androidx.compose.ui.graphics.Path? = {
                if (isEmpty || !holder.searchActive) null else holder.search.current?.let { matchPath(it) }
            }

            // --- Действия (пересоздаются каждую рекомпозицию, видят актуальные значения) ---
            val userScrollTo: (Float) -> Unit = { target ->
                val clamped = target.coerceIn(0f, maxScroll)
                // У самого низа защёлкиваем точно в конец (чтобы виды были непрерывны)
                val atBottom = clamped >= maxScroll - lineHeightPx
                holder.scrollbackScrollPx = if (atBottom) maxScroll else clamped
                val (aseq, acol) = pxToAnchor(layout, plainText, effectiveFirstSeq, clamped)
                holder.anchorSeq = aseq
                holder.anchorCol = acol
                holder.anchorOffsetPx = clamped - anchorToPx(layout, plainText, effectiveFirstSeq, aseq, acol)
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
                    val lineIdx = com.bylins.client.ui.scroll.BufferOffsets.lineIndexOfOffset(plainText, m.start)
                    val targetSeq = (effectiveFirstSeq + lineIdx - 2).coerceAtLeast(effectiveFirstSeq)
                    holder.anchorSeq = targetSeq
                    holder.anchorCol = 0
                    holder.anchorOffsetPx = 0f
                    controller.jumpToLine(targetSeq)
                    holder.scrollbackScrollPx =
                        anchorToPx(layout, plainText, effectiveFirstSeq, targetSeq, 0).coerceIn(0f, maxScroll)
                }
            }
            val onSearchQueryChange: (String) -> Unit = { q ->
                searchQuery = q
                holder.search.update(q, plainText)
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
            LaunchedEffect(plainText, searchQuery, holder.search.caseSensitive, holder.search.useRegex) {
                if (searchQuery.isNotEmpty()) { holder.search.update(searchQuery, plainText); holder.bumpSearch() }
            }
            // Указатель -> точка выделения (с учётом того, в какой панели курсор)
            val pointToSel: (Offset) -> com.bylins.client.ui.scroll.SelPoint = { pos ->
                val inBottom = split && pos.y >= topPaneHeightPx
                val contentY = if (inBottom) {
                    (pos.y - topPaneHeightPx) + bottomScrollPx
                } else {
                    pos.y + scrollbackPx
                }
                pointToSelPoint(layout, plainText, effectiveFirstSeq, pos.x, contentY)
            }
            val copySelection: () -> Unit = {
                if (!isEmpty) {
                    val text = selection.copyText(effectiveFirstSeq, plainText)
                    if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                }
            }
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
                    isCommand(event) && event.key == Key.A -> {
                        selection.selectAll(effectiveFirstSeq, geometry.lineCount); holder.bumpSelection(); true
                    }
                    isCommand(event) && event.key == Key.C -> { copySelection(); true }
                    event.isCtrlPressed && event.key == Key.Insert -> { copySelection(); true }
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
                            layout = layout,
                            scrollProvider = scrollbackProvider,
                            selectionColor = SELECTION_COLOR,
                            revisionState = revisionState,
                            searchRevisionState = searchRevisionState,
                            selectionPathProvider = selectionPathProvider,
                            searchAllProvider = searchAllProvider,
                            searchCurrentProvider = searchCurrentProvider,
                            modifier = Modifier.fillMaxWidth().weight(1f - splitFraction)
                        )
                        OutputCanvas(
                            layout = layout,
                            scrollProvider = { bottomScrollPx },
                            selectionColor = SELECTION_COLOR,
                            revisionState = revisionState,
                            searchRevisionState = searchRevisionState,
                            selectionPathProvider = selectionPathProvider,
                            searchAllProvider = searchAllProvider,
                            searchCurrentProvider = searchCurrentProvider,
                            modifier = Modifier.fillMaxWidth().weight(splitFraction)
                        )
                    }
                } else {
                    // Одно окно: скроллбэк внизу непрерывен с хвостом — показываем как единый вид.
                    OutputCanvas(
                        layout = layout,
                        scrollProvider = scrollbackProvider,
                        selectionColor = SELECTION_COLOR,
                        revisionState = revisionState,
                        searchRevisionState = searchRevisionState,
                        selectionPathProvider = selectionPathProvider,
                        searchAllProvider = searchAllProvider,
                        searchCurrentProvider = searchCurrentProvider,
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
                        holder.search.update(searchQuery, plainText); holder.bumpSearch()
                    },
                    useRegex = holder.search.useRegex,
                    onToggleRegex = {
                        holder.search.useRegex = !holder.search.useRegex
                        holder.search.update(searchQuery, plainText); holder.bumpSearch()
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
