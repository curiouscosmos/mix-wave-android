package org.videolan.vlc.discourse

import java.math.BigDecimal
import java.math.RoundingMode

internal fun formatDiscourseLikes(count: Int): String {
    if (count <= 0) return ""
    if (count < 1_000) return count.toString()
    fun scaled(divisor: Int) = BigDecimal.valueOf(count.toLong())
        .divide(BigDecimal.valueOf(divisor.toLong()), 2, RoundingMode.HALF_UP)
    val thousands = scaled(1_000)
    return if (count >= 1_000_000 || thousands >= BigDecimal.valueOf(1_000))
        scaled(1_000_000).stripTrailingZeros().toPlainString() + "m"
    else thousands.stripTrailingZeros().toPlainString() + "k"
}
