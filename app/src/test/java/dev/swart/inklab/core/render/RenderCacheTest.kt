package dev.swart.inklab.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenderCacheTest {
    @Test fun evictsLeastRecentlyUsedByByteBudget() {
        val cache = RenderCache<String, String>(5) { it.length.toLong() }
        cache.put("a", 1, "aa")
        cache.put("b", 1, "bb")
        cache.get("a", 1)
        cache.put("c", 1, "cc")
        assertNull(cache.get("b", 1))
        assertEquals("aa", cache.get("a", 1))
        assertEquals("cc", cache.get("c", 1))
    }

    @Test fun staleGenerationIsNeverReturned() {
        val cache = RenderCache<String, String>(20) { it.length.toLong() }
        cache.put("page", 5, "old")
        assertNull(cache.get("page", 6))
        assertEquals(0, cache.size())
    }
}
