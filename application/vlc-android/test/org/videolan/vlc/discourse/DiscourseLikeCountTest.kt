package org.videolan.vlc.discourse

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscourseLikeCountTest {
    @Test
    fun hidesZeroAndFormatsCompactCountsWithTwoDecimalRounding() {
        val cases = mapOf(
            -1 to "", 0 to "", 1 to "1", 999 to "999", 1_000 to "1k",
            1_005 to "1.01k", 2_393 to "2.39k", 12_500 to "12.5k",
            999_994 to "999.99k", 999_995 to "1m", 1_000_000 to "1m",
            1_250_000 to "1.25m", 1_999_999 to "2m", Int.MAX_VALUE to "2147.48m"
        )
        cases.forEach { (count, expected) -> assertEquals("count=$count", expected, formatDiscourseLikes(count)) }
    }
}
