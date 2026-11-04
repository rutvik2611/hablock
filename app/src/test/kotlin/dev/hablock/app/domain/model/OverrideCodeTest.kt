package dev.hablock.app.domain.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverrideCodeTest {

    @Test
    fun `the passphrase is today plus the word plus the month`() {
        assertEquals("03letmein11", OverrideCode.expected(LocalDate.of(2026, 11, 3)))
        // Single-digit date and month are padded, so the shape never changes.
        assertEquals("06letmein06", OverrideCode.expected(LocalDate.of(2026, 6, 6)))
        assertEquals("01letmein01", OverrideCode.expected(LocalDate.of(2026, 1, 1)))
        assertEquals("31letmein12", OverrideCode.expected(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `the passphrase is accepted in any case and with slack`() {
        val day = LocalDate.of(2026, 11, 3)
        assertTrue(OverrideCode.matches("03letmein11", day))
        assertTrue(OverrideCode.matches("  03LETMEIN11  ", day))
    }

    @Test
    fun `yesterday and tomorrow are refused`() {
        val day = LocalDate.of(2026, 11, 3)
        assertFalse(OverrideCode.matches("02letmein11", day))
        assertFalse(OverrideCode.matches("04letmein11", day))
        assertFalse(OverrideCode.matches("letmein", day))
        assertFalse(OverrideCode.matches("", day))
        assertFalse(OverrideCode.matches("11letmein03", day))
    }
}
