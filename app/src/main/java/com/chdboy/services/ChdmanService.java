package com.chdboy.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.chdboy.MainActivity;
import com.chdboy.R;

public class ChdmanService extends Service {
    public static final String CHANNEL_ID = "chdboy_compression";
    public static final int NOTIF_ID = 1001;
    
    private static Context serviceContext;

    @Override
    public void onCreate() {
        super.onCreate();
        android.util.Log.d("ChdmanService", "Service onCreate");
        ensureChannel();
        serviceContext = this;
        startForegroundNotification();
    }
    
    private void startForegroundNotification() {
        try {
            PendingIntent pendingIntent = createPendingIntent(this);
            Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("CHDBOY")
                    .setContentText("Starting compression...")
                .setSmallIcon(R.drawable.ic_stat_name)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setContentIntent(pendingIntent)
                    .build();
            startForeground(NOTIF_ID, notif);
            android.util.Log.d("ChdmanService", "Started foreground notification");
        } catch (Exception e) {
            android.util.Log.e("ChdmanService", "Failed to start foreground: " + e.getMessage());
            // Try with minimal notification
            try {
                PendingIntent pendingIntent = createPendingIntent(this);
                Notification minimal = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("CHDBOY")
                        .setContentText("Working...")
                        .setSmallIcon(R.drawable.ic_stat_name)
                        .setContentIntent(pendingIntent)
                        .build();
                startForeground(NOTIF_ID, minimal);
            } catch (Exception e2) {
                android.util.Log.e("ChdmanService", "Even minimal notification failed: " + e2.getMessage());
                stopSelf();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Work is driven externally; service is just for foreground/persistence
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serviceContext = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "CHDBOY Compression",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void ensureChannel() {
        ensureChannel(this);
    }

    public static void updateProgress(Context ctx, String message) {
        if (!hasNotificationPermission(ctx)) return;
        
        PendingIntent pendingIntent = createPendingIntent(ctx);
        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Compressing...")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif);
        } catch (SecurityException ignored) {}
    }

    public static void notifyDone(Context ctx, String message) {
        if (!hasNotificationPermission(ctx)) return;
        
        // Show completion notification with static icon
        PendingIntent pendingIntent = createPendingIntent(ctx);
        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID + 1, notif);
        } catch (SecurityException ignored) {}
    }
    
    public static void updateIdle(Context ctx, String message) {
        if (!hasNotificationPermission(ctx)) return;
        
        // Show idle notification with static icon
        PendingIntent pendingIntent = createPendingIntent(ctx);
        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif);
        } catch (SecurityException ignored) {}
    }
    
    // Public method to update notification message
    public static void updateProgressMessage(Context ctx, String message) {
        updateProgress(ctx, message);
    }

    private static boolean hasNotificationPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Pre-Android 13 doesn't require explicit permission
    }

    private static PendingIntent createPendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, 0, intent, flags);
    }
}
