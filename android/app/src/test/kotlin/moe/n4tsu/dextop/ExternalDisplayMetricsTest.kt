package moe.n4tsu.dextop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ExternalDisplayMetricsTest {
    @Test fun preservesExistingOverridesForRollback() {
        val value = ExternalDisplayMetrics.parse("Physical size: 1920x1080\nOverride size: 1600x900", "Physical density: 160\nOverride density: 200")
        assertEquals(1600, value.width)
        assertEquals(900, value.height)
        assertEquals(200, value.overrideDensity)
        assertEquals(1920, value.physicalWidth)
    }

    @Test fun absenceOfOverridesMeansRestoreWithReset() {
        val value = ExternalDisplayMetrics.parse("Physical size: 2560x1440\n", "Physical density: 240\n")
        assertNull(value.overrideWidth)
        assertNull(value.overrideHeight)
        assertNull(value.overrideDensity)
        assertEquals(240, value.density)
    }

    @Test fun rejectedOrMalformedQueriesCannotBecomeDefaultSnapshots() {
        assertFailsWith<IllegalStateException> { ExternalDisplayMetrics.parse("Permission denied", "Physical density: 160") }
        assertFailsWith<IllegalStateException> { ExternalDisplayMetrics.parse("Physical size: 1920x1080\nOverride size: invalid", "Physical density: 160") }
        assertFailsWith<IllegalStateException> { ExternalDisplayMetrics.parse("Physical size: 1920x1080", "Physical density: 160\nOverride density: invalid") }
    }

    @Test fun boundsPreventExcessiveBuffersAndInvalidDensity() {
        ExternalDisplayMetrics.validate(3840, 2160, 160)
        assertFailsWith<IllegalArgumentException> { ExternalDisplayMetrics.validate(7680, 7680, 160) }
        assertFailsWith<IllegalArgumentException> { ExternalDisplayMetrics.validate(1920, 1080, 0) }
        assertFailsWith<IllegalArgumentException> { ExternalDisplayMetrics.validate(0, 1080, 160) }
    }

    @Test fun oemDisplayIdentityIsNeverInterpretedAsShellCode() {
        assertEquals("'local:123'", ExternalDisplayMetrics.shellQuote("local:123"))
        assertEquals("'a'\"'\"'b'", ExternalDisplayMetrics.shellQuote("a'b"))
        assertEquals("'\$(id);`id`'", ExternalDisplayMetrics.shellQuote("\$(id);`id`"))
    }
}
