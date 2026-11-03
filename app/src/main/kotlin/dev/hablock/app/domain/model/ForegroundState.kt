package dev.hablock.app.domain.model

/**
 * The package the accessibility service last saw in the foreground. Written by the system layer and
 * read when the deadline alarm fires, so an app that is already on screen at 17:00 can be covered
 * without waiting for the user to switch away and back.
 */
object ForegroundState {
    @Volatile
    var packageName: String? = null
}
