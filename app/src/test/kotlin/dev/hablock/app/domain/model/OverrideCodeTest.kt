package dev.hablock.app.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverrideCodeTest {

    @Test
    fun `the household code is accepted`() {
        assertTrue(OverrideCode.matches("letmein"))
        assertTrue(OverrideCode.matches("  letmein  "))
    }

    @Test
    fun `anything else is refused`() {
        assertFalse(OverrideCode.matches(""))
        assertFalse(OverrideCode.matches("LETMEIN"))
        assertFalse(OverrideCode.matches("letmein1"))
        assertFalse(OverrideCode.matches("let me in"))
    }
}
