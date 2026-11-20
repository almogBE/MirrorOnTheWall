package com.mirroronthewall.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service of type `mediaProjection` that hosts screen mirroring.
 *
 * Android 14+ order (ARCHITECTURE.md §6.1):
 * 1. The activity obtains user consent (createScreenCaptureIntent) and starts this service with the result.
 * 2. Here, [startForeground] with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION runs **first**.
 * 3. Only then does [SenderSession.startMirroring] build the capturer. The capturer
 *    obtains the MediaProjection, registers its callback, and creates the
 *    VirtualDisplay, in that order.
 *
 * The service stops itself once the session reports that mirroring ended, whether
 * by user, TV, system, or error.
 */
class MirrorService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent, startId)
            ACTION_STOP -> senderSession.stopMirroring("Mirroring stopped from the notification.")
            else -> stopSelf(startId)
        }
        // Never restart without a fresh consent. The consent token is single-use on Android 14+.
        return START_NOT_STICKY
    }

    private fun start(intent: Intent, startId: Int) {
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        if (data == null) {
            stopSelf(startId)
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: RuntimeException) {
            // E.g. SecurityException if consent was not granted, or
            // ForegroundServiceStartNotAllowedException.
            Log.e(TAG, "startForeground failed", e)
            senderSession.stopMirroring("Couldn't start screen capture: ${e.message}")
            stopSelf(startId)
            return
        }
        lifecycleScope.launch {
            val session = senderSession
            if (session.startMirroring(this@MirrorService, data)) {
                session.mirroringActive.first { !it }
            }
            ServiceCompat.stopForeground(this@MirrorService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        // If the system destroys us while mirroring, don't leave the capture running.
        if (senderSession.mirroringActive.value) senderSession.stopMirroring("Mirroring service was stopped.")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val tvName = (senderSession.connection.value as? SenderSession.ConnectionUi.Connected)?.receiverName
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(tvName?.let { getString(R.string.notif_text, it) })
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_mirroring), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val ACTION_START = "com.mirroronthewall.sender.START"
        private const val ACTION_STOP = "com.mirroronthewall.sender.STOP"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1

        /** Call with the data Intent of a RESULT_OK screen-capture consent. */
        fun start(context: Context, projectionData: Intent) {
            val i = Intent(context, MirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
