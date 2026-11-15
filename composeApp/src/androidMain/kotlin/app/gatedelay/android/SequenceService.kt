package app.gatedelay.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.gatedelay.model.GateRole
import app.gatedelay.model.SequenceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Short-lived foreground service that keeps the process alive while a sequence runs
 * (e.g. the 27 s EXIT wait with the screen locked, or when started from the widget).
 */
class SequenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var active = 0
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runner = AppGraph.runner
        // Must enter the foreground promptly after startForegroundService().
        goForeground(runner.state.value.summary())

        val job = when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_KIND)?.let { runCatching { SequenceKind.valueOf(it) }.getOrNull() }
                ?.let { runner.start(it) }
            ACTION_SINGLE -> intent.getStringExtra(EXTRA_ROLE)?.let { runCatching { GateRole.valueOf(it) }.getOrNull() }
                ?.let { runner.openSingle(it) }
            ACTION_CANCEL -> { runner.cancel(); null }
            else -> null
        }

        if (statusJob == null) {
            statusJob = scope.launch { runner.state.collect { notify(it.summary()) } }
        }
        if (job != null) {
            active++
            scope.launch {
                job.join()
                if (--active == 0) stop()
            }
        } else if (active == 0) {
            stop()
        }
        return START_NOT_STICKY
    }

    /** Android 14+: shortService time limit reached (~3 min). */
    override fun onTimeout(startId: Int) {
        AppGraph.runner.cancel()
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun notify(text: String) {
        if (active > 0) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gate sequence", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SequenceService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(app.gatedelay.R.drawable.ic_gate)
            .setContentTitle("GateDelay")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL = "sequence"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "app.gatedelay.START"
        const val ACTION_SINGLE = "app.gatedelay.SINGLE"
        const val ACTION_CANCEL = "app.gatedelay.CANCEL"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ROLE = "role"

        fun startIntent(context: Context, kind: SequenceKind): Intent =
            Intent(context, SequenceService::class.java).setAction(ACTION_START).putExtra(EXTRA_KIND, kind.name)

        fun singleIntent(context: Context, role: GateRole): Intent =
            Intent(context, SequenceService::class.java).setAction(ACTION_SINGLE).putExtra(EXTRA_ROLE, role.name)
    }
}
