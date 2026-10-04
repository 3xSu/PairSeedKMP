package com.pairseedkmp.domain

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform