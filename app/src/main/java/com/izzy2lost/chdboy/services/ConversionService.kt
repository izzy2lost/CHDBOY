package com.izzy2lost.chdboy.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.izzy2lost.chdboy.MainActivity
import com.izzy2lost.chdboy.R

/**
 * Keeps the process alive while a conversion runs.
 *
 * It does no work itself — [com.izzy2lost.chdboy.core.Converter] drives everything — but
 * a disc image takes minutes, and without a foreground service Android is free
 * to kill the app the moment the user switches away from it.
 */
class ConversionService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_preparing))
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setColor(ContextCompat.getColor(this, R.color.notification_accent))
                // Android 12 and up hold a foreground service notification back
                // for ten seconds, which is exactly the window in which the user
                // leaves the app and looks for it. A conversion is long-running
                // and user-initiated, so show it at once.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                // Indeterminate until the first real percentage arrives, so the
                // shade shows movement rather than an empty bar.
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(createPendingIntent(this))
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // startForeground throws if the notification is rejected, and a
            // service that cannot go foreground will be killed anyway -- so
            // stop cleanly rather than lingering in a state that looks fine.
            Log.e(TAG, "Could not start in the foreground", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ConversionService"

        const val CHANNEL_ID = "chdboy_compression_progress"
        const val NOTIFICATION_ID = 1001

        /**
         * The original channel, which cannot be reused.
         *
         * It was created at IMPORTANCE_LOW, and a channel's importance is fixed
         * once the system has seen it -- only the user can change it afterwards.
         * Raising it therefore takes a new id, and this one is deleted so the
         * app's notification settings do not keep a dead entry.
         */
        private const val LEGACY_CHANNEL_ID = "chdboy_compression"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }

            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            // IMPORTANCE_LOW classifies a notification as silent, and Android
            // hides silent notifications from the status bar unless the user has
            // opted in -- so the progress icon never appeared while a conversion
            // ran. DEFAULT is what earns the icon; the sound and vibration are
            // cleared here so it stays as quiet as LOW was.
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }

            manager.createNotificationChannel(channel)
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }

        fun updateProgress(context: Context, message: String, percent: Int) {
            notify(context, NOTIFICATION_ID) {
                setContentTitle(context.getString(R.string.notification_compressing))
                setContentText(message)
                setStyle(NotificationCompat.BigTextStyle().bigText(message))
                setProgress(100, percent.coerceIn(0, 100), false)
                setOngoing(true)
                setOnlyAlertOnce(true)
            }
        }

        fun updateIdle(context: Context, message: String) {
            notify(context, NOTIFICATION_ID) {
                setContentTitle(context.getString(R.string.app_name))
                setContentText(message)
                // 0/0/false removes the bar rather than leaving it part-filled.
                setProgress(0, 0, false)
                setOngoing(true)
                setOnlyAlertOnce(true)
            }
        }

        /** Posted under its own id so it survives the ongoing one being cleared. */
        fun notifyDone(context: Context, message: String) {
            notify(context, NOTIFICATION_ID + 1) {
                setContentTitle(context.getString(R.string.app_name))
                setContentText(message)
                setPriority(NotificationCompat.PRIORITY_HIGH)
                setCategory(NotificationCompat.CATEGORY_STATUS)
                setAutoCancel(true)
            }
        }

        private fun notify(context: Context, id: Int, configure: NotificationCompat.Builder.() -> Unit) {
            if (!hasNotificationPermission(context)) {
                return
            }

            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                // Every update replaces the foreground notification, so this has
                // to be re-stated or the deferral comes back with the next one.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(createPendingIntent(context))
                .apply(configure)
                .build()

            // The permission check above races with the user revoking it, so
            // this can still throw. Nothing about a notification is worth
            // taking the conversion down for.
            runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        }

        private fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }

            return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun createPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            var flags = PendingIntent.FLAG_UPDATE_CURRENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }

            return PendingIntent.getActivity(context, 0, intent, flags)
        }
    }
}
