package com.ilatv.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启动：手机后台「开机自启动」开关打开时，开机后把 iLaTV 拉到前台。
 *
 * <p>桌面应用模式下系统本身就会启动 HOME（最可靠），这里再发一次 NEW_TASK，
 * singleTask 会复用已有实例、不会叠两层。普通应用模式在 Android 10+ 可能被系统
 * 的「后台启动 Activity」限制拦下，因此走 BootLaunchService 前台服务过渡；
 * 部分国产盒子还需在系统「自启动管理」里放行 iLaTV。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (c == null || !Store.autoBoot(c)) return;
        // 开机后重新同步一次组件状态，避免系统重置了动态 enabled
        LauncherMode.apply(c);
        final Context app = c.getApplicationContext();
        try {
            if (Build.VERSION.SDK_INT >= 29 && !"home".equals(Store.launcherMode(app))) {
                Intent s = new Intent(app, BootLaunchService.class);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(s);
                else app.startService(s);
            } else {
                startMain(app);
            }
        } catch (Exception e) {
            // 前台服务被系统拒绝时仍有最后一次直接启动机会
            try { startMain(app); } catch (Exception ignored) {}
        }
    }

    static void startMain(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(i);
    }
}
