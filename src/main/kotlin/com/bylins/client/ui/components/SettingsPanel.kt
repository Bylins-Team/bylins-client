package com.bylins.client.ui.components

import mu.KotlinLogging
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bylins.client.ClientState
import com.bylins.client.OperatingSystem
import com.bylins.client.config.MAX_CONFIG_BACKUPS
import com.bylins.client.config.MAX_OUTPUT_BUFFER_LINES
import com.bylins.client.config.MIN_OUTPUT_BUFFER_LINES
import com.bylins.client.PERMANENT_TAB_IDS
import com.bylins.client.ui.theme.LocalAppColorScheme
import com.bylins.client.ui.ALL_TABS
import java.awt.Desktop
import java.io.File

@OptIn(ExperimentalMaterialApi::class)
private val logger = KotlinLogging.logger("SettingsPanel")
@Composable
fun SettingsPanel(
    clientState: ClientState,
    modifier: Modifier = Modifier
) {
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusColor by remember { mutableStateOf<Color?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val colorScheme = LocalAppColorScheme.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        Text(
            text = "Настройки",
            color = colorScheme.onSurface,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Divider(color = colorScheme.divider, thickness = 1.dp)

        // Логирование
        val isLogging by clientState.isLogging.collectAsState()
        val currentLogFile by clientState.currentLogFile.collectAsState()

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = colorScheme.surface,
            elevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Логирование",
                    color = colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = colorScheme.divider, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Статус:",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = if (isLogging) "Активно" else "Остановлено",
                        color = if (isLogging) colorScheme.success else colorScheme.error,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (currentLogFile != null) {
                    val clipboard = LocalClipboardManager.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Путь можно выделить и скопировать (Ctrl+C), либо кликнуть по нему
                        // целиком для копирования всего пути в буфер обмена.
                        SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "Файл: $currentLogFile",
                                color = colorScheme.success,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable {
                                        clipboard.setText(AnnotatedString(currentLogFile!!))
                                        statusMessage = "Путь скопирован в буфер обмена"
                                        statusColor = colorScheme.success
                                    }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isLogging) {
                                clientState.stopLogging()
                                statusMessage = "Логирование остановлено"
                                statusColor = colorScheme.warning
                            } else {
                                clientState.startLogging(stripAnsi = true)
                                statusMessage = "Логирование начато"
                                statusColor = colorScheme.success
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isLogging) colorScheme.error else colorScheme.success
                        )
                    ) {
                        Text(
                            text = if (isLogging) "Остановить" else "Начать",
                            color = colorScheme.onSurface
                        )
                    }

                    Button(
                        onClick = {
                            val file = currentLogFile
                            if (file != null) {
                                revealInExplorer(file)
                                statusMessage = "Лог показан в проводнике"
                            } else {
                                openDirectory(clientState.getLogsDirectory())
                                statusMessage = "Открыта директория логов"
                            }
                            statusColor = colorScheme.success
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colorScheme.primary)
                    ) {
                        Text(
                            text = if (currentLogFile != null) "Показать в проводнике" else "Открыть папку",
                            color = colorScheme.onSurface
                        )
                    }

                    Button(
                        onClick = {
                            val count = clientState.getLogFiles().size
                            clientState.cleanOldLogs(30)
                            val newCount = clientState.getLogFiles().size
                            statusMessage = "Удалено старых логов: ${count - newCount}"
                            statusColor = colorScheme.success
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colorScheme.warning)
                    ) {
                        Text("Очистить старые", color = colorScheme.onSurface)
                    }
                }

                // Галочка "Сохранять цвета в логах"
                val logWithColors by clientState.logWithColors.collectAsState()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = logWithColors,
                        onCheckedChange = { clientState.setLogWithColors(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colorScheme.success,
                            uncheckedColor = colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        text = "Сохранять цвета (ANSI-коды)",
                        color = colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Divider(color = colorScheme.divider, modifier = Modifier.padding(vertical = 8.dp))

                // Глубина истории вывода. Платится памятью, не задержкой:
                // на приход текста работа идёт по изменившимся строкам
                val outputBufferLines by clientState.outputBufferLines.collectAsState()
                var bufferText by remember(outputBufferLines) { mutableStateOf(outputBufferLines.toString()) }

                Text(
                    text = "История вывода",
                    color = colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = bufferText,
                        onValueChange = { value ->
                            bufferText = value.filter { it.isDigit() }.take(7)
                            bufferText.toIntOrNull()?.let { clientState.setOutputBufferLines(it) }
                        },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = colorScheme.onSurface,
                            backgroundColor = colorScheme.background,
                            cursorColor = colorScheme.onSurface,
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.border
                        )
                    )
                    Text(
                        text = "строк держится в памяти и доступно прокруткой, поиском и выделением " +
                            "(от $MIN_OUTPUT_BUFFER_LINES до $MAX_OUTPUT_BUFFER_LINES). " +
                            "Около килобайта на строку",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Divider(color = colorScheme.divider, modifier = Modifier.padding(vertical = 8.dp))

                // Резервные копии настроек: список триггеров копится месяцами,
                // а испорченную запись замечаешь далеко не сразу
                val configBackups by clientState.configBackups.collectAsState()
                var backupsText by remember(configBackups) { mutableStateOf(configBackups.toString()) }

                Text(
                    text = "Резервные копии настроек",
                    color = colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = backupsText,
                        onValueChange = { text ->
                            backupsText = text.filter { it.isDigit() }.take(2)
                            backupsText.toIntOrNull()?.let { clientState.setConfigBackups(it) }
                        },
                        singleLine = true,
                        modifier = Modifier.width(90.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = colorScheme.onSurface,
                            backgroundColor = colorScheme.background,
                            cursorColor = colorScheme.onSurface,
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.border
                        )
                    )
                    Text(
                        text = if (configBackups == 0) {
                            "копии выключены"
                        } else {
                            "прошлых версий рядом с config.json (0 — выключить, максимум $MAX_CONFIG_BACKUPS)"
                        },
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        // Тема оформления
        val currentTheme by clientState.currentTheme.collectAsState()
        var themeExpanded by remember { mutableStateOf(false) }

        val themes = listOf(
            "DARK" to "Тёмная",
            "LIGHT" to "Светлая",
            "DARK_BLUE" to "Тёмно-синяя",
            "SOLARIZED_DARK" to "Solarized Dark",
            "MONOKAI" to "Monokai"
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = colorScheme.surface,
            elevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Тема оформления",
                    color = colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = colorScheme.divider, thickness = 1.dp)

                ExposedDropdownMenuBox(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = !themeExpanded }
                ) {
                    OutlinedTextField(
                        value = themes.find { it.first == currentTheme }?.second ?: currentTheme,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = colorScheme.onSurface,
                            focusedBorderColor = colorScheme.success,
                            unfocusedBorderColor = colorScheme.border
                        )
                    )

                    ExposedDropdownMenu(
                        expanded = themeExpanded,
                        onDismissRequest = { themeExpanded = false },
                        modifier = Modifier.background(colorScheme.surface)
                    ) {
                        themes.forEach { (themeId, themeName) ->
                            DropdownMenuItem(
                                onClick = {
                                    clientState.setTheme(themeId)
                                    themeExpanded = false
                                },
                                modifier = Modifier.background(colorScheme.surface)
                            ) {
                                Text(themeName, color = colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }

        // Шрифты
        val currentFontFamily by clientState.fontFamily.collectAsState()
        val currentFontSize by clientState.fontSize.collectAsState()
        var fontExpanded by remember { mutableStateOf(false) }

        val fontFamilies = listOf(
            "MONOSPACE" to Pair("Monospace", FontFamily.Monospace),
            "SERIF" to Pair("Serif", FontFamily.Serif),
            "SANS_SERIF" to Pair("Sans Serif", FontFamily.SansSerif),
            "CURSIVE" to Pair("Cursive", FontFamily.Cursive)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = colorScheme.surface,
            elevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Шрифты",
                    color = colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = colorScheme.divider, thickness = 1.dp)

                // Выбор шрифта
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Шрифт:",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(80.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = fontExpanded,
                        onExpandedChange = { fontExpanded = !fontExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        val currentFont = fontFamilies.find { it.first == currentFontFamily }

                        OutlinedTextField(
                            value = currentFont?.second?.first ?: currentFontFamily,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontExpanded) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = currentFont?.second?.second ?: FontFamily.Monospace
                            ),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = colorScheme.onSurface,
                                focusedBorderColor = colorScheme.success,
                                unfocusedBorderColor = colorScheme.border
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = fontExpanded,
                            onDismissRequest = { fontExpanded = false },
                            modifier = Modifier.background(colorScheme.surface)
                        ) {
                            fontFamilies.forEach { (familyId, fontInfo) ->
                                DropdownMenuItem(
                                    onClick = {
                                        clientState.setFontFamily(familyId)
                                        fontExpanded = false
                                        statusMessage = "Шрифт изменён"
                                        statusColor = colorScheme.success
                                    },
                                    modifier = Modifier.background(colorScheme.surface)
                                ) {
                                    Text(
                                        text = fontInfo.first,
                                        fontFamily = fontInfo.second,
                                        color = colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Размер шрифта
                var fontSizeExpanded by remember { mutableStateOf(false) }
                val fontSizes = (10..24).toList()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Размер:",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(80.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = fontSizeExpanded,
                        onExpandedChange = { fontSizeExpanded = !fontSizeExpanded },
                        modifier = Modifier.width(120.dp)
                    ) {
                        OutlinedTextField(
                            value = "$currentFontSize sp",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontSizeExpanded) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = colorScheme.onSurface,
                                focusedBorderColor = colorScheme.success,
                                unfocusedBorderColor = colorScheme.border
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = fontSizeExpanded,
                            onDismissRequest = { fontSizeExpanded = false },
                            modifier = Modifier.background(colorScheme.surface)
                        ) {
                            fontSizes.forEach { size ->
                                DropdownMenuItem(
                                    onClick = {
                                        clientState.setFontSize(size)
                                        fontSizeExpanded = false
                                    },
                                    modifier = Modifier.background(colorScheme.surface)
                                ) {
                                    Text("$size sp", color = colorScheme.onSurface)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = {
                        clientState.setFontSize(14)
                        statusMessage = "Размер сброшен"
                        statusColor = colorScheme.success
                    }) {
                        Text("Сброс", color = colorScheme.primary)
                    }
                }

                // Превью
                Text(
                    text = "Пример текста выбранным шрифтом",
                    color = colorScheme.onSurface,
                    fontSize = currentFontSize.sp,
                    fontFamily = fontFamilies.find { it.first == currentFontFamily }?.second?.second
                        ?: FontFamily.Monospace
                )
            }
        }

        // Кодировка
        val currentEncoding = clientState.encoding
        var encodingExpanded by remember { mutableStateOf(false) }

        val encodings = listOf(
            "Windows-1251" to "Windows-1251 (Windows)",
            "UTF-8" to "UTF-8",
            "KOI8-R" to "KOI8-R",
            "CP866" to "CP866 (DOS)"
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = colorScheme.surface,
            elevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Кодировка",
                    color = colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = colorScheme.divider, thickness = 1.dp)

                Text(
                    text = "Выберите кодировку сервера. Для Bylins обычно Windows-1251.",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                ExposedDropdownMenuBox(
                    expanded = encodingExpanded,
                    onExpandedChange = { encodingExpanded = !encodingExpanded }
                ) {
                    OutlinedTextField(
                        value = encodings.find { it.first == currentEncoding }?.second ?: currentEncoding,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = encodingExpanded) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = colorScheme.onSurface,
                            focusedBorderColor = colorScheme.success,
                            unfocusedBorderColor = colorScheme.border
                        )
                    )

                    ExposedDropdownMenu(
                        expanded = encodingExpanded,
                        onDismissRequest = { encodingExpanded = false },
                        modifier = Modifier.background(colorScheme.surface)
                    ) {
                        encodings.forEach { (encodingId, encodingName) ->
                            DropdownMenuItem(
                                onClick = {
                                    clientState.setEncoding(encodingId)
                                    encodingExpanded = false
                                    statusMessage = "Кодировка изменена на $encodingId"
                                    statusColor = colorScheme.success
                                },
                                modifier = Modifier.background(colorScheme.surface)
                            ) {
                                Text(encodingName, color = colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }

        // Конфигурация
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = colorScheme.surface,
            elevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Конфигурация",
                    color = colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = colorScheme.divider, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = clientState.getConfigPath(),
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            openFile(clientState.getConfigPath())
                            statusMessage = "Открыт файл конфигурации"
                            statusColor = colorScheme.success
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colorScheme.primary)
                    ) {
                        Text("Открыть", color = colorScheme.onSurface)
                    }
                }

                val triggers by clientState.triggers.collectAsState()
                val aliases by clientState.aliases.collectAsState()
                val hotkeys by clientState.hotkeys.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Триггеров: ${triggers.size}",
                        color = colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Алиасов: ${aliases.size}",
                        color = colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Хоткеев: ${hotkeys.size}",
                        color = colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showExportDialog = true },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colorScheme.primary)
                    ) {
                        Text("Экспорт", color = colorScheme.onSurface)
                    }

                    Button(
                        onClick = { showImportDialog = true },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colorScheme.warning)
                    ) {
                        Text("Импорт", color = colorScheme.onSurface)
                    }
                }
            }
        }

        // Видимость вкладок
        val hiddenTabs by clientState.hiddenTabs.collectAsState()

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = colorScheme.surface,
            elevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Видимость вкладок",
                    color = colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = colorScheme.divider, thickness = 1.dp)

                Text(
                    text = "Отметьте вкладки, которые хотите видеть в интерфейсе",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Две колонки с чекбоксами
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Первая колонка
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ALL_TABS.take(7).forEach { tab ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = tab.id in PERMANENT_TAB_IDS || tab.id !in hiddenTabs,
                                    enabled = tab.id !in PERMANENT_TAB_IDS,
                                    onCheckedChange = { checked ->
                                        clientState.setTabVisible(tab.id, checked)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = colorScheme.success,
                                        uncheckedColor = colorScheme.onSurfaceVariant
                                    )
                                )
                                Text(
                                    text = tab.name,
                                    color = colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }

                    // Вторая колонка
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ALL_TABS.drop(7).forEach { tab ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = tab.id in PERMANENT_TAB_IDS || tab.id !in hiddenTabs,
                                    enabled = tab.id !in PERMANENT_TAB_IDS,
                                    onCheckedChange = { checked ->
                                        clientState.setTabVisible(tab.id, checked)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = colorScheme.success,
                                        uncheckedColor = colorScheme.onSurfaceVariant
                                    )
                                )
                                Text(
                                    text = tab.name,
                                    color = colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Статусное сообщение
        if (statusMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = colorScheme.surface,
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = statusMessage!!,
                        color = statusColor ?: colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(onClick = { statusMessage = null }) {
                        Text("✕", color = colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // Диалог экспорта конфигурации
    if (showExportDialog) {
        FilePickerDialog(
            mode = FilePickerMode.SAVE,
            title = "Экспорт конфигурации",
            initialDirectory = File(System.getProperty("user.home")),
            extensions = listOf("json"),
            defaultFileName = "bylins-config.json",
            onDismiss = { showExportDialog = false },
            onFileSelected = { file ->
                try {
                    var targetFile = file
                    if (!targetFile.name.endsWith(".json")) {
                        targetFile = File(targetFile.parentFile, targetFile.name + ".json")
                    }
                    clientState.exportConfig(targetFile)
                    statusMessage = "Конфигурация экспортирована"
                    statusColor = colorScheme.success
                } catch (e: Exception) {
                    statusMessage = "Ошибка: ${e.message}"
                    statusColor = colorScheme.error
                }
                showExportDialog = false
            }
        )
    }

    // Диалог импорта конфигурации
    if (showImportDialog) {
        FilePickerDialog(
            mode = FilePickerMode.OPEN,
            title = "Импорт конфигурации",
            initialDirectory = File(System.getProperty("user.home")),
            extensions = listOf("json"),
            onDismiss = { showImportDialog = false },
            onFileSelected = { file ->
                try {
                    clientState.importConfig(file)
                    statusMessage = "Конфигурация импортирована"
                    statusColor = colorScheme.success
                } catch (e: Exception) {
                    statusMessage = "Ошибка: ${e.message}"
                    statusColor = colorScheme.error
                }
                showImportDialog = false
            }
        )
    }
}

private fun openDirectory(path: String) {
    try {
        val os = OperatingSystem.current
        when {
            os == OperatingSystem.Windows -> Runtime.getRuntime().exec(arrayOf("explorer", path))
            os == OperatingSystem.MacOS -> Runtime.getRuntime().exec(arrayOf("open", path))
            else -> Runtime.getRuntime().exec(arrayOf("xdg-open", path))
        }
    } catch (e: Exception) {
        logger.error { "Error opening directory: ${e.message}" }
    }
}

/**
 * Открывает проводник с выделенным файлом [filePath].
 * На Linux нет стандартного «выделить файл» — открываем родительскую папку.
 */
private fun revealInExplorer(filePath: String) {
    try {
        val os = OperatingSystem.current
        when {
            os == OperatingSystem.Windows -> Runtime.getRuntime().exec(arrayOf("explorer", "/select,$filePath"))
            os == OperatingSystem.MacOS -> Runtime.getRuntime().exec(arrayOf("open", "-R", filePath))
            else -> {
                val parent = File(filePath).parent ?: filePath
                Runtime.getRuntime().exec(arrayOf("xdg-open", parent))
            }
        }
    } catch (e: Exception) {
        logger.error { "Error revealing file in explorer: ${e.message}" }
    }
}

private fun openFile(path: String) {
    try {
        val file = File(path)
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        } else {
            val os = OperatingSystem.current
            when {
                os == OperatingSystem.Windows -> Runtime.getRuntime().exec(arrayOf("notepad", path))
                os == OperatingSystem.MacOS -> Runtime.getRuntime().exec(arrayOf("open", "-t", path))
                else -> Runtime.getRuntime().exec(arrayOf("xdg-open", path))
            }
        }
    } catch (e: Exception) {
        logger.error { "Error opening file: ${e.message}" }
    }
}
