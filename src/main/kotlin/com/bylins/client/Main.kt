package com.bylins.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.bylins.client.ui.MainWindow

fun main() {
    application {
        val windowState = rememberWindowState(
            width = 1200.dp,
            height = 800.dp
        )

        Window(
            onCloseRequest = ::exitApplication,
            // Версия и ревизия в заголовке: по окну видно, какая сборка запущена
            title = "Bylins MUD Client ${BuildInfo.full}",
            state = windowState
        ) {
            MainWindow()
        }
    }
}
