package com.astralink.terralink.util

import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT

actual fun systemUtcOffsetMinutes(): Int =
    (NSTimeZone.localTimeZone.secondsFromGMT / 60L).toInt()
