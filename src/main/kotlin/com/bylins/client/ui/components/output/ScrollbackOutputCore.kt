package com.bylins.client.ui.components.output

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import com.bylins.client.ui.scroll.MeasuredWindow
import com.bylins.client.ui.scroll.SelPoint

/**
 * Портируемое ядро панели вывода: маппинг между абсолютным seq строки и
 * пиксельной позицией скролла и отрисовка.
 *
 * Разметка — по строкам (см. [MeasuredWindow]): у каждой логической строки
 * свой TextLayoutResult, а её место по вертикали — сумма высот строк выше.
 * Поэтому любой перевод «пиксель ↔ символ» — это два шага: найти строку по
 * вертикали или номеру, затем спросить её собственную разметку.
 * Использует только multiplatform-API Compose (без desktop-специфики).
 */

internal typealias TextWindow = MeasuredWindow<TextLayoutResult>

/** Максимальный сдвиг скролла для заданной высоты контента и вьюпорта. */
internal fun maxScrollOf(contentHeightPx: Float, viewportPx: Float): Float =
    (contentHeightPx - viewportPx).coerceAtLeast(0f)

/**
 * Разбивает размеченное окно на логические строки, сохраняя раскраску.
 *
 * Разбор ANSI идёт по всему окну сразу — цвет переносится между строками
 * (камень №12), — а режется уже результат. Строк ровно столько, сколько
 * даёт countLines: текст с завершающим переводом строки заканчивается
 * пустой строкой.
 *
 * Не через `subSequence`: тот на каждый срез перебирает ВСЕ отрезки окна,
 * и на ста тысячах строк с четырьмя сотнями тысяч отрезков UI-поток ушёл
 * в это на девять минут (камень №14). Здесь отрезки, отсортированные по
 * началу, проходятся один раз вместе со строками: отрезок посещается
 * столько раз, сколько строк он задевает.
 */
internal fun splitLines(annotated: AnnotatedString): List<AnnotatedString> {
    val text = annotated.text
    if (text.isEmpty()) return emptyList()
    val spans = annotated.spanStyles.sortedBy { it.start }
    val lines = ArrayList<AnnotatedString>()
    var spanIndex = 0
    var start = 0
    while (true) {
        val newline = text.indexOf('\n', start)
        val end = if (newline == -1) text.length else newline
        // Отрезки, закончившиеся до этой строки, больше не понадобятся
        while (spanIndex < spans.size && spans[spanIndex].end <= start) spanIndex++
        lines.add(
            buildAnnotatedString {
                append(text, start, end)
                var j = spanIndex
                while (j < spans.size && spans[j].start < end) {
                    val span = spans[j]
                    val from = maxOf(span.start, start) - start
                    val to = minOf(span.end, end) - start
                    if (to > from) addStyle(span.item, from, to)
                    j++
                }
            }
        )
        if (newline == -1) return lines
        start = newline + 1
    }
}

/** Последние [maxLines] строк текста (окно разбора для больших буферов). */
internal fun lastLines(text: String, maxLines: Int): String {
    if (text.isEmpty()) return text
    var lineCount = 0
    var position = text.length - 1
    while (position >= 0 && lineCount < maxLines) {
        if (text[position] == '\n') lineCount++
        position--
    }
    if (position < 0) return text
    return text.substring(position + 1)
}

/** Смещение начала каждой логической строки в plain-тексте окна. */
internal fun lineStarts(plainText: String): IntArray {
    if (plainText.isEmpty()) return IntArray(0)
    var count = 1
    for (ch in plainText) if (ch == '\n') count++
    val starts = IntArray(count)
    var line = 1
    for (i in plainText.indices) {
        if (plainText[i] == '\n') starts[line++] = i + 1
    }
    return starts
}

/** Индекс логической строки, содержащей смещение [offset] (последнее начало <= offset). */
internal fun lineIndexOf(lineStarts: IntArray, offset: Int): Int {
    if (lineStarts.isEmpty()) return 0
    var low = 0
    var high = lineStarts.size - 1
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (lineStarts[mid] <= offset) low = mid else high = mid - 1
    }
    return low
}

/** Точный пиксель верха визуальной строки, содержащей символ (seq, col). Без «защёлкивания»
 *  к началу логической строки — поэтому нет дрожи на переносах. */
internal fun TextWindow.anchorToPx(seq: Long, col: Int): Float {
    if (isEmpty) return 0f
    val line = lines[indexOfSeq(seq)]
    // Вытесненная строка подтягивается к началу окна, как и в модели выделения
    val offset = if (seq < firstSeq) 0 else col.coerceIn(0, line.length)
    val visualLine = line.layout.getLineForOffset(offset)
    return line.top + line.layout.getLineTop(visualLine)
}

/** Якорь (seq, col) символа в левом-верхнем углу вьюпорта при сдвиге [scrollPx]. */
internal fun TextWindow.pxToAnchor(scrollPx: Float): Pair<Long, Int> {
    if (isEmpty) return firstSeq to 0
    val index = indexAt(scrollPx)
    val line = lines[index]
    val localY = (scrollPx - line.top).coerceAtLeast(0f)
    val offset = line.layout.getOffsetForPosition(Offset(0f, localY)).coerceIn(0, line.length)
    return (firstSeq + index) to offset
}

/** Точка выделения (seq, col) для позиции указателя в координатах контента. */
internal fun TextWindow.pointToSelPoint(contentX: Float, contentY: Float): SelPoint {
    if (isEmpty) return SelPoint(firstSeq, 0)
    val index = indexAt(contentY)
    val line = lines[index]
    val localY = (contentY - line.top).coerceAtLeast(0f)
    val offset = line.layout.getOffsetForPosition(Offset(contentX, localY)).coerceIn(0, line.length)
    return SelPoint(firstSeq + index, offset)
}

/**
 * Путь подсветки диапазона (seq, col) → (seq, col) — по строкам, каждая
 * своей разметкой на своей высоте. Столбцы зажимаются по длине строки, номера
 * — по окну: выделение может начинаться в вытесненной строке.
 *
 * @return null, если подсвечивать нечего
 */
internal fun TextWindow.pathForRange(startSeq: Long, startCol: Int, endSeq: Long, endCol: Int): Path? {
    if (isEmpty) return null
    val from = maxOf(startSeq, firstSeq)
    val to = minOf(endSeq, lastSeq)
    if (from > to) return null
    var path: Path? = null
    for (seq in from..to) {
        val line = lines[(seq - firstSeq).toInt()]
        val s = if (seq == startSeq) startCol.coerceIn(0, line.length) else 0
        val e = if (seq == endSeq) endCol.coerceIn(0, line.length) else line.length
        if (e <= s) continue
        val segment = line.layout.getPathForRange(s, e)
        segment.translate(Offset(0f, line.top))
        (path ?: Path().also { path = it }).addPath(segment)
    }
    return path
}

/**
 * Путь подсветки для диапазона смещений в plain-тексте окна (совпадение поиска).
 * Полуинтервал [startOffset, endOffset) может пересекать границу строк.
 */
internal fun TextWindow.pathForOffsets(lineStarts: IntArray, startOffset: Int, endOffset: Int): Path? {
    if (isEmpty || endOffset <= startOffset || lineStarts.isEmpty()) return null
    val first = lineIndexOf(lineStarts, startOffset)
    val last = lineIndexOf(lineStarts, endOffset - 1)
    var path: Path? = null
    for (index in first..minOf(last, lines.size - 1)) {
        val line = lines[index]
        val s = (startOffset - lineStarts[index]).coerceIn(0, line.length)
        val e = (endOffset - lineStarts[index]).coerceIn(0, line.length)
        if (e <= s) continue
        val segment = line.layout.getPathForRange(s, e)
        segment.translate(Offset(0f, line.top))
        (path ?: Path().also { path = it }).addPath(segment)
    }
    return path
}

internal val SEARCH_ALL_COLOR = Color(0x66E6B800)     // все совпадения — приглушённый жёлтый
internal val SEARCH_CURRENT_COLOR = Color(0xCCFF8C00)  // текущее — яркий оранжевый

/**
 * Рисует одну панель-вьюпорт над окном строк: подсветки и текст, сдвинутые
 * на [scrollPx] и обрезанные границами панели. Рисуются только строки,
 * попавшие во вьюпорт, — остальные не стоят ничего.
 */
@Composable
internal fun OutputCanvas(
    window: TextWindow,
    scrollProvider: () -> Float,
    selectionColor: Color,
    revisionState: State<Int>,
    searchRevisionState: State<Int>,
    selectionPathProvider: () -> Path?,
    searchAllProvider: () -> Path?,
    searchCurrentProvider: () -> Path?,
    modifier: Modifier
) {
    Canvas(modifier) {
        // Скролл, ревизии выделения и поиска читаем в фазе draw — Canvas перерисуется
        // при их изменении даже без рекомпозиции (надёжно на любой вкладке).
        revisionState.value
        searchRevisionState.value
        val scrollPx = scrollProvider()
        val selectionPath = selectionPathProvider()
        val searchAll = searchAllProvider()
        val searchCurrent = searchCurrentProvider()
        clipRect {
            translate(top = -scrollPx) {
                if (searchAll != null) drawPath(searchAll, color = SEARCH_ALL_COLOR)
                if (searchCurrent != null) drawPath(searchCurrent, color = SEARCH_CURRENT_COLOR)
                if (selectionPath != null) drawPath(selectionPath, color = selectionColor)
                if (!window.isEmpty) {
                    val from = window.indexAt(scrollPx)
                    val to = window.indexAt(scrollPx + size.height)
                    for (index in from..to) {
                        val line = window.lines[index]
                        drawText(line.layout, topLeft = Offset(0f, line.top))
                    }
                }
            }
        }
    }
}

/**
 * Единый интерактивный скроллбар на всю высоту компонента, управляющий
 * позицией скроллбэка (общий и в одно-, и в двухпанельном режиме).
 *
 * @param scrollPx текущий сдвиг скроллбэка
 * @param maxScroll максимальный сдвиг скроллбэка (для активного вьюпорта)
 * @param viewportPx высота окна скроллбэка (для размера ползунка)
 * @param contentHeightPx полная высота контента
 * @param onScrollTo установить сдвиг (как пользовательский скролл)
 */
@Composable
internal fun OutputScrollbar(
    scrollProvider: () -> Float,
    maxScroll: Float,
    viewportPx: Float,
    contentHeightPx: Float,
    onScrollTo: (Float) -> Unit,
    onActive: (Boolean) -> Unit,
    modifier: Modifier
) {
    if (contentHeightPx <= viewportPx + 1f) return

    val maxRef = rememberUpdatedState(maxScroll)
    val viewportRef = rememberUpdatedState(viewportPx)
    val contentRef = rememberUpdatedState(contentHeightPx)
    // ВАЖНО: колбэк тоже через rememberUpdatedState — иначе долгоживущий жест
    // pointerInput(Unit) захватит устаревший onScrollTo (со старым maxScroll) → скачки.
    val onScrollToRef = rememberUpdatedState(onScrollTo)
    val scrollProviderRef = rememberUpdatedState(scrollProvider)
    val onActiveRef = rememberUpdatedState(onActive)

    Canvas(
        modifier.pointerInput(Unit) {
            // Тащим ползунок по АБСОЛЮТНОЙ позиции указателя (с учётом точки захвата) —
            // ползунок точно следует за мышью, без накопления дельт и скачков.
            awaitEachGesture {
                val down = awaitFirstDown()
                // Геометрию ползунка фиксируем на время жеста СОЗНАТЕЛЬНО: если
                // пересчитывать её при росте лога, ползунок будет уползать
                // из-под курсора. Позиция и предел при этом берутся свежие.
                val track = size.height.toFloat()
                val thumb = (track * viewportRef.value / contentRef.value).coerceIn(30f, track)
                val denom = (track - thumb).coerceAtLeast(1f)
                val curThumbY = if (maxRef.value > 0f) (scrollProviderRef.value() / maxRef.value) * denom else 0f
                // Смещение точки захвата внутри ползунка (если кликнули мимо — по центру)
                val grab = (down.position.y - curThumbY).let { if (it in 0f..thumb) it else thumb / 2f }
                down.consume()
                onActiveRef.value(true)
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val targetThumbY = (ch.position.y - grab).coerceIn(0f, denom)
                        onScrollToRef.value(targetThumbY / denom * maxRef.value)
                        ch.consume()
                    }
                } finally {
                    onActiveRef.value(false)
                }
            }
        }
    ) {
        val track = size.height
        val thumb = (track * viewportPx / contentHeightPx).coerceIn(30f, track)
        val scrollPx = scrollProvider() // читаем в фазе draw — ползунок двигается без рекомпозиции
        val thumbY = if (maxScroll > 0f) (scrollPx / maxScroll) * (track - thumb) else 0f
        val width = 8f
        drawRoundRect(
            color = Color(0x55FFFFFF),
            topLeft = Offset(size.width - width - 2f, thumbY.coerceIn(0f, track - thumb)),
            size = Size(width, thumb),
            cornerRadius = CornerRadius(width / 2f, width / 2f)
        )
    }
}
