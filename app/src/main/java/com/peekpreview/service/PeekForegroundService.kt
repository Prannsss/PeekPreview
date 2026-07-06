package com.peekpreview.service

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
import com.peekpreview.MainActivity
import com.peekpreview.R
import com.peekpreview.data.PeekPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent "PeekPreview is running" notification.
 *
 * HONEST NOTE on whether this is necessary: an AccessibilityService already has
 * elevated, sticky background privileges — the system keeps it bound while the
 * user has it enabled, and it is NOT subject to normal background-execution or
 * cached-process limits. So this foreground service is *not strictly required*
 * to keep the accessibility service alive. Its real jobs are:
 *   1. User transparency — a visible, always-present notification that the app
 *      is watching Messages, with a one-tap "Turn off".
 *   2. A small hedge on aggressive OEM task-killers that ignore the accessibility
 *      exemption.
 * That's why it's low-priority and does almost nothing. If you don't care about
 * (1)/(2), you could delete this service entirely and the feature still works.
 *
 * ponytail: kept because the spec wants user transparency, but deliberately thin
 * — no wakelocks, no polling, just a notification.
 */
class PeekForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            scope.launch {
                PeekPreferences.setMasterEnabled(applicationContext, false)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startInForegroundCompat()
        return START_STICKY
    }

    private fun startInForegroundCompat() {
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "peek_status"
        private const val NOTIFICATION_ID = 42
        const val ACTION_TURN_OFF = "com.peekpreview.action.TURN_OFF"

        fun start(context: Context) {
            val intent = Intent(context, PeekForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PeekForegroundService::class.java))
        }

        private fun buildNotification(context: Context): Notification {
            ensureChannel(context)

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val turnOff = PendingIntent.getService(
                context, 1,
                Intent(context, PeekForegroundService::class.java).setAction(ACTION_TURN_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.fg_notification_title))
                .setContentText(context.getString(R.string.fg_notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(openApp)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.fg_notification_turn_off),
                        turnOff,
                    ).build(),
                )
                .build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.fg_notification_channel),
                        NotificationManager.IMPORTANCE_LOW, // silent, low-priority
                    ),
                )
            }
        }
    }
}
