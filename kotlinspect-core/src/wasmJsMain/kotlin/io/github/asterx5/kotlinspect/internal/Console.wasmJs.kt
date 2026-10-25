package io.github.asterx5.kotlinspect.internal

@JsFun("(message) => console.info(message)")
private external fun consoleInfo(message: String)

internal actual fun webConsoleInfo(message: String) {
    consoleInfo(message)
}
