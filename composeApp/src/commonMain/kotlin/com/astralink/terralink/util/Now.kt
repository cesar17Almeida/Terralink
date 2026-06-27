package com.astralink.terralink.util

/** Wall-clock epoch milliseconds. expect/actual so we avoid a kotlinx-datetime
 *  dependency just for a timestamp. */
expect fun nowEpochMs(): Long
