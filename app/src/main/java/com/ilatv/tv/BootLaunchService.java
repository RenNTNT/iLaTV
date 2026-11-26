package com.ilatv.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Android 10+ 后台启动 Activity 限制下的过渡服务：亮一个一闪而过的通知，
 * 再启动首页，随后立刻销毁。只在「普通应用 + 开机自启」时用到，不常驻。
 */
public class BootLaunchService extends Service {

    private static final String CHANNEL = "ilatv_boot";
    private static final int NOTIFY_ID = 91;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try { BootReceiver.startMain(BootLaunchService.this); } catch (Exception ignored) {}
                try { stopForeground(true); } catch (Exception ignored) {}
                stopSelf();
            }
        }, 300);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "iLaTV 启动", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("开机自启动过渡通知");
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("iLaTV")
                .setContentText("正在启动…")
                .setAutoCancel(true)
                .setOngoing(false);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(1500);
        return b.build();
    }
}
