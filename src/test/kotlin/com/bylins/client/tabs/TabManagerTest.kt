package com.bylins.client.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TabManagerTest {

    @Test
    fun `main tab exists by default`() {
        val manager = TabManager()
        val mainTab = manager.getTab("main")
        assertNotNull(mainTab)
        assertEquals("Главная", mainTab.name)
    }

    @Test
    fun `active tab is main by default`() {
        val manager = TabManager()
        assertEquals("main", manager.activeTabId.value)
    }

    @Test
    fun `addTab adds new tab`() {
        val manager = TabManager()
        val tab = Tab(id = "test", name = "Test Tab")
        manager.addTab(tab)

        val retrieved = manager.getTab("test")
        assertNotNull(retrieved)
        assertEquals("Test Tab", retrieved.name)
    }

    @Test
    fun `addTab does not duplicate existing tab`() {
        val manager = TabManager()
        val tab1 = Tab(id = "test", name = "Test 1")
        val tab2 = Tab(id = "test", name = "Test 2")

        manager.addTab(tab1)
        manager.addTab(tab2)

        val retrieved = manager.getTab("test")
        assertEquals("Test 1", retrieved?.name)
    }

    @Test
    fun `removeTab removes tab`() {
        val manager = TabManager()
        val tab = Tab(id = "test", name = "Test")
        manager.addTab(tab)
        manager.removeTab("test")

        val retrieved = manager.getTab("test")
        assertEquals(null, retrieved)
    }

    @Test
    fun `removeTab cannot remove main tab`() {
        val manager = TabManager()
        manager.removeTab("main")

        val mainTab = manager.getTab("main")
        assertNotNull(mainTab)
    }

    @Test
    fun `setActiveTab changes active tab`() {
        val manager = TabManager()
        val tab = Tab(id = "test", name = "Test")
        manager.addTab(tab)
        manager.setActiveTab("test")

        assertEquals("test", manager.activeTabId.value)
    }

    @Test
    fun `setActiveTab reverts to main if active tab is removed`() {
        val manager = TabManager()
        val tab = Tab(id = "test", name = "Test")
        manager.addTab(tab)
        manager.setActiveTab("test")
        manager.removeTab("test")

        assertEquals("main", manager.activeTabId.value)
    }

    @Test
    fun `processText adds text to main tab`() {
        val manager = TabManager()
        manager.processText("Hello world")

        val mainTab = manager.getTab("main")
        assertTrue(mainTab!!.snapshot.value.text().contains("Hello world"))
    }

    @Test
    fun `processText filters text to matching tabs`() {
        val manager = TabManager()
        val chatTab = Tab(
            id = "chat",
            name = "Chat",
            filters = listOf(TabFilter("говорит вам".toRegex()))
        )
        manager.addTab(chatTab)

        manager.processText("Вася говорит вам: привет")

        val chat = manager.getTab("chat")
        assertTrue(chat!!.snapshot.value.text().contains("говорит вам"))
    }

    @Test
    fun `processText with MOVE mode removes from main`() {
        val manager = TabManager()
        val chatTab = Tab(
            id = "chat",
            name = "Chat",
            filters = listOf(TabFilter("секретно".toRegex())),
            captureMode = CaptureMode.MOVE
        )
        manager.addTab(chatTab)

        manager.processText("секретно: важное сообщение")

        val mainTab = manager.getTab("main")
        val chatTabResult = manager.getTab("chat")

        assertFalse(mainTab!!.snapshot.value.text().contains("секретно"))
        assertTrue(chatTabResult!!.snapshot.value.text().contains("секретно"))
    }

    @Test
    fun `processText with COPY mode keeps in main`() {
        val manager = TabManager()
        val chatTab = Tab(
            id = "chat",
            name = "Chat",
            filters = listOf(TabFilter("копия".toRegex())),
            captureMode = CaptureMode.COPY
        )
        manager.addTab(chatTab)

        manager.processText("копия: сообщение")

        val mainTab = manager.getTab("main")
        val chatTabResult = manager.getTab("chat")

        assertTrue(mainTab!!.snapshot.value.text().contains("копия"))
        assertTrue(chatTabResult!!.snapshot.value.text().contains("копия"))
    }

    @Test
    fun `addToMainTab adds text directly to main`() {
        val manager = TabManager()
        manager.addToMainTab("Direct text")

        val mainTab = manager.getTab("main")
        assertTrue(mainTab!!.snapshot.value.text().contains("Direct text"))
    }

    @Test
    fun `clearTab clears tab content`() {
        val manager = TabManager()
        manager.processText("Some content")
        manager.clearTab("main")

        val mainTab = manager.getTab("main")
        assertEquals("", mainTab!!.snapshot.value.text())
    }

    @Test
    fun `clearAll clears all tabs`() {
        val manager = TabManager()
        val tab = Tab(id = "test", name = "Test")
        manager.addTab(tab)

        manager.processText("Content for main")
        manager.getTab("test")?.appendText("Content for test")

        manager.clearAll()

        assertEquals("", manager.getTab("main")!!.snapshot.value.text())
        assertEquals("", manager.getTab("test")!!.snapshot.value.text())
    }

    @Test
    fun `getTabsForSave returns all tabs including main and logs`() {
        val manager = TabManager()
        val tab = Tab(id = "test", name = "Test")
        manager.addTab(tab)

        val tabsToSave = manager.getTabsForSave()

        // TabManager создаёт main и logs, плюс мы добавили test
        assertEquals(3, tabsToSave.size)
        assertTrue(tabsToSave.any { it.id == "main" })
        assertTrue(tabsToSave.any { it.id == "logs" })
        assertTrue(tabsToSave.any { it.id == "test" })
    }

    @Test
    fun `getTabsForSave excludes plugin tabs`() {
        val manager = TabManager()
        val pluginTab = Tab(id = "plugin_test", name = "Plugin Tab", isPluginTab = true)
        manager.addTab(pluginTab)

        val tabsToSave = manager.getTabsForSave()

        // Plugin tabs should be excluded
        assertFalse(tabsToSave.any { it.id == "plugin_test" })
        // But main and logs should still be there
        assertTrue(tabsToSave.any { it.id == "main" })
        assertTrue(tabsToSave.any { it.id == "logs" })
    }
}
