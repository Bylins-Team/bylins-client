package com.bylins.client.ui.scroll

/** Совпадение поиска: строка по абсолютному номеру и полуинтервал [start, end) в её видимом тексте. */
data class SearchMatch(val seq: Long, val start: Int, val end: Int)

/**
 * Чистая логика поиска по тексту панели вывода (без Compose).
 *
 * Ищет по строкам и помнит, что в какой строке нашла: на обновление вывода
 * заново просматриваются только изменившиеся строки — те, что в снимке
 * оказались другим объектом. Иначе открытая строка поиска стоила бы прохода
 * по всей истории на каждый приход текста.
 *
 * Совпадения — в координатах (номер строки, столбцы видимого текста);
 * текущее переживает обновление: оно помнится как место, а не как индекс.
 */
class OutputSearch {
    var query: String = ""
        private set
    var caseSensitive: Boolean = false
    var useRegex: Boolean = false
    var regexError: Boolean = false
        private set

    private val _matches = mutableListOf<SearchMatch>()
    /** Все совпадения по порядку строк. */
    val matches: List<SearchMatch> get() = _matches

    var currentIndex: Int = -1
        private set

    val count: Int get() = _matches.size
    val current: SearchMatch? get() = _matches.getOrNull(currentIndex)
    val isActive: Boolean get() = query.isNotEmpty()

    // Что просматривали в прошлый раз, по строкам: строка та же (по ссылке) и
    // условия те же — её совпадения те же
    private var scannedFirstSeq = 0L
    private var scannedLines: Array<CharSequence?> = arrayOfNulls(0)
    private var scannedMatches: Array<IntArray?> = arrayOfNulls(0)
    private var scannedKey: Triple<String, Boolean, Boolean>? = null

    /**
     * Пересчитывает совпадения для [query] по строкам буфера.
     *
     * При смене запроса текущее совпадение — первое; при том же запросе
     * (изменился текст) — то же место, если оно ещё есть.
     */
    fun update(query: String, firstSeq: Long, lines: List<CharSequence>) {
        val queryChanged = query != this.query
        val previous = current
        this.query = query
        regexError = useRegex && query.isNotEmpty() && !isValidRegex(query)

        val key = Triple(query, caseSensitive, useRegex)
        val sameKey = key == scannedKey
        scannedKey = key
        val n = lines.size
        val shift = (firstSeq - scannedFirstSeq).toInt()
        val freshLines = arrayOfNulls<CharSequence>(n)
        val freshMatches = arrayOfNulls<IntArray>(n)
        _matches.clear()
        for (i in 0 until n) {
            val line = lines[i]
            val old = i + shift
            val reusable = sameKey && old in scannedLines.indices && scannedLines[old] === line
            val found = if (reusable) scannedMatches[old] else scanLine(line, query)
            freshLines[i] = line
            freshMatches[i] = found
            if (found != null) {
                var k = 0
                while (k < found.size) {
                    _matches.add(SearchMatch(firstSeq + i, found[k], found[k + 1]))
                    k += 2
                }
            }
        }
        scannedFirstSeq = firstSeq
        scannedLines = freshLines
        scannedMatches = freshMatches

        currentIndex = when {
            _matches.isEmpty() -> -1
            queryChanged || previous == null -> 0
            else -> indexOfPlace(previous)
        }
    }

    /** Совпадения строк с номерами от [fromSeq] до [toSeq] включительно. */
    fun matchesBetween(fromSeq: Long, toSeq: Long): List<SearchMatch> {
        if (_matches.isEmpty() || fromSeq > toSeq) return emptyList()
        val from = lowerBound(fromSeq)
        val to = lowerBound(toSeq + 1)
        return if (from < to) _matches.subList(from, to) else emptyList()
    }

    fun next() { if (count > 0) currentIndex = (currentIndex + 1).mod(count) }
    fun prev() { if (count > 0) currentIndex = (currentIndex - 1).mod(count) }

    fun clear() {
        query = ""
        _matches.clear()
        currentIndex = -1
        regexError = false
        scannedKey = null
        scannedLines = arrayOfNulls(0)
        scannedMatches = arrayOfNulls(0)
    }

    /** Совпадения строки парами (start, end); null — ни одного. */
    private fun scanLine(line: CharSequence, query: String): IntArray? {
        val found = findMatches(line, query, caseSensitive, useRegex)
        if (found.isEmpty()) return null
        val packed = IntArray(found.size * 2)
        for ((k, m) in found.withIndex()) {
            packed[k * 2] = m.first
            packed[k * 2 + 1] = m.second
        }
        return packed
    }

    /** Индекс прежнего текущего совпадения по месту; сдвинулось — ближайшее не раньше него. */
    private fun indexOfPlace(place: SearchMatch): Int {
        var low = 0
        var high = _matches.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val m = _matches[mid]
            val before = m.seq < place.seq || (m.seq == place.seq && m.start < place.start)
            if (before) low = mid + 1 else high = mid
        }
        return low.coerceIn(0, _matches.size - 1)
    }

    /** Первый индекс совпадения со строкой не раньше [seq]. */
    private fun lowerBound(seq: Long): Int {
        var low = 0
        var high = _matches.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (_matches[mid].seq < seq) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        private fun isValidRegex(pattern: String): Boolean =
            try { Regex(pattern); true } catch (e: Exception) { false }

        /** Все непересекающиеся совпадения [query] в [text]: пары (start, end). */
        fun findMatches(
            text: CharSequence,
            query: String,
            caseSensitive: Boolean,
            useRegex: Boolean
        ): List<Pair<Int, Int>> {
            if (query.isEmpty() || text.isEmpty()) return emptyList()
            return if (useRegex) {
                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                try {
                    Regex(query, opts).findAll(text)
                        .filter { it.value.isNotEmpty() }
                        .map { it.range.first to it.range.last + 1 }
                        .toList()
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                val result = mutableListOf<Pair<Int, Int>>()
                val hay = if (caseSensitive) text.toString() else text.toString().lowercase()
                val needle = if (caseSensitive) query else query.lowercase()
                var i = hay.indexOf(needle)
                while (i >= 0) {
                    result.add(i to i + needle.length)
                    i = hay.indexOf(needle, i + needle.length)
                }
                result
            }
        }
    }
}
