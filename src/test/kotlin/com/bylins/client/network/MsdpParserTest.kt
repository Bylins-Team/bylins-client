package com.bylins.client.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsdpParserTest {

    private val parser = MsdpParser()

    @Test
    fun `parse simple variable`() {
        // MSDP_VAR + "LEVEL" + MSDP_VAL + "50"
        val data = byteArrayOf(
            MsdpParser.MSDP_VAR
        ) + "LEVEL".toByteArray() + byteArrayOf(
            MsdpParser.MSDP_VAL
        ) + "50".toByteArray()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("50", result["LEVEL"])
    }

    @Test
    fun `parse multiple variables`() {
        // MSDP_VAR + "LEVEL" + MSDP_VAL + "50" + MSDP_VAR + "GOLD" + MSDP_VAL + "1000"
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "LEVEL".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "50".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAR) + "GOLD".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "1000".toByteArray()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("50", result["LEVEL"])
        assertEquals("1000", result["GOLD"])
    }

    @Test
    fun `parse array value`() {
        // MSDP_VAR + "COMMANDS" + MSDP_VAL + MSDP_ARRAY_OPEN + MSDP_VAL + "LIST" + MSDP_VAL + "GET" + MSDP_ARRAY_CLOSE
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "COMMANDS".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_ARRAY_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAL) + "LIST".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "GET".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_ARRAY_CLOSE)

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertTrue(result["COMMANDS"] is List<*>)
        @Suppress("UNCHECKED_CAST")
        val commands = result["COMMANDS"] as List<String>
        assertTrue(commands.contains("LIST"))
        assertTrue(commands.contains("GET"))
    }

    @Test
    fun `parse table value`() {
        // MSDP_VAR + "STATE" + MSDP_VAL + MSDP_TABLE_OPEN + MSDP_VAR + "CURRENT_HP" + MSDP_VAL + "100" + MSDP_TABLE_CLOSE
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "STATE".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "CURRENT_HP".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "100".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAR) + "CURRENT_MOVE".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "50".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE)

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertTrue(result["STATE"] is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val state = result["STATE"] as Map<String, Any>
        assertEquals("100", state["CURRENT_HP"])
        assertEquals("50", state["CURRENT_MOVE"])
    }

    @Test
    fun `parse REPORTABLE_VARIABLES array`() {
        // Simulate real MSDP data for REPORTABLE_VARIABLES
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "REPORTABLE_VARIABLES".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_ARRAY_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAL) + "LEVEL".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "STATE".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "ROOM".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "GOLD".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_ARRAY_CLOSE)

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertTrue(result["REPORTABLE_VARIABLES"] is List<*>)
        @Suppress("UNCHECKED_CAST")
        val vars = result["REPORTABLE_VARIABLES"] as List<String>
        assertEquals(4, vars.size)
        assertTrue(vars.contains("LEVEL"))
        assertTrue(vars.contains("STATE"))
        assertTrue(vars.contains("ROOM"))
        assertTrue(vars.contains("GOLD"))
    }

    @Test
    fun `parse ROOM with nested exits table`() {
        // ROOM with EXITS table containing direction-to-vnum mappings
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "ROOM".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "VNUM".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "5000".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAR) + "NAME".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "Test Room".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAR) + "EXITS".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "n".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "5001".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAR) + "s".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "4999".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE) +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE)

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertTrue(result["ROOM"] is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val room = result["ROOM"] as Map<String, Any>
        assertEquals("5000", room["VNUM"])
        assertEquals("Test Room", room["NAME"])

        assertTrue(room["EXITS"] is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val exits = room["EXITS"] as Map<String, Any>
        assertEquals("5001", exits["n"])
        assertEquals("4999", exits["s"])
    }

    @Test
    fun `parse cyrillic text`() {
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "NAME".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "Тестовая комната".toByteArray(Charsets.UTF_8)

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("Тестовая комната", result["NAME"])
    }

    @Test
    fun `parse GOLD table`() {
        // GOLD = {POCKET=100, BANK=5000}
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "GOLD".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "POCKET".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "100".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAR) + "BANK".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "5000".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE)

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertTrue(result["GOLD"] is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val gold = result["GOLD"] as Map<String, Any>
        assertEquals("100", gold["POCKET"])
        assertEquals("5000", gold["BANK"])
    }

    @Test
    fun `parse empty data returns empty map`() {
        val result = parser.parse(byteArrayOf())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse ROOM with cyrillic name under UTF-8`() {
        // Позиция в буфере считается в байтах: русская буква занимает два, и счёт по длине
        // декодированной строки уводил бы разбор в середину символа.
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "ROOM".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "VNUM".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "27001".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAR) + "NAME".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "Площадь".toByteArray(Charsets.UTF_8) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "AREA".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "Суздаль".toByteArray(Charsets.UTF_8) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "EXITS".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "n".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "27002".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE) +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE)

        val result = parser.parse(data)

        @Suppress("UNCHECKED_CAST")
        val room = result["ROOM"] as Map<String, Any>
        assertEquals("27001", room["VNUM"])
        assertEquals("Площадь", room["NAME"])
        assertEquals("Суздаль", room["AREA"])
        @Suppress("UNCHECKED_CAST")
        val exits = room["EXITS"] as Map<String, Any>
        assertEquals("27002", exits["n"])
    }

    @Test
    fun `parse GROUP as array of tables`() {
        // GROUP сервер шлёт массивом таблиц. Раньше разбор массива умел только строки и
        // на TABLE_OPEN зацикливался: позиция не двигалась, выход из цикла не срабатывал.
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "GROUP".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_ARRAY_OPEN) +
                byteArrayOf(MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "NAME".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "Мородей".toByteArray(Charsets.UTF_8) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "IS_LEADER".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "1".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE) +
                byteArrayOf(MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "NAME".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "Ванар".toByteArray(Charsets.UTF_8) +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE) +
                byteArrayOf(MsdpParser.MSDP_ARRAY_CLOSE)

        val result = parser.parse(data)

        @Suppress("UNCHECKED_CAST")
        val group = result["GROUP"] as List<Any>
        assertEquals(2, group.size)
        @Suppress("UNCHECKED_CAST")
        val first = group[0] as Map<String, Any>
        assertEquals("Мородей", first["NAME"])
        assertEquals("1", first["IS_LEADER"])
        @Suppress("UNCHECKED_CAST")
        val second = group[1] as Map<String, Any>
        assertEquals("Ванар", second["NAME"])
    }

    @Test
    fun `parse uses the session encoding`() {
        // Сервер отдаёт MSDP в кодировке сессии, а не всегда в UTF-8.
        val koi8 = MsdpParser("koi8-r")
        val data = byteArrayOf(MsdpParser.MSDP_VAR) + "ROOM".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL, MsdpParser.MSDP_TABLE_OPEN) +
                byteArrayOf(MsdpParser.MSDP_VAR) + "NAME".toByteArray() +
                byteArrayOf(MsdpParser.MSDP_VAL) + "Площадь".toByteArray(charset("koi8-r")) +
                byteArrayOf(MsdpParser.MSDP_TABLE_CLOSE)

        @Suppress("UNCHECKED_CAST")
        val room = koi8.parse(data)["ROOM"] as Map<String, Any>
        assertEquals("Площадь", room["NAME"])
    }
}
