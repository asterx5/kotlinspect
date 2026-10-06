package io.github.asterx5.kotlinspect

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kotlinspect",
    ) {
        App()
    }
}