package io.github.asterx5.kotlinspect

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform