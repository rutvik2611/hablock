package dev.hablock.app.domain.model

import java.time.LocalDate

/**
 * The temporary-unlock passphrase: today's day, the word, then the month — `06letmein06`.
 *
 * It is derived from the device date on purpose, so there is nothing stored to leak and no hash to
 * compile in. The trade-off is real and worth stating: a passphrase built from the date is guessable
 * by anyone who learns the pattern, and moving the phone's clock moves the answer. It is a household
 * speed bump, not a security boundary.
 */
object OverrideCode {

    private const val WORD = "letmein"

    /** `03letmein11` on 3 November. Zero-padded, so single-digit days and months line up. */
    fun expected(today: LocalDate = LocalDate.now()): String =
        "%02d%s%02d".format(today.dayOfMonth, WORD, today.monthValue)

    fun matches(input: String, today: LocalDate = LocalDate.now()): Boolean =
        input.isNotBlank() && input.trim().equals(expected(today), ignoreCase = true)
}
