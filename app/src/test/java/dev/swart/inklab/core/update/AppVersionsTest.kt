package dev.swart.inklab.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionsTest {
    @Test fun comparesNumericSegments() {
        assertTrue(AppVersions.isNewer("2.1.0", "2.0.9"))
        assertTrue(AppVersions.isNewer("v2.0.10", "2.0.9"))
        assertFalse(AppVersions.isNewer("2.0.3", "2.0.3"))
        assertFalse(AppVersions.isNewer("1.9.9", "2.0.0"))
    }
}
