package dev.hablock.app.domain.model

import java.security.MessageDigest

/**
 * The temporary-unlock password. Only the SHA-256 digest is compiled in, so the code is not sitting
 * in the APK as a readable string. This is a household speed bump, not a security boundary: anyone
 * who can read the source can find the digest, and a determined user can patch the app.
 */
object OverrideCode {

    private const val DIGEST =
        "1c8bfe8f801d79745c4631d09fff36c82aa37fc4cce4fc946683d7b336b63032"

    fun matches(input: String): Boolean {
        if (input.isBlank()) return false
        val candidate = MessageDigest.getInstance("SHA-256")
            .digest(input.trim().lowercase().toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(candidate, DIGEST.hexToBytes())
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
