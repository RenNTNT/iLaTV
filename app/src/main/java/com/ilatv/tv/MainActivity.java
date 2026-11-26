package com.ilatv.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * LibreTV-TV 3.0.0：纯 Java 版。
 * 不再嵌入 Node.js / .so 原生库，彻底避开 nodejs-mobile 在旧设备上的启动崩溃。
 */
public class MainActivity extends Activity {

    private static final int REQ_PLAYER = 1001;
    /** 手机后台推送的 APK 升级包，可绕过 WebView 轮询立即弹安装界面 */
    private static WeakReference<MainActivity> sActive;
    /** MainActivity 是否在前台：全屏播放时它在后台，起 Activity 需带 NEW_TASK */
    private boolean foreground = false;
    /** 本次回到首页是用户主动从直播点「影视点播」，不再自动进任何启动页 */
    private boolean skipAutoStartupOnce = false;
    /** 只有真正冷启动才自动进入用户设定的启动页 */
    private boolean coldStart = false;
    /** 自动进入启动页只执行一次，避免页面回调多次触发 */
    private boolean startupApplied = false;

    /**
     * 进程内唯一的服务实例。ExoPlayer 全屏播放时 MainActivity 处于后台，
     * 老电视内存紧张可能把它回收掉；服务放在静态引用里才不会中途断流。
     */
    private static TvServer sServer;

    private TvServer server;
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActive = new WeakReference<>(MainActivity.this);
        // 首页方向由 flavor 决定：电视/盒子横屏，手机版竖屏。
        // 播放器与直播页仍是横屏（Manifest 里写死），看视频全屏更合适。
        setRequestedOrientation(BuildConfig.IS_PHONE
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 升级安装完成后旧进程被系统杀掉，来不及在回调里删包；下次启动兜底清理残留
        try { ApkInstaller.cleanupStale(this); } catch (Exception ignored) {}

        // 0) 首次运行播种内置直播订阅源（原生直播模块从 Store 的 SharedPreferences 读取）
        try { Store.seedDefaults(this); } catch (Exception ignored) {}
        // 画面比例默认档由「自适应」改为「原始比例」，老配置只迁移一次
        try { Store.migrateAspectDefaults(this); } catch (Exception ignored) {}
        // 同步「应用模式」：备份导入 / 系统重置后，HOME alias 的启用状态仍按配置来
        try { LauncherMode.apply(this); } catch (Exception ignored) {}

        // 1) 启动内置 Java HTTP 服务（优先 9600，被占用则顺延）：放到后台线程，避免主线程做 socket bind
        final int[] usedPort = new int[]{TvServer.PORT};
        final TvServer[] holder = new TvServer[1];
        if (sServer != null) {
            // 服务还活着（例如播放器全屏时本 Activity 被系统回收后重建）
            holder[0] = sServer;
            usedPort[0] = sServer.getPort();
        } else {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int p = TvServer.PORT; p < TvServer.PORT + 10; p++) {
                        try {
                            TvServer srv = new TvServer(MainActivity.this, p);
                            srv.start(10000, false);
                            holder[0] = srv;
                            usedPort[0] = p;
                            return;
                        } catch (Exception ignored) {
                            // 端口被占用，试下一个
                        }
                    }
                    final String msg = TvServer.PORT + "-" + (TvServer.PORT + 9) + " 端口均被占用";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "服务启动失败：" + msg, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, "libretv-http");
            t.start();
            try {
                t.join(4000);
            } catch (InterruptedException ignored) {
            }
        }
        server = holder[0];
        sServer = server;

        // 2) 全屏 WebView 承载界面
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面 JS 就绪后再决定要不要自动进 IPTV / 影视分类
                applyStartupPage();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(url);
            }

            @Override
            @TargetApi(Build.VERSION_CODES.N)
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // 只处理主框架导航；图片 / hls 等子资源仍由 WebView 自己加载
                if (!req.isForMainFrame()) return false;
                return handleExternalUrl(req.getUrl().toString());
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        // 内置 ExoPlayer：网页把播放参数打包成 JSON 传过来，起全屏播放 Activity
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void play(final String cfgJson) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                            i.putExtra("cfg", cfgJson);
                            i.putExtra("port", usedPort[0]);
                            // 播放中推送：MainActivity 已在后台，必须 NEW_TASK 才能起新播放器；
                            // 此时用 startActivity（不收 result），进度由网页 saveProgress 兜底。
                            if (foreground) {
                                startActivityForResult(i, REQ_PLAYER);
                            } else {
                                // CLEAR_TOP：正在播放时推新片，重建播放器而不是叠两层
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(i);
                            }
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "启动播放器失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "exo");
        // 原生 IPTV 直播（从精简首页工程移植的 Media3 播放内核）：
        // play() 短按进入全屏播放页（单击屏幕唤出选台抽屉），setup() 长按进入完整选台/订阅管理页。
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void play() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent li = new Intent(MainActivity.this, LivePlayerActivity.class);
                            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(li);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "启动直播失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @JavascriptInterface
            public void setup() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent si = new Intent(MainActivity.this, LiveActivity.class);
                            si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(si);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "打开选台失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            /** 手机后台「直播推送」：带着频道名与线路直接起播，随后后台补齐完整频道列表 */
            @JavascriptInterface
            public void push(final String name, final String urlsJson) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ArrayList<String> urls = new ArrayList<>();
                            if (urlsJson != null && !urlsJson.isEmpty()) {
                                JSONArray a = new JSONArray(urlsJson);
                                for (int i = 0; i < a.length(); i++) {
                                    String u = a.optString(i, "");
                                    if (!u.isEmpty()) urls.add(u);
                                }
                            }
                            Intent i2 = new Intent(MainActivity.this, LivePlayerActivity.class);
                            i2.putExtra("pushName", name == null ? "" : name);
                            i2.putStringArrayListExtra("pushUrls", urls);
                            // 播放中推送：后台启动用 NEW_TASK；已在直播页则复用走 onNewIntent 换台
                            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(i2);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "推送直播失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "liveBridge");
        // 首页再按返回 → 退出应用
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void exit() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }
        }, "appHost");
        // 手机后台推送 APK 升级包：WebView 轮询到指令后调起系统安装界面
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void installApk(final String path, final String name, final long token) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ApkInstaller.install(MainActivity.this, path, name, token);
                    }
                });
            }
        }, "tvHost");
        // 只在真正冷启动（savedInstanceState == null）时自动进入用户设定的启动页。
        // 从直播功能菜单「影视点播」回来时带 skipAutoIptv，明确就是要看首页，不再跳回去。
        // 这些标记必须在 loadUrl 之前设好，避免 onPageFinished 抢先回调。
        coldStart = (savedInstanceState == null);
        skipAutoStartupOnce = getIntent() != null
                && getIntent().getBooleanExtra("skipAutoIptv", false);
        web.loadUrl("http://127.0.0.1:" + usedPort[0] + "/?tv=1");
    }

    /**
     * 手机后台一发推送就主动把指令喂给首页 JS。
     * 播放时 MainActivity 处于 stopped，WebView 的 setInterval 会被系统冻结，
     * 只靠 /api/poll 轮询就会「播放时推送没反应」；这里直接 evaluateJavascript 绕过定时器。
     * 返回 false 表示首页已被回收，调用方应保留 pendingPush / 走原生兜底。
     */
    public static boolean dispatchPush(String json) {
        final MainActivity a = sActive == null ? null : sActive.get();
        if (a == null || a.web == null || a.isFinishing()) return false;
        final String payload = org.json.JSONObject.quote(json);
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.isFinishing() || a.web == null) return;
                try {
                    a.web.evaluateJavascript(
                            "window.__nativePush&&window.__nativePush(JSON.parse(" + payload + "))", null);
                } catch (Exception ignored) {
                }
            }
        });
        return true;
    }

    /**
     * 首页 WebView 加载完成后，按「启动页」设置分派（冷启动只执行一次）：
     * iptv     → 起全屏直播 Activity，MainActivity 留在栈底，返回即回首页
     * category → 调网页 __openCategory()，直接显示影视分类页，返回栈里仍有首页
     * home     → 什么都不做
     * 从直播/分类页返回、切后台再回来、Activity 被回收重建都不会再被拽回去。
     */
    private void applyStartupPage() {
        if (!coldStart || startupApplied || isFinishing() || skipAutoStartupOnce) return;
        startupApplied = true;
        final String page = Store.startupPage(this);
        if ("home".equals(page)) return;
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || skipAutoStartupOnce) return;
                if ("iptv".equals(page)) {
                    startActivity(new Intent(MainActivity.this, LivePlayerActivity.class));
                } else if ("category".equals(page)) {
                    web.evaluateJavascript(
                            "window.__openCategory&&window.__openCategory()", null);
                }
            }
        }, 350);   // 让首页 WebView 先起加载，返回时不会是空白页
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask 复用本页时也要记住：这次是从直播主动回首页，别再跳回启动页
        if (intent != null && intent.getBooleanExtra("skipAutoIptv", false)) {
            skipAutoStartupOnce = true;
        }
        // 作为桌面应用时，按 Home 键系统会把 MAIN+HOME 送到本页：回首页是桌面基本行为。
        // 普通应用模式下不会带 CATEGORY_HOME，不触发。
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)
                && "home".equals(Store.launcherMode(this)) && web != null) {
            web.evaluateJavascript("window.__homePressed&&window.__homePressed()", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        // 从播放器返回首页时立刻补一次轮询，不用等 2 秒定时器
        if (web != null) {
            web.evaluateJavascript("window.pollPush&&window.pollPush()", null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 安装界面不在 forResult 链路里了：绝不能在这儿删包，否则安装器异步读文件时会解析失败
        if (requestCode != REQ_PLAYER || web == null) return;
        int index = 0;
        long pos = 0;
        boolean failed = false, ended = false;
        if (data != null) {
            index = data.getIntExtra("index", 0);
            pos = data.getLongExtra("positionMs", 0L);
            failed = data.getBooleanExtra("failed", false);
            ended = data.getBooleanExtra("ended", false);
        }
        final String js = "window.__onExoExit&&window.__onExoExit(" + index + "," + pos + ","
                + failed + "," + ended + ")";
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /**
     * 手机版首页底部的 GitHub 链接交给系统浏览器打开。
     * 不这么做的话 WebView 会就地导航到外部网页，而该页没有 window.__onBack，
     * 返回键会被原生层吞掉、用户卡在网页里出不来。
     * 只拦截「外部」http(s) 主框架导航；本机回环 / 局域网地址仍由 WebView 自己加载。
     * 电视版 BuildConfig.IS_PHONE=false，整段不生效，首页也根本没有可点击外链。
     */
    private boolean handleExternalUrl(String url) {
        if (!BuildConfig.IS_PHONE || url == null) return false;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = u.getHost();
        if (host == null || isLocalHost(host)) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception ignored) {
            return false;   // 没有浏览器时让 WebView 走默认行为，不至于点了没反应
        }
    }

    /** 内置服务地址：127.0.0.1 / localhost / 常见局域网段，不算外链 */
    private static boolean isLocalHost(String h) {
        String s = h.toLowerCase();
        if (s.equals("localhost") || s.equals("127.0.0.1") || s.equals("::1")
                || s.equals("[::1]")) return true;
        if (s.startsWith("192.168.") || s.startsWith("10.")) return true;
        if (s.startsWith("172.")) {
            String[] p = s.split("\\.");
            if (p.length > 1) {
                try {
                    int b = Integer.parseInt(p[1]);
                    if (b >= 16 && b <= 31) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 返回键交给网页处理（播放器/弹层自行拦截）
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            web.evaluateJavascript("window.__onBack&&window.__onBack()", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        // 只有用户主动退出才停服务；被系统回收（播放器在前台）时保留，避免播放中断流
        if (isFinishing()) {
            if (server != null) server.stop();
            sServer = null;
            if (sActive != null && sActive.get() == this) sActive = null;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
