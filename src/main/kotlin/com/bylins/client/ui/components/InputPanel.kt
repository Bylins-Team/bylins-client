package com.bylins.client.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bylins.client.ClientState

@Composable
fun InputPanel(
    clientState: ClientState,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // История общая на клиент и переживает перезапуск -- лежит в ~/.bylins-client/history.txt
    val commandHistory = clientState.commandHistory
    var historyIndex by remember { mutableStateOf(-1) }
    val isConnected by clientState.isConnected.collectAsState()

    // Подстановка по Tab: запомненное начало строки и место в списке совпадений.
    // Пока идёт перебор, начало не меняется, иначе после первой подстановки Tab искал бы
    // уже по подставленной команде.
    var completionPrefix by remember { mutableStateOf<String?>(null) }
    var completionMatches by remember { mutableStateOf<List<String>>(emptyList()) }
    var completionIndex by remember { mutableStateOf(-1) }

    fun resetCompletion() {
        completionPrefix = null
        completionMatches = emptyList()
        completionIndex = -1
    }

    fun complete(backwards: Boolean) {
        if (completionPrefix == null) {
            val prefix = inputText.text
            completionPrefix = prefix
            completionMatches = commandHistory.matches(prefix)
            completionIndex = -1
        }
        val matches = completionMatches
        if (matches.isEmpty()) return
        completionIndex = if (backwards) {
            if (completionIndex <= 0) matches.size - 1 else completionIndex - 1
        } else {
            (completionIndex + 1) % matches.size
        }
        val command = matches[completionIndex]
        inputText = TextFieldValue(text = command, selection = TextRange(command.length))
    }

    // Автоматически фокусируемся при первом рендере
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun sendCommand() {
        val text = inputText.text
        val isLocalCommand = text.startsWith("#")

        // Локальные команды (#vars, #help и т.д.) работают всегда
        // Остальные команды требуют подключения
        if (isLocalCommand || isConnected) {
            // Добавляем в историю только непустые команды. Пароль не добавляем: сервер
            // только что его спросил, а история лежит на диске открытым текстом.
            val wasPasswordPrompt = clientState.consumePasswordPrompt()
            if (text.isNotBlank() && !wasPasswordPrompt) {
                commandHistory.add(text)
            }
            historyIndex = -1
            resetCompletion()
            clientState.send(text)
            inputText = TextFieldValue("")
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val placeholderText = if (isConnected) "Команда" else "Команда (# для локальных)"
            if (inputText.text.isEmpty()) {
                Text(
                    text = placeholderText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
            }
            val clipboard = LocalClipboardManager.current
            BasicTextField(
                value = inputText,
                onValueChange = { newValue ->
                    if (!clientState.wasHotkeyRecentlyProcessed()) {
                        inputText = newValue
                        // Набрали что-то своё -- перебор по Tab начинается заново
                        resetCompletion()
                    }
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        // Ctrl+C/V/X/A в русской раскладке: Compose видит «с», а не
                        // C, и своё копирование не запускает. Если физическая
                        // клавиша — латинская, а код события — нет, делаем то же
                        // руками; где коды совпадают, ветка не срабатывает
                        val physical = com.bylins.client.hotkeys.PhysicalKey.of(event)
                        val isCommand = com.bylins.client.ui.CommandModifier.isPressed(event.isCtrlPressed, event.isMetaPressed)
                        val layoutFallback = event.type == KeyEventType.KeyDown && isCommand && physical != event.key &&
                            !event.isAltPressed && !event.isShiftPressed
                        when {
                            layoutFallback && physical == Key.C -> {
                                selectedText(inputText)?.let { clipboard.setText(AnnotatedString(it)) }
                                    ?: clientState.copyOutputSelection()
                                true
                            }
                            // Ctrl+C без выделения в самой строке копирует выделенное
                            // в выводе: фокус тут, а выделяют мышью там
                            event.type == KeyEventType.KeyDown && isCommand && physical == Key.C
                                && selectedText(inputText) == null -> {
                                clientState.copyOutputSelection()
                            }
                            layoutFallback && physical == Key.X -> {
                                selectedText(inputText)?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    inputText = replaceSelection(inputText, "")
                                }
                                true
                            }
                            layoutFallback && physical == Key.V -> {
                                clipboard.getText()?.text?.let { inputText = replaceSelection(inputText, it) }
                                true
                            }
                            layoutFallback && physical == Key.A -> {
                                inputText = inputText.copy(selection = TextRange(0, inputText.text.length))
                                true
                            }
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) && event.type == KeyEventType.KeyDown -> {
                                sendCommand()
                                true
                            }
                            event.key == Key.Tab && event.type == KeyEventType.KeyDown -> {
                                // Подстановка из истории: Tab -- следующая (более старая)
                                // команда с тем же началом, Shift+Tab -- назад.
                                complete(backwards = event.isShiftPressed)
                                true
                            }
                            event.key == Key.Escape && event.type == KeyEventType.KeyDown
                                && completionPrefix != null -> {
                                // Вернуть то, что набрали до перебора
                                val prefix = completionPrefix ?: ""
                                inputText = TextFieldValue(prefix, TextRange(prefix.length))
                                resetCompletion()
                                true
                            }
                            event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown -> {
                                resetCompletion()
                                val history = commandHistory.all()
                                if (history.isNotEmpty()) {
                                    historyIndex = (historyIndex + 1).coerceAtMost(history.size - 1)
                                    val command = history[history.size - 1 - historyIndex]
                                    inputText = TextFieldValue(
                                        text = command,
                                        selection = TextRange(command.length)
                                    )
                                }
                                true
                            }
                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown -> {
                                resetCompletion()
                                val history = commandHistory.all()
                                if (historyIndex > 0 && history.isNotEmpty()) {
                                    historyIndex--
                                    val command = history[history.size - 1 - historyIndex]
                                    inputText = TextFieldValue(
                                        text = command,
                                        selection = TextRange(command.length)
                                    )
                                } else {
                                    historyIndex = -1
                                    inputText = TextFieldValue("")
                                }
                                true
                            }
                            else -> false
                        }
                    }
            )
        }

        IconButton(
            onClick = { sendCommand() },
            enabled = true  // Всегда включено для локальных команд
        ) {
            Icon(Icons.Default.Send, contentDescription = "Отправить")
        }
    }
}

/** Выделенный текст поля; null, если выделения нет. */
private fun selectedText(value: TextFieldValue): String? {
    val range = value.selection
    if (range.collapsed) return null
    return value.text.substring(range.min, range.max)
}

/** Заменяет выделение (или вставляет в позицию курсора) текстом [text]. */
private fun replaceSelection(value: TextFieldValue, text: String): TextFieldValue {
    val range = value.selection
    val replaced = value.text.substring(0, range.min) + text + value.text.substring(range.max)
    return TextFieldValue(text = replaced, selection = TextRange(range.min + text.length))
}
