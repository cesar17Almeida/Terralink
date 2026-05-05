package com.astralink.terralink

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform