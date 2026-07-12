package com.astralink.terralink.util

/** Current system UTC offset in minutes (may be negative). expect/actual so we avoid
 *  a kotlinx-datetime dependency just for one integer. */
expect fun systemUtcOffsetMinutes(): Int
