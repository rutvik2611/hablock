package dev.hablock.app.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import dev.hablock.app.R
import dev.hablock.app.domain.service.Notifier
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration

private const val CHANNEL_ID = "hablock"
private const val RELINQUISH_NOTIFICATION_ID = 1
private const val SESSION_NOTIFICATION_ID = 2
private const val CHECKPOINT_WARNING_NOTIFICATION_ID = 3
private const val GATE_CLOSED_NOTIFICATION_ID = 4
private const val GATE_OPENED_NOTIFICATION_ID = 5

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun steps(value: Double): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())

private fun clock(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(CLOCK)

class GateNotifier(
    private val context: Context,
    private val sessionDuration: Duration,
    private val contentIntent: () -> PendingIntent,
) : Notifier {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var channelReady = false

    override fun sessionEnded(blockId: String, blockName: String) {
        val minutes = sessionDuration.inWholeMinutes.toInt()
        notify(
            tag = blockId,
            id = SESSION_NOTIFICATION_ID,
            title = context.getString(R.string.notif_session_ended_title, blockName),
            text = context.resources.getQuantityString(R.plurals.notif_session_ended_text, minutes, minutes),
        )
    }

    override fun relinquishReady() {
        notify(
            tag = null,
            id = RELINQUISH_NOTIFICATION_ID,
            title = context.getString(R.string.notif_relinquish_title),
            text = context.getString(R.string.notif_relinquish_text),
        )
    }

    override fun checkpointWarning(stepsRemaining: Double, stepsTarget: Double, at: Instant) {
        notify(
            tag = "checkpoint-warning",
            id = CHECKPOINT_WARNING_NOTIFICATION_ID,
            title = context.getString(R.string.notif_checkpoint_title, steps(stepsTarget), clock(at)),
            text = context.getString(R.string.notif_checkpoint_text, steps(stepsRemaining), clock(at)),
        )
    }

    override fun gateClosed(stepsRemaining: Double, stepsTarget: Double) {
        notify(
            tag = "gate",
            id = GATE_CLOSED_NOTIFICATION_ID,
            title = context.getString(R.string.notif_gate_closed_title, steps(stepsTarget)),
            text = context.getString(R.string.notif_gate_closed_text, steps(stepsRemaining)),
        )
    }

    override fun gateOpened() {
        notify(
            tag = "gate",
            id = GATE_OPENED_NOTIFICATION_ID,
            title = context.getString(R.string.notif_gate_opened_title),
            text = context.getString(R.string.notif_gate_opened_text),
        )
    }

    private fun notify(tag: String?, id: Int, title: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(tag, id, notification)
        } catch (_: SecurityException) {
            // Notification permission revoked between the enabled check and notify — drop silently.
        }
    }

    private fun ensureChannel() {
        if (channelReady) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        channelReady = true
    }
}
