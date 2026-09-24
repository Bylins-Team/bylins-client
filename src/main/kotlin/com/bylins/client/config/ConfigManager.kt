package com.bylins.client.config

import mu.KotlinLogging
import com.bylins.client.aliases.Alias
import com.bylins.client.contextcommands.ContextCommandRule
import com.bylins.client.hotkeys.Hotkey
import com.bylins.client.tabs.Tab
import com.bylins.client.tabs.TabDto
import com.bylins.client.triggers.Trigger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private val logger = KotlinLogging.logger("ConfigManager")

/** Сколько прошлых версий конфига хранить, если игрок не задал своё число. */
const val DEFAULT_CONFIG_BACKUPS = 3

/** Верхняя граница: копии дешёвые, но плодить их без счёта незачем. */
const val MAX_CONFIG_BACKUPS = 20

/**
 * Сколько строк вывода держать в памяти.
 *
 * Столько же доступно прокруткой, поиском и выделением. Всё, что делается на
 * приход текста, стоит по изменению, а не по истории, так что глубина
 * платится только памятью: около килобайта на строку.
 */
const val DEFAULT_OUTPUT_BUFFER_LINES = 100_000
const val MIN_OUTPUT_BUFFER_LINES = 1_000
const val MAX_OUTPUT_BUFFER_LINES = 1_000_000

/**
 * Сколько команд держать в истории ввода (стрелки и подстановка по Tab).
 * Столько же уходит в ~/.bylins-client/history.txt.
 */
const val DEFAULT_COMMAND_HISTORY_SIZE = 500
const val MIN_COMMAND_HISTORY_SIZE = 10
const val MAX_COMMAND_HISTORY_SIZE = 10_000
class ConfigManager {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val configDir = Paths.get(System.getProperty("user.home"), ".bylins-client")
    private val configFile = configDir.resolve("config.json")

    /**
     * Читали ли конфиг в этом запуске.
     *
     * Пока не читали, сохранять нечего: состояние клиента ещё пустое, и запись
     * означала бы затирание файла. Ровно так и терялись настройки — клиент
     * падал при старте, а shutdown hook честно сохранял пустоту поверх.
     */
    private var loaded = false

    init {
        // Создаём директорию конфига если её нет
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir)
        }
    }

    /**
     * Отодвигает прежний конфиг в config.json.1, сдвигая старые копии дальше по
     * кругу; самая старая вытесняется.
     *
     * Дёшево и спасает от любой ошибки, приводящей к записи неполного состояния:
     * файл со списком триггеров, копившимся месяцами, не должен зависеть от
     * единственной удачной записи.
     *
     * @param keep сколько копий хранить; 0 отключает
     */
    private fun rotateBackups(keep: Int) {
        if (keep <= 0 || !Files.exists(configFile)) return
        try {
            // Лишние копии убираем сразу: после уменьшения глубины они иначе
            // остались бы лежать навсегда
            var extra = keep + 1
            while (Files.deleteIfExists(configDir.resolve("config.json.$extra"))) extra++

            for (index in keep downTo 2) {
                val older = configDir.resolve("config.json.$index")
                val newer = configDir.resolve("config.json.${index - 1}")
                if (Files.exists(newer)) Files.move(newer, older, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            Files.copy(
                configFile,
                configDir.resolve("config.json.1"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            logger.warn { "Не удалось сохранить резервную копию конфига: ${e.message}" }
        }
    }

    /**
     * Сохраняет триггеры, алиасы, хоткеи, переменные, вкладки и настройки в файл
     */
    fun saveConfig(
        triggers: List<Trigger>,
        aliases: List<Alias>,
        hotkeys: List<Hotkey>,
        variables: Map<String, String>,
        tabs: List<Tab>,
        contextCommandRules: List<ContextCommandRule> = emptyList(),
        contextCommandMaxQueueSize: Int = 50,
        encoding: String = "UTF-8",
        miniMapWidth: Int = 250,
        miniMapHeight: Int = 300,
        zonePanelWidth: Int = 220,
        theme: String = "DARK",
        fontFamily: String = "MONOSPACE",
        allowSystemFonts: Boolean = false,
        fontSize: Int = 14,
        connectionProfiles: List<com.bylins.client.connection.ConnectionProfile> = emptyList(),
        currentProfileId: String? = null,
        ignoreNumLock: Boolean = false,
        activeProfileStack: List<String> = emptyList(),
        hiddenTabs: Set<String> = emptySet(),
        lastMapRoomId: String? = null,
        logWithColors: Boolean = false,
        statusGroupCollapsed: Map<String, Boolean> = emptyMap(),
        outputSplitFractions: Map<String, Float> = emptyMap(),
        sidePanelCollapsed: Boolean = false,
        pluginPermissions: Map<String, Set<String>> = emptyMap(),
        configBackups: Int = DEFAULT_CONFIG_BACKUPS,
        outputBufferLines: Int = DEFAULT_OUTPUT_BUFFER_LINES,
        commandHistorySize: Int = DEFAULT_COMMAND_HISTORY_SIZE
    ) {
        try {
            val config = ClientConfig(
                triggers = triggers.map { TriggerDto.fromTrigger(it) },
                aliases = aliases.map { AliasDto.fromAlias(it) },
                hotkeys = hotkeys.map { HotkeyDto.fromHotkey(it) },
                variables = variables,
                tabs = tabs.map { TabDto.fromTab(it) },
                contextCommandRules = contextCommandRules.map { ContextCommandRuleDto.fromRule(it) },
                contextCommandMaxQueueSize = contextCommandMaxQueueSize,
                encoding = encoding,
                miniMapWidth = miniMapWidth,
                miniMapHeight = miniMapHeight,
                zonePanelWidth = zonePanelWidth,
                theme = theme,
                fontFamily = fontFamily,
                allowSystemFonts = allowSystemFonts,
                fontSize = fontSize,
                connectionProfiles = connectionProfiles.map { ConnectionProfileDto.fromConnectionProfile(it) },
                currentProfileId = currentProfileId,
                ignoreNumLock = ignoreNumLock,
                activeProfileStack = activeProfileStack,
                hiddenTabs = hiddenTabs,
                lastMapRoomId = lastMapRoomId,
                logWithColors = logWithColors,
                statusGroupCollapsed = statusGroupCollapsed,
                outputSplitFractions = outputSplitFractions,
                sidePanelCollapsed = sidePanelCollapsed,
                pluginPermissions = pluginPermissions,
                configBackups = configBackups,
                outputBufferLines = outputBufferLines,
                commandHistorySize = commandHistorySize
            )

            // Конфиг не читали — значит и состояния ещё нет, писать нечего
            if (!loaded) {
                logger.warn { "Конфиг не сохранён: он ещё не был загружен в этом запуске" }
                return
            }

            val jsonString = json.encodeToString(config)
            rotateBackups(configBackups.coerceIn(0, MAX_CONFIG_BACKUPS))
            // Пишем во временный файл и подменяем: оборванная запись не должна
            // оставлять обрезанный конфиг вместо целого
            val temp = configDir.resolve("config.json.tmp")
            Files.writeString(temp, jsonString)
            Files.move(
                temp,
                configFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )

            logger.info { "Config saved to: $configFile" }
        } catch (e: Exception) {
            logger.error { "Failed to save config: ${e.message}" }
            e.printStackTrace()
        }
    }

    /**
     * Загружает триггеры, алиасы, хоткеи, переменные, вкладки и настройки из файла
     */
    fun loadConfig(): ConfigData {
        try {
            if (!Files.exists(configFile)) {
                logger.info { "Config file not found: $configFile" }
                // Первый запуск: сохранять можно, затирать нечего
                loaded = true
                return ConfigData(
                    triggers = emptyList(),
                    aliases = emptyList(),
                    hotkeys = emptyList(),
                    variables = emptyMap(),
                    tabs = emptyList(),
                    connectionProfiles = com.bylins.client.connection.ConnectionProfile.createDefaultProfiles()
                )
            }

            val jsonString = Files.readString(configFile)
            val config = json.decodeFromString<ClientConfig>(jsonString)

            val triggers = config.triggers.map { it.toTrigger() }
            val aliases = config.aliases.map { it.toAlias() }
            val hotkeys = config.hotkeys.mapNotNull { it.toHotkey() }
            val variables = config.variables
            val tabs = config.tabs.map { it.toTab() }
            val encoding = config.encoding
            val miniMapWidth = config.miniMapWidth
            val miniMapHeight = config.miniMapHeight
            val zonePanelWidth = config.zonePanelWidth
            val theme = config.theme
            val fontFamily = config.fontFamily
            val allowSystemFonts = config.allowSystemFonts
            val fontSize = config.fontSize
            val connectionProfiles = config.connectionProfiles.map { it.toConnectionProfile() }.ifEmpty {
                com.bylins.client.connection.ConnectionProfile.createDefaultProfiles()
            }
            val currentProfileId = config.currentProfileId
            val ignoreNumLock = config.ignoreNumLock
            val activeProfileStack = config.activeProfileStack
            val hiddenTabs = config.hiddenTabs
            val lastMapRoomId = config.lastMapRoomId
            val logWithColors = config.logWithColors
            val statusGroupCollapsed = config.statusGroupCollapsed
            val outputSplitFractions = config.outputSplitFractions
            val sidePanelCollapsed = config.sidePanelCollapsed
            val pluginPermissions = config.pluginPermissions
            val configBackups = config.configBackups
            val outputBufferLines = config.outputBufferLines
            val commandHistorySize = config.commandHistorySize

            val contextCommandRules = config.contextCommandRules
            val contextCommandMaxQueueSize = config.contextCommandMaxQueueSize

            logger.info { "Config loaded from: $configFile (${triggers.size} triggers, ${aliases.size} aliases, ${hotkeys.size} hotkeys, ${variables.size} variables, ${tabs.size} tabs, ${contextCommandRules.size} context rules, encoding: $encoding, miniMapWidth: $miniMapWidth, miniMapHeight: $miniMapHeight, theme: $theme, fontFamily: $fontFamily, fontSize: $fontSize, ${connectionProfiles.size} connection profiles, ignoreNumLock: $ignoreNumLock, ${activeProfileStack.size} active profiles, lastMapRoomId: $lastMapRoomId)" }
            loaded = true
            return ConfigData(
                triggers = triggers,
                aliases = aliases,
                hotkeys = hotkeys,
                variables = variables,
                tabs = tabs,
                contextCommandRules = contextCommandRules,
                contextCommandMaxQueueSize = contextCommandMaxQueueSize,
                encoding = encoding,
                miniMapWidth = miniMapWidth,
                miniMapHeight = miniMapHeight,
                zonePanelWidth = zonePanelWidth,
                theme = theme,
                fontFamily = fontFamily,
                allowSystemFonts = allowSystemFonts,
                fontSize = fontSize,
                connectionProfiles = connectionProfiles,
                currentProfileId = currentProfileId,
                ignoreNumLock = ignoreNumLock,
                activeProfileStack = activeProfileStack,
                hiddenTabs = hiddenTabs,
                lastMapRoomId = lastMapRoomId,
                logWithColors = logWithColors,
                statusGroupCollapsed = statusGroupCollapsed,
                outputSplitFractions = outputSplitFractions,
                sidePanelCollapsed = sidePanelCollapsed,
                pluginPermissions = pluginPermissions,
                configBackups = configBackups,
                outputBufferLines = outputBufferLines,
                commandHistorySize = commandHistorySize
            )
        } catch (e: Exception) {
            // Не помечаем конфиг загруженным: файл есть, но прочитать не вышло.
            // Сохранение поверх затёрло бы его содержимое окончательно.
            logger.error { "Failed to load config: ${e.message}" }
            e.printStackTrace()
            return ConfigData(
                triggers = emptyList(),
                aliases = emptyList(),
                hotkeys = emptyList(),
                variables = emptyMap(),
                tabs = emptyList(),
                connectionProfiles = com.bylins.client.connection.ConnectionProfile.createDefaultProfiles()
            )
        }
    }

    /**
     * Экспортирует конфигурацию в указанный файл
     */
    fun exportConfig(file: File, triggers: List<Trigger>, aliases: List<Alias>, hotkeys: List<Hotkey>, variables: Map<String, String>, tabs: List<Tab>, encoding: String = "UTF-8", miniMapWidth: Int = 250, miniMapHeight: Int = 300, theme: String = "DARK", fontFamily: String = "MONOSPACE", fontSize: Int = 14) {
        try {
            val config = ClientConfig(
                triggers = triggers.map { TriggerDto.fromTrigger(it) },
                aliases = aliases.map { AliasDto.fromAlias(it) },
                hotkeys = hotkeys.map { HotkeyDto.fromHotkey(it) },
                variables = variables,
                tabs = tabs.map { TabDto.fromTab(it) },
                encoding = encoding,
                miniMapWidth = miniMapWidth,
                miniMapHeight = miniMapHeight,
                theme = theme,
                fontFamily = fontFamily,
                fontSize = fontSize
            )

            val jsonString = json.encodeToString(config)
            file.writeText(jsonString)

            logger.info { "Config exported to: ${file.absolutePath}" }
        } catch (e: Exception) {
            logger.error { "Failed to export config: ${e.message}" }
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Импортирует конфигурацию из указанного файла
     */
    fun importConfig(file: File): ConfigData {
        try {
            val jsonString = file.readText()
            val config = json.decodeFromString<ClientConfig>(jsonString)

            val triggers = config.triggers.map { it.toTrigger() }
            val aliases = config.aliases.map { it.toAlias() }
            val hotkeys = config.hotkeys.mapNotNull { it.toHotkey() }
            val variables = config.variables
            val tabs = config.tabs.map { it.toTab() }
            val encoding = config.encoding
            val miniMapWidth = config.miniMapWidth
            val miniMapHeight = config.miniMapHeight
            val zonePanelWidth = config.zonePanelWidth
            val theme = config.theme
            val fontFamily = config.fontFamily
            val fontSize = config.fontSize

            logger.info { "Config imported from: ${file.absolutePath} (${triggers.size} triggers, ${aliases.size} aliases, ${hotkeys.size} hotkeys, ${variables.size} variables, ${tabs.size} tabs, encoding: $encoding, miniMapWidth: $miniMapWidth, miniMapHeight: $miniMapHeight, theme: $theme, fontFamily: $fontFamily, fontSize: $fontSize)" }
            return ConfigData(
                triggers = triggers,
                aliases = aliases,
                hotkeys = hotkeys,
                variables = variables,
                tabs = tabs,
                encoding = encoding,
                miniMapWidth = miniMapWidth,
                miniMapHeight = miniMapHeight,
                zonePanelWidth = zonePanelWidth,
                theme = theme,
                fontFamily = fontFamily,
                fontSize = fontSize
            )
        } catch (e: Exception) {
            logger.error { "Failed to import config: ${e.message}" }
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Возвращает путь к директории конфига
     */
    fun getConfigDir(): String = configDir.toString()

    /**
     * Возвращает путь к файлу конфига
     */
    fun getConfigFile(): String = configFile.toString()
}

/**
 * Данные конфигурации
 */
data class ConfigData(
    val triggers: List<Trigger>,
    val aliases: List<Alias>,
    val hotkeys: List<Hotkey>,
    val variables: Map<String, String>,
    val tabs: List<Tab>,
    val contextCommandRules: List<ContextCommandRuleDto> = emptyList(),
    val contextCommandMaxQueueSize: Int = 50,
    val encoding: String = "UTF-8",
    val miniMapWidth: Int = 250,
    val miniMapHeight: Int = 300,
    val zonePanelWidth: Int = 220,
    val theme: String = "DARK",
    val fontFamily: String = "MONOSPACE",
    val allowSystemFonts: Boolean = false,
    val fontSize: Int = 14,
    val connectionProfiles: List<com.bylins.client.connection.ConnectionProfile> = emptyList(),
    val currentProfileId: String? = null,
    val ignoreNumLock: Boolean = false,
    val activeProfileStack: List<String> = emptyList(),
    val hiddenTabs: Set<String> = emptySet(),
    val lastMapRoomId: String? = null,
    val logWithColors: Boolean = false,
    val statusGroupCollapsed: Map<String, Boolean> = emptyMap(),
    val outputSplitFractions: Map<String, Float> = emptyMap(),
    val sidePanelCollapsed: Boolean = false,
    val pluginPermissions: Map<String, Set<String>> = emptyMap(),
    val configBackups: Int = DEFAULT_CONFIG_BACKUPS,
    val outputBufferLines: Int = DEFAULT_OUTPUT_BUFFER_LINES,
    val commandHistorySize: Int = DEFAULT_COMMAND_HISTORY_SIZE
)
