package dev.swart.inklab.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageRangeTest {
    @Test fun blankMeansAllPages() {
        assertEquals(listOf(0, 1, 2), PageRange.parse("", 3))
    }

    @Test fun rangesAreOneBasedDeduplicatedAndOrderedByFirstAppearance() {
        assertEquals(listOf(0, 1, 2, 3, 4, 7, 5), PageRange.parse("1-5,8,3,6", 8))
    }

    @Test fun documentedExampleCreatesNinePages() {
        assertEquals(listOf(0,1,2,3,4,5,6,7,11), PageRange.parse("1-8,12", 12))
    }

    @Test fun invalidRangesFailBeforeMutation() {
        assertThrows(IllegalArgumentException::class.java) { PageRange.parse("0,2", 3) }
        assertThrows(IllegalArgumentException::class.java) { PageRange.parse("3-1", 3) }
        assertThrows(IllegalArgumentException::class.java) { PageRange.parse("1,,2", 3) }
    }
}
