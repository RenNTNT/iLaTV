package com.ilatv.tv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 手机后台推送 APK 升级包后的电视端处理：
 * 1) 调起系统安装界面（ACTION_VIEW / content://，兼容 API 21+）；
 * 2) 安装完成（或用户取消一段时间后）把 cache/push 里的安装包销毁，不留垃圾。
 *
 * <p>关键：绝不在 startActivity 的回调里立刻删包——不少电视 ROM 的安装界面
 * 会马上返回 RESULT_CANCELED，但系统安装器还在异步读文件，提前删掉就会报
 * 「解析软件包出现问题」。清理改由安装完成广播 / 下次启动兜底完成。
 */
public final class ApkInstaller {

    /** 用 applicationId 拼接：电视版 / 手机版两个包同时安装时广播不会串台 */
    public static final String ACTION_INSTALL_STATUS = BuildConfig.APPLICATION_ID + ".INSTALL_STATUS";

    private static final String PREF = "ilatv_apk_push";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_PENDING_TS = "pending_ts";
    /** 用户点了取消、安装器没给回调时，留出足够时间再兜底清理 */
    private static final long PENDING_KEEP_MS = 10 * 60 * 1000L;
    private static final long STALE_KEEP_MS = 2 * 60 * 60 * 1000L;

    /** 同一份推送可能经「原生即时弹窗」和「WebView 轮询」两条路到达，按 token 去重 */
    private static volatile long lastToken;

    private ApkInstaller() {}

    /** 用 Context 即可调起，MainActivity 不在前台（正在全屏播放）时同样能弹安装界面 */
    public static void install(Context c, String path, String name, long token) {
        if (c == null) return;
        if (token != 0) {
            if (lastToken == token) return;
            lastToken = token;
        }
        final Context app = c.getApplicationContext();
        final File f = (path == null || path.isEmpty()) ? ApkProvider.apkFile(app) : new File(path);
        if (!f.isFile()) {
            toastMain(app, "升级包不存在，请重新推送");
            return;
        }
        notePending(app, f.getAbsolutePath());
        final String label = name == null ? "iLaTV" : name;
        final Uri uri = Build.VERSION.SDK_INT >= 24
                ? ApkProvider.uriFor(app) : Uri.fromFile(f);
        // 调用方常常是内置 HTTP 服务的工作线程，Toast / startActivity 必须切到主线程
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    // 从后台 / Application Context 启动必须带 NEW_TASK；否则播放中推送会「没反应」
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                    if (Build.VERSION.SDK_INT >= 26) {
                        i.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, app.getPackageName());
                    }
                    // 部分 ROM 的 ContentResolver 不继承 Intent 的 grant，显式再授权一次
                    try {
                        ResolveInfo ri = app.getPackageManager()
                                .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
                        if (ri != null && ri.activityInfo != null && Build.VERSION.SDK_INT >= 24) {
                            app.grantUriPermission(ri.activityInfo.packageName, uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                    } catch (Exception ignored) {
                    }
                    // 不用 startActivityForResult：很多 ROM 会立刻回调 RESULT_CANCELED，
                    // 我们若在那儿 cleanup 就会把安装器正在读的 APK 删掉 → 解析失败
                    app.startActivity(i);
                    toastMain(app, "请在安装界面确认升级：" + label);
                } catch (final Exception e) {
                    // PackageInstaller 会话要读整个 APK，放到子线程，别卡主线程
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (!sessionInstall(app, f)) {
                                toastMain(app, "打开安装界面失败：" + e.getMessage());
                                cleanup(app);
                            }
                        }
                    }, "ilatv-apk-session").start();
                }
            }
        });
    }

    private static void toastMain(final Context c, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            }
        });
    }

    /** 部分电视 ROM 无 ACTION_VIEW 安装器：用 PackageInstaller 会话提交，系统同样会弹确认框 */
    private static boolean sessionInstall(Context c, File f) {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(p);
            session = pi.openSession(id);
            OutputStream out = session.openWrite("update.apk", 0, f.length());
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            session.fsync(out);
            out.close();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(c.getPackageName());
            PendingIntent pi2 = PendingIntent.getBroadcast(c, id, status, flags);
            session.commit(pi2.getIntentSender());
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void notePending(Context c, String path) {
        sp(c).edit().putString(KEY_PENDING, path)
                .putLong(KEY_PENDING_TS, System.currentTimeMillis()).apply();
    }

    /** 安装完成（成功或明确失败）时销毁安装包 */
    public static void cleanup(Context c) {
        try {
            File dir = new File(c.getCacheDir(), "push");
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    try { f.delete(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
        }
        try {
            sp(c).edit().remove(KEY_PENDING).remove(KEY_PENDING_TS).apply();
        } catch (Exception ignored) {}
    }

    /** 冷启动兜底：安装成功时旧进程被杀来不及回调，下次启动按时间清理，绝不误删刚推的包 */
    public static void cleanupStale(Context c) {
        try {
            SharedPreferences p = sp(c);
            boolean pending = !p.getString(KEY_PENDING, "").isEmpty();
            long ts = p.getLong(KEY_PENDING_TS, 0L);
            long keep = pending ? PENDING_KEEP_MS : STALE_KEEP_MS;
            File dir = new File(c.getCacheDir(), "push");
            File[] fs = dir.listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                long base = pending && ts > 0 ? ts : f.lastModified();
                if (now - base > keep) {
                    try { f.delete(); } catch (Exception ignored) {}
                }
            }
            if (pending && now - ts > PENDING_KEEP_MS) {
                p.edit().remove(KEY_PENDING).remove(KEY_PENDING_TS).apply();
            }
        } catch (Exception ignored) {
        }
    }
}
