package org.videolan.vlc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionCheckTest {
    @Test fun comparesNumericComponents() {
        assertTrue(isVersionLower("1.1.9", "1.2.0"))
        assertTrue(isVersionLower("1.2", "1.2.1"))
        assertTrue(isVersionLower("1.9.0", "1.10.0"))
        assertFalse(isVersionLower("1.2.0", "1.2.0"))
        assertFalse(isVersionLower("1.2", "1.2.0"))
        assertFalse(isVersionLower("1.3.0", "1.2.0"))
        assertFalse(isVersionLower("2.0", "1.99.99"))
    }

    @Test fun invalidVersionsDoNotRequireUpdates() {
        for (invalid in listOf("", "1..2", "1.x", "-1.0", "1.2-beta")) {
            assertFalse(isVersionLower(invalid, "1.2.0"))
            assertFalse(isVersionLower("1.0.0", invalid))
        }
    }
}
