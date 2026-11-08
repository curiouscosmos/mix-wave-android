package org.videolan.vlc.gui.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscourseCardMetadataTest {
    @Test fun locationUsesOnlyTheFirstComponent() {
        assertEquals("Pune", discourseCardLocation(" Pune, Maharashtra, India "))
        assertEquals("Mumbai", discourseCardLocation("Mumbai"))
        listOf(null, "", " ", ", India").forEach { assertNull(discourseCardLocation(it)) }
    }

    @Test fun datesUseTheReferenceFormatWithoutTimezoneShifts() {
        assertEquals("Jun 10, 1974", discourseCardDate("1974-06-10"))
        assertEquals("Jun 10, 1974", discourseCardDate("1974-06-10T23:30:00-08:00"))
        assertEquals("Jun 10, 1974", discourseCardDate("1974-06-10 00:00:00"))
        assertEquals("Jun 10, 1974", discourseCardDate("Jun 10, 1974"))
        assertEquals("Mar 23, 1971", discourseCardDate("23rd March, 1971"))
        assertEquals("Feb 29, 1972", discourseCardDate("1972-02-29"))
    }

    @Test fun missingAndInvalidDatesAreHidden() {
        listOf(null, "", " ", "null", "not a date", "1971-02-29", "1974-13-10").forEach {
            assertNull(discourseCardDate(it))
        }
    }
}
