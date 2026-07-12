package com.astralink.terralink.util

import java.util.TimeZone

actual fun systemUtcOffsetMinutes(): Int =
    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
