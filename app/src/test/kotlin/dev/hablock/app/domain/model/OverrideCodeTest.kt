package dev.hablock.app.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverrideCodeTest {

    @Test
    fun `the household code is accepted in any case`() {
        assertTrue(OverrideCode.matches("letmein"))
        assertTrue(OverrideCode.matches("  letmein  "))
        // A phone keyboard auto-capitalises the first letter; that must not lock the owner out.
        assertTrue(OverrideCode.matches("Letmein"))
        assertTrue(OverrideCode.matches("LETMEIN"))
    }

    @Test
    fun `anything else is refused`() {
        assertFalse(OverrideCode.matches(""))
        assertFalse(OverrideCode.matches("   "))
        assertFalse(OverrideCode.matches("letmein1"))
        assertFalse(OverrideCode.matches("let me in"))
        assertFalse(OverrideCode.matches("letmeout"))
    }
}
