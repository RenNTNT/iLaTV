package com.ilatv.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 应用模式：普通应用 / 桌面应用（HOME）。
 *
 * <p>Manifest 里声明的 {@code .HomeLauncher} 是一个 activity-alias，默认 enabled=false，
 * 带 MAIN + HOME + DEFAULT 过滤器。运行时用 setComponentEnabledSetting 动态启停它，
 * 就能让系统把 iLaTV 当作候选桌面，而不影响 MainActivity 原有的 LAUNCHER 图标。
 * activity-alias 的 launchMode / screenOrientation / theme 等属性全部沿用 targetActivity，
 * 所以桌面入口与正常入口行为完全一致。
 */
public final class LauncherMode {

    /**
     * 必须与 AndroidManifest.xml 里 activity-alias 的 name 一致。
     * 注意：组件类名由 Gradle namespace（com.ilatv.tv）决定，两版都是
     * com.ilatv.tv.HomeLauncher；不能用 applicationId 拼，否则手机版对不上。
     * 组件所属 APK 的 package 才是 applicationId，由 ComponentName(ctx, cls) 提供。
     */
    public static final String HOME_ALIAS = "com.ilatv.tv.HomeLauncher";

    private LauncherMode() {}

    /** 幂等：把组件状态同步成当前 Store 里的应用模式。开机、启动、后台保存后都会调用。 */
    public static void apply(Context c) {
        if (c == null) return;
        try {
            ComponentName cn = new ComponentName(c, HOME_ALIAS);
            // 桌面能力只给电视版：手机上误开会把系统桌面换掉，风险太大。
            // 手机版配置仍可保存 / 回显（全量复刻），但 alias 始终不启用。
            boolean home = !BuildConfig.IS_PHONE && "home".equals(Store.launcherMode(c));
            // DEFAULT 会回到清单里的 enabled="false"；ENABLED 则让系统看到这个 HOME 入口
            int state = home ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                             : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            c.getPackageManager().setComponentEnabledSetting(
                    cn, state, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
    }

    /** 当前 HOME alias 是否真的启用（用于后台状态回显） */
    public static boolean isHomeEnabled(Context c) {
        try {
            return c.getPackageManager().getComponentEnabledSetting(
                    new ComponentName(c, HOME_ALIAS)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 让电视弹出系统「选择默认桌面」对话框，用户选 iLaTV 并勾「始终」。
     * Android 没有公开 API 静默设置默认 HOME，必须由用户在系统界面确认。
     */
    public static void openHomeChooser(Context c) {
        if (c == null) return;
        if (BuildConfig.IS_PHONE) {
            toast(c, "桌面应用模式仅电视版生效，手机版不会替换系统桌面");
            return;
        }
        if (!"home".equals(Store.launcherMode(c))) {
            toast(c, "请先把应用模式设为「桌面应用」并保存");
            return;
        }
        apply(c);
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = Build.VERSION.SDK_INT >= 21
                            ? Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_HOME)
                            : new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                } catch (Exception e) {
                    toast(app, "打开桌面选择失败：" + e.getMessage());
                }
            }
        });
    }

    private static void toast(final Context c, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(c, msg, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
            }
        });
    }
}
