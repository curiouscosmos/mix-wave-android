package org.videolan.vlc.gui.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscourseCatalogueHeadingTest {
    @Test fun headingsFollowConsecutiveGroupsIncludingPageBoundaries() {
        assertEquals("A", discourseCatalogueHeading(" Adhyatam", null, true))
        assertNull(discourseCatalogueHeading("agyat", "Adhyatam", true))
        assertEquals("B", discourseCatalogueHeading("Barsaat", "agyat", true))
        assertNull(discourseCatalogueHeading("Bhakti", "Barsaat", true))
        assertEquals("#", discourseCatalogueHeading("123", null, true))
        assertNull(discourseCatalogueHeading("", "123", true))
        assertEquals("ध", discourseCatalogueHeading("ध्यान", "Barsaat", true))
        assertNull(discourseCatalogueHeading("Barsaat", "agyat", false))
    }
}
