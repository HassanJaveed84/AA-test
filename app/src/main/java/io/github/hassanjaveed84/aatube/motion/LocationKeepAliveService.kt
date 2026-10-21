package io.github.hassanjaveed84.aatube.motion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.hassanjaveed84.aatube.R
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.KeepAliveState

/**
 * A location-type foreground service held for the length of the car
 * session, so the GPS fallback of the parked-only gate keeps receiving
 * updates while the phone screen is off. It does no work of its own.
 *
 * Android 12+ may refuse to start it from the background; the gate then
 * relies on the car's speed signal (or closes), and the refusal is logged.
 */
class LocationKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (state == KeepAliveState.RUNNING) state = KeepAliveState.STOPPED
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            state = KeepAliveState.RUNNING
            AppHub.record("speed", "location foreground service running")
        } catch (e: RuntimeException) {
            state = KeepAliveState.REFUSED
            AppHub.record("speed", "location foreground service refused: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "parked_gate"
        private const val NOTIFICATION_ID = 1

        /** Last known state, for the gate diagnostics line. */
        @Volatile
        var state: KeepAliveState = KeepAliveState.NOT_STARTED
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocationKeepAliveService::class.java))
                state = KeepAliveState.START_REQUESTED
                AppHub.record("speed", "location foreground service start requested")
            } catch (e: RuntimeException) {
                state = KeepAliveState.REFUSED
                AppHub.record("speed", "location foreground service not started: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationKeepAliveService::class.java))
        }
    }
}
