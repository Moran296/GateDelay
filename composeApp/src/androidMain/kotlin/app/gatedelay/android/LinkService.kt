package app.gatedelay.android

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
import androidx.core.content.ContextCompat
import app.gatedelay.data.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the app in the foreground while waiting for the PalGate QR scan.
 * Without it, Android blocks network access for the app as soon as the user switches
 * to the PalGate app to scan, and the linking answer (with the token) is lost.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)

        if (watch == null) {
            watch = scope.launch {
                val linker = AppGraph.deps.linker
                val finished = withTimeoutOrNull(MAX_WAIT_MS) { linker.state.first { it !is LinkState.WaitingForScan } }
                if (finished == null) linker.fail("Timed out waiting for the QR scan. Try again.")
                stop()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification() = run {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Account linking", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(app.gatedelay.R.drawable.ic_gate)
            .setContentTitle("GateDelay")
            .setContentText("Waiting for the PalGate QR scan… tap to return")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "linking"
        private const val NOTIFICATION_ID = 43
        private const val MAX_WAIT_MS = 15 * 60_000L

        /** Must be called while the app is in the foreground (Android forbids starting it from the background). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) }
        }
    }
}
