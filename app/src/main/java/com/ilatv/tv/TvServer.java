package com.ilatv.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;

/**
 * LibreTV-TV 内置服务器（纯 Java，无任何 native 依赖）。
 *
 * 路由：
 *  GET  /                    电视端主界面（assets/index.html）
 *  GET  /m/                  管理页：电视版 admin.html（浅色）/ 手机版 m.html（本机设置）
 *  GET  /api/status          {ok, version, lan}
 *  GET  /api/decoders        设备系统 MediaCodec 视频解码能力（硬解/软解清单）
 *  GET  /api/qr?text=...     二维码 PNG
 *  GET  /api/config          读取配置（源列表、片头/片尾秒数）
 *  POST /api/config          保存配置
 *  POST /api/search          聚合搜索 {wd}
 *  GET  /api/detail?k=&id=   详情 + 分集
 *  POST /api/push            手机推送指令 {action: "search"|"play"|"live"|"clearcache", ...}
 *  GET  /api/live-search     搜索直播源内频道（手机后台「直播推送」）
 *  POST /api/push-apk        上传 APK 升级包（二进制 body + X-File-Name 头）
 *  GET  /api/home-chooser    在电视上弹出系统「选择默认桌面」对话框
 *  GET  /api/poll            电视端轮询待执行指令
 *  GET  /api/clearcache      清除本地缓存（WebView HTTP 缓存等）+ 观看记录，返回 {ok, freed}
 *  GET  /api/history         读取观看记录 {ok, records:[...]}（电视/手机共享）
 *  POST /api/history         {action:save|del|clear, ...} 写入/删除/清空观看记录
 *  GET  /api/iptv?force=1    拉取解析 IPTV m3u 频道列表（带缓存）{ok, count, groups:[{name, channels}]}
 *  POST /api/iptv            {url} 保存直播订阅源地址并让缓存失效
 *  GET  /hls?url=...         HLS 代理（m3u8 改写 + ts/Range 流式转发）
 */
public class TvServer extends NanoHTTPD {

    /** 首选端口；电视版 9600 / 手机版 9601，被占用时 MainActivity 会顺延 */
    public static final int PORT = BuildConfig.BASE_PORT;
    private final int port;
    private static final String VERSION = "1.0-iptv2";

    /** 全局主题色（v72）：白名单之外一律回落 ember，避免注入非法 HTML 属性。 */
    private static final String[] THEMES = {"ember", "mint", "ocean", "violet", "rose"};
    private static final String DEFAULT_THEME = "ember";
    /** 明暗模式（v73）：dark = 历版深色底；light = 亮色底。白名单之外回落 dark。 */
    private static final String[] MODES = {"dark", "light"};
    private static final String DEFAULT_MODE = "dark";
    /** 边框 / 字体大小缩放（v74）：范围由 Store.clampScale 统一约束。 */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final Object cfgLock = new Object();
    private final Object pushLock = new Object();
    private final Object searchCacheLock = new Object();
    private volatile JSONObject pendingPush; // 手机推送给电视的指令
    private volatile JSONObject config;      // 源列表 + 跳过片头片尾
    /** 单进程单实例；供原生直播功能菜单改缩放时回写 config.json（ctx 是 applicationContext） */
    private static volatile TvServer instance;
    /** IPTV 频道列表缓存（从 m3u 解析），避免每次切分组都重新拉取 */
    private volatile JSONArray iptvChannelsCache;
    private volatile long iptvCacheTs = 0;
    private static final long IPTV_CACHE_TTL = 6 * 60 * 60 * 1000L; // 6 小时

    /** 搜索缓存：keyword|filterAdult -> {ts, result}，命中后秒回 */
    private final Map<String, Object[]> searchCache = new HashMap<>();
    private static final long SEARCH_CACHE_TTL = 90_000L;   // 90 秒
    private static final long SEARCH_BUDGET_MS = 2_800L;    // 全局搜索预算：2.8 秒（含聚合，HTTP 响应控制在 3 秒内）
    private static final int SEARCH_TIMEOUT_MS = 2_200;     // 单源超时：2.2 秒，高延迟源直接放弃

    /** 默认内置采集源（苹果CMS V10 接口），可在手机管理页增删 */
    private static final String DEFAULT_SOURCES = "[" +
            "{\"name\":\"红牛资源\",\"url\":\"https://hongniuzy2.com/api.php/provide/vod/from/hnm3u8\"}," +
            "{\"name\":\"360资源\",\"url\":\"https://360zyzz.com/api.php/provide/vod\"}," +
            "{\"name\":\"魔都资源\",\"url\":\"https://caiji.moduapi.cc/api.php/provide/vod\"}," +
            "{\"name\":\"非凡资源\",\"url\":\"http://api.ffzyapi.com/api.php/provide/vod\"}," +
            "{\"name\":\"百度资源\",\"url\":\"https://api.apibdzy.com/api.php/provide/vod\"}," +
            "{\"name\":\"无尽资源\",\"url\":\"https://api.wujinapi.com/api.php/provide/vod\"}," +
            "{\"name\":\"火狐资源\",\"url\":\"https://hhzyapi.com/api.php/provide/vod\"}" +
            "]";

    /** 默认 IPTV 直播订阅源（m3u），可在手机后台「直播源」里改成自己的订阅地址 */
    private static final String DEFAULT_IPTV_URL =
            "https://sub.ottiptv.cc/huyayqk.m3u";

    public TvServer(Context ctx) {
        this(ctx, PORT);
    }

    public TvServer(Context ctx, int port) {
        // 电视版绑全部网卡（局域网可访问后台页）；手机版只绑 127.0.0.1，
        // 仅供本机 WebView 取页面与 /api，局域网完全不可达 → 不扫码、不做遥控服务端
        super(BuildConfig.EXPOSE_LAN ? null : "127.0.0.1", port);
        this.port = port;
        this.ctx = ctx.getApplicationContext();
        this.config = loadConfig();
        instance = this;
    }

    /** 实际监听端口（端口被占用时会顺延） */
    public int getPort() {
        return port;
    }

    /** 当前主题（读 config.json；两个 flavor 共用逻辑，各自存一份）。 */
    private String currentTheme() {
        String t;
        synchronized (cfgLock) { t = config.optString("theme", DEFAULT_THEME); }
        for (String x : THEMES) if (x.equals(t)) return x;
        return DEFAULT_THEME;
    }
    /** 当前明暗模式（读 config.json；两个 flavor 共用逻辑，各自存一份）。 */
    private String currentMode() {
        String m;
        synchronized (cfgLock) { m = config.optString("mode", DEFAULT_MODE); }
        for (String x : MODES) if (x.equals(m)) return x;
        return DEFAULT_MODE;
    }
    /** 边框缩放百分比（config.json 为源，Store 里是给原生 UI 读的镜像）。 */
    private int currentLiveUiScale() {
        synchronized (cfgLock) { return Store.clampScale(config.optInt("liveUiScale", Store.SCALE_DEF)); }
    }
    private int currentEpgFontScale() {
        synchronized (cfgLock) { return Store.clampScale(config.optInt("epgFontScale", Store.SCALE_DEF)); }
    }

    /**
     * 原生直播功能菜单改「边框大小 / 字体大小」时调用（v75）。
     * 与 POST /api/config 完全等价：Store（原生 UI 读）与 config.json（后台回显 / 备份导出）
     * 双写，并即时推送给首页 WebView 的网页直播页，避免两边数值不一致。
     */
    public static void syncLiveScales(Context c, int ui, int epg) {
        ui = Store.clampScale(ui);
        epg = Store.clampScale(epg);
        Store.setLiveUiScale(c, ui);
        Store.setEpgFontScale(c, epg);
        TvServer srv = instance;
        if (srv != null) {
            synchronized (srv.cfgLock) {
                try {
                    srv.config.put("liveUiScale", ui);
                    srv.config.put("epgFontScale", epg);
                } catch (Exception ignored) {}
                srv.saveConfigLocked();
            }
        }
        try {
            JSONObject push = new JSONObject();
            push.put("action", "appearance");
            push.put("liveUiScale", ui);
            push.put("epgFontScale", epg);
            push.put("pushId", UUID.randomUUID().toString());
            MainActivity.dispatchPush(push.toString());
        } catch (Exception ignored) {}
    }

    /**
     * 主题色 / 明暗模式改了：立刻把「当前完整外观」喂给首页 WebView，
     * 不用等刷新或轮询。不写 pendingPush，避免重建后 /api/poll 重复处理。
     */
    private void notifyAppearance() {
        try {
            JSONObject push = new JSONObject();
            push.put("action", "appearance");
            push.put("theme", currentTheme());
            push.put("mode", currentMode());
            push.put("liveUiScale", currentLiveUiScale());
            push.put("epgFontScale", currentEpgFontScale());
            push.put("pushId", UUID.randomUUID().toString());
            MainActivity.dispatchPush(push.toString());
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- 配置

    private File configFile() {
        return new File(ctx.getFilesDir(), "config.json");
    }

    private JSONObject loadConfig() {
        try {
            File f = configFile();
            if (f.exists()) {
                byte[] buf = readAll(new FileInputStream(f));
                JSONObject o = new JSONObject(new String(buf, "UTF-8"));
                if (!o.has("sources")) o.put("sources", new JSONArray(DEFAULT_SOURCES));
                if (!o.has("playEngine")) o.put("playEngine", "auto");
                if (!o.has("iptvUrl")) o.put("iptvUrl", DEFAULT_IPTV_URL);
                else if (Store.isLegacyLiveUrl(o.optString("iptvUrl", "")))
                    o.put("iptvUrl", DEFAULT_IPTV_URL);   // 历版内置源统一自动升级
                return o;
            }
        } catch (Exception ignored) {
        }
        JSONObject o = new JSONObject();
        try {
            o.put("sources", new JSONArray(DEFAULT_SOURCES));
            o.put("skipIntro", 0);   // 跳过片头秒数
            o.put("skipOutro", 0);   // 跳过片尾秒数
            o.put("filterAdult", true);
            o.put("playEngine", "auto");  // auto=自动选择 / exo=内置 ExoPlayer / sysdec=系统解码
            o.put("iptvUrl", DEFAULT_IPTV_URL);  // IPTV 直播 m3u 订阅地址
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void saveConfigLocked() {
        try {
            File f = configFile();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(config.toString().getBytes("UTF-8"));
            fos.getFD().sync();
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private JSONArray sourcesArr() {
        try {
            return config.optJSONArray("sources");
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONObject findSource(String key) {
        JSONArray arr = sourcesArr();
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && ("" + i).equals(key)) return s;
        }
        return null;
    }

    // ---------------------------------------------------------------- 观看记录（服务端共享，电视/手机两端同步）
    // 存到 filesDir/history.json，结构为 JSON 数组，每条含 key/index/time/ts/name/pic 等字段。
    // key 与前端 progressKey 一致：mergeKey 用 "lp_m_"+mergeKey，否则 "lp_"+sourceKey+"_"+vodId。

    private final Object historyLock = new Object();

    private File historyFile() {
        return new File(ctx.getFilesDir(), "history.json");
    }

    /** 读取全部观看记录；文件不存在/损坏时返回空数组 */
    private JSONArray loadHistory() {
        synchronized (historyLock) {
            return loadHistoryUnsafe();
        }
    }

    private JSONArray loadHistoryUnsafe() {
        try {
            File f = historyFile();
            if (f.exists()) {
                byte[] buf = readAll(new FileInputStream(f));
                JSONArray a = new JSONArray(new String(buf, "UTF-8"));
                return a;
            }
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }

    private void saveHistoryUnsafe(JSONArray arr) {
        try {
            File f = historyFile();
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.getFD().sync();
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * GET  /api/history                -> {ok:true, records:[...]}
     * POST /api/history {action:...}
     *   save  {record:{...}}  按 key 覆盖（存在则更新，不存在则插入）
     *   del   {key:"..."}     按 key 删除
     *   clear                 清空全部
     */
    private Response apiHistory(IHTTPSession session, Method method) {
        if (method == Method.GET) {
            JSONArray a = loadHistory();
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("records", a);
            } catch (JSONException ignored) {
            }
            return json(o);
        }
        JSONObject body = bodyJson(session);
        synchronized (historyLock) {
            JSONArray arr = loadHistoryUnsafe();
            String action = body.optString("action", "");
            if ("clear".equals(action)) {
                arr = new JSONArray();
                saveHistoryUnsafe(arr);
            } else if ("del".equals(action)) {
                String key = body.optString("key", "");
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r != null && key.equals(r.optString("key", ""))) continue;
                    next.put(r);
                }
                saveHistoryUnsafe(next);
                arr = next;
            } else if ("save".equals(action)) {
                JSONObject rec = body.optJSONObject("record");
                if (rec == null || !rec.has("key")) return jsonError("记录缺少 key");
                String key = rec.optString("key", "");
                boolean replaced = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r != null && key.equals(r.optString("key", ""))) {
                        try { arr.put(i, rec); } catch (JSONException ignored) {}
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) arr.put(rec);
                saveHistoryUnsafe(arr);
            } else {
                return jsonError("未知的 action");
            }
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("count", arr.length());
            } catch (JSONException ignored) {
            }
            return json(o);
        }
    }

    // ---------------------------------------------------------------- SMB 网盘

    /**
     * GET  /api/smb             列出已添加网盘（不回传密码）
     * POST /api/smb             新增 / 编辑 / 删除，配置写进 config.json
     */
    private Response apiSmb(IHTTPSession session, Method method) {
        if (method == Method.GET) {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("shares", SmbStore.publicShares(config));
                return json(o);
            } catch (JSONException e) {
                return jsonError("读取网盘列表失败");
            }
        }
        JSONObject body = bodyJson(session);
        String action = body.optString("action", "");
        synchronized (cfgLock) {
            if ("del".equals(action)) {
                JSONObject r = SmbStore.deleteShare(config, body.optString("id", ""));
                if (r.optBoolean("ok", false)) saveConfigLocked();
                return json(r);
            }
            if ("add".equals(action) || "update".equals(action)) {
                JSONObject input = body.optJSONObject("share");
                if (input == null) input = body;
                try {
                    if ("update".equals(action)) input.put("id", body.optString("id", ""));
                    else input.remove("id");
                } catch (JSONException ignored) {
                }
                JSONObject r = SmbStore.saveShare(config, input);
                if (r.optBoolean("ok", false)) saveConfigLocked();
                return json(r);
            }
            return jsonError("未知操作");
        }
    }

    /** GET /api/smb/browse?id=xx&path=/电影 → 目录里的文件夹与视频 */
    private Response apiSmbBrowse(IHTTPSession session) {
        return json(SmbStore.browse(config, param(session, "id"), param(session, "path")));
    }

    /** GET /api/smb/search?wd=片名 → 遍历所有网盘按文件名匹配视频（轻量实时搜索） */
    private Response apiSmbSearch(IHTTPSession session) {
        return json(SmbStore.search(config, param(session, "wd")));
    }

    /**
     * GET /smb?id=xx&path=/电影/a.mp4 → 把 SMB 文件代理成本机 HTTP 流。
     * 支持 Range（206 Partial Content）：ExoPlayer 拖动进度条靠它 seek，
     * 用 SmbFileInputStream.skip() 在远端直接定位，不会把前面的内容白读一遍。
     */
    private Response smbProxy(IHTTPSession session) {
        String id = param(session, "id");
        String path = param(session, "path");
        if (id == null || id.isEmpty() || path == null || path.isEmpty()) return notFound();

        SmbStore.Stream st;
        try {
            st = SmbStore.open(config, id, path);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8", "smb open error: " + e.getMessage());
        }

        long total = st.length;
        long start = 0, end = total - 1;
        boolean partial = false;
        String range = header(session, "Range");
        if (range != null && range.length() > 6 && range.regionMatches(true, 0, "bytes=", 0, 6)) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String s = spec.substring(0, dash).trim();
                    String e = spec.substring(dash + 1).trim();
                    if (!s.isEmpty()) {
                        start = Long.parseLong(s);
                        if (!e.isEmpty()) end = Math.min(Long.parseLong(e), total - 1);
                    } else if (!e.isEmpty()) {                 // bytes=-500：取末尾 N 字节
                        long n = Long.parseLong(e);
                        start = Math.max(0, total - n);
                    }
                    if (start < 0) start = 0;
                    if (end < start) end = start;
                    partial = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (total <= 0 || start >= total || end >= total) {
            try { st.in.close(); } catch (Exception ignored) {}
            Response r = newFixedLengthResponse(
                    Response.Status.lookup(416) == null
                            ? Response.Status.INTERNAL_ERROR : Response.Status.lookup(416),
                    "text/plain; charset=utf-8", "range not satisfiable");
            r.addHeader("Content-Range", "bytes */" + total);
            return r;
        }
        end = Math.min(end, total - 1);
        long len = end - start + 1;

        try {
            if (start > 0) skipFully(st.in, start);
            Response r = newFixedLengthResponse(
                    partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                    st.mime, st.in, len);
            r.addHeader("Accept-Ranges", "bytes");
            if (partial) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
            r.addHeader("Cache-Control", "no-store");
            r.addHeader("Access-Control-Allow-Origin", "*");
            return r;
        } catch (Exception e) {
            try { st.in.close(); } catch (Exception ignored) {}
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8", "smb read error: " + e.getMessage());
        }
    }

    private static void skipFully(InputStream in, long n) throws java.io.IOException {
        long left = n;
        while (left > 0) {
            long k = in.skip(left);
            if (k <= 0) {
                if (in.read() < 0) throw new java.io.IOException("unexpected EOF while seeking");
                k = 1;
            }
            left -= k;
        }
    }

    // ---------------------------------------------------------------- 路由

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        try {
            if (uri.equals("/api/search")) return apiSearch(session);
            if (uri.equals("/api/detail")) return apiDetail(session);
            if (uri.equals("/api/config")) return apiConfig(session, method);
            if (uri.equals("/api/history")) return apiHistory(session, method);
            if (uri.equals("/api/iptv")) return apiIptv(session, method);
            if (uri.equals("/api/live-subs")) return apiLiveSubs(session, method);
            if (uri.equals("/api/live-groups")) return apiLiveGroups(session, method);
            if (uri.equals("/api/tvbox")) return apiTvbox(session);
            if (uri.equals("/api/status")) return apiStatus();
            if (uri.equals("/api/decoders")) return apiDecoders();
            if (uri.equals("/api/qr")) return apiQr(session);
            if (uri.equals("/api/push")) return apiPush(session);
            if (uri.equals("/api/poll")) return apiPoll();
            if (uri.equals("/api/clearcache")) return apiClearCache();
            if (uri.equals("/api/backup")) return apiBackup(session, method);
            if (uri.equals("/api/smb")) return apiSmb(session, method);
            if (uri.equals("/api/smb/browse")) return apiSmbBrowse(session);
            if (uri.equals("/api/smb/search")) return apiSmbSearch(session);
            if (uri.equals("/api/live-search")) return apiLiveSearch(session);
            if (uri.equals("/api/push-apk")) return apiPushApk(session);
            if (uri.equals("/api/home-chooser")) return apiHomeChooser();
            if (uri.equals("/smb") || uri.startsWith("/smb/")) return smbProxy(session);
            if (uri.equals("/hls") || uri.startsWith("/hls/")) return hlsProxy(session);
            if (uri.equals("/flv")) return flvProxy(session);
            if (uri.equals("/probe")) return probeStream(session);
            if (uri.equals("/")) return serveRoot(session);
            return serveAsset(uri);
        } catch (Exception e) {
            return jsonError("服务器内部错误: " + e.getMessage());
        }
    }

    private Response json(JSONObject o) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString());
    }

    private Response jsonError(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg);
        } catch (JSONException ignored) {
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString());
    }

    /**
     * 读取 JSON 请求体。
     * 注意：不要用 session.parseBody()——它按 Content-Type 的 charset 解码，缺省是 US-ASCII，
     * 中文关键词会被解成乱码导致所有源都搜不到。这里直接按 UTF-8 读原始字节流。
     */
    private JSONObject bodyJson(IHTTPSession session) {
        try {
            // 必须按 Content-Length 精确读取：keep-alive 连接读完 body 不会 EOF，多读会阻塞
            int len = -1;
            String cl = header(session, "content-length");
            if (cl != null) {
                try { len = Integer.parseInt(cl.trim()); } catch (NumberFormatException ignored) { len = -1; }
            }
            if (len > 0 && len < 8 * 1024 * 1024) {
                InputStream is = session.getInputStream();
                byte[] data = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = is.read(data, off, len - off);
                    if (n <= 0) break;
                    off += n;
                }
                String body = new String(data, 0, off, "UTF-8").trim();
                if (body.isEmpty()) return new JSONObject();
                return new JSONObject(body);
            }
        } catch (Exception ignored) {
        }
        // 兜底：标准 NanoHTTPD 解析（仅 ASCII 场景可用）
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            String body = files.get("postData");
            if (body == null || body.isEmpty()) return new JSONObject();
            return new JSONObject(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String param(IHTTPSession session, String key) {
        try {
            List<String> v = session.getParameters().get(key);
            if (v != null && !v.isEmpty()) return v.get(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------- 静态资源

    /**
     * 根路径：手机直接访问 http://电视IP:9600 就打开管理页（无需 /m）。
     * 电视端 App 加载 /?tv=1，或本机 127.0.0.1 访问，仍显示电视主界面。
     */
    private Response serveRoot(IHTTPSession session) {
        String tv = param(session, "tv");
        if (tv != null && tv.equals("1")) return asset("index.html", "text/html");
        // 兜底：本机访问显示电视界面；局域网访问显示手机管理页
        String host = "";
        try {
            String h = session.getHeaders().get("host");
            if (h != null) host = h;
        } catch (Exception ignored) {
        }
        if (host.startsWith("127.0.0.1") || host.startsWith("localhost")) {
            return asset("index.html", "text/html");
        }
        return asset(adminAsset(), "text/html");
    }

    /**
     * 后台页文件：电视版用 tv flavor 专属的 admin.html（浅色列表风格），
     * 手机版用 phone flavor 专属的 m.html（本机设置页，无 TV 推送功能）。
     * 两个文件放在各自 flavor 源集、互不重名，AGP 不会报 Duplicate resources；
     * 改手机版设置页不会碰电视版后台，两版资源与界面彼此独立。
     */
    private static String adminAsset() {
        return BuildConfig.IS_PHONE ? "m.html" : "admin.html";
    }

    private Response serveAsset(String uri) {
        if (uri.equals("/") || uri.equals("/index.html")) return asset("index.html", "text/html");
        if (uri.equals("/m") || uri.equals("/m/")) return asset(adminAsset(), "text/html");
        // 电视版把直接访问 /m.html 也指到新后台，避免看到未使用的旧文件
        if (uri.equals("/m.html") && !BuildConfig.IS_PHONE)
            return asset("admin.html", "text/html");
        // 简单防目录穿越
        if (uri.contains("..")) return notFound();
        String name = uri.startsWith("/") ? uri.substring(1) : uri;
        String mime = guessMime(name);
        return asset(name, mime);
    }

    private Response asset(String name, String mime) {
        try {
            InputStream is = ctx.getAssets().open(name);
            byte[] data = readAll(is);
            // 三份页面（首页 WebView / 电视版后台 / 手机版设置）首帧就带上主题，
            // 由服务端注入 <html data-theme="...">：不用额外请求、不等 JS，刷新不闪色。
            // 只处理文本 HTML，其它静态资源（js / png）原样返回。
            if ("text/html".equals(mime) && (name.equals("index.html")
                    || name.equals("admin.html") || name.equals("m.html"))) {
                String html = new String(data, "UTF-8");
                String themed = html.replaceFirst("(?i)<html([^>]*)>",
                        "<html$1 data-theme=\"" + currentTheme() + "\" data-mode=\"" + currentMode() + "\">");
                if (!themed.equals(html)) data = themed.getBytes("UTF-8");
            }
            Response r = newFixedLengthResponse(Response.Status.OK, mime, new ByteArrayInputStream(data), data.length);
            return r;
        } catch (Exception e) {
            return notFound();
        }
    }

    private Response notFound() {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404");
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------- status / qr

    private Response apiStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("version", VERSION);
            o.put("lan", "http://" + getLanIp() + ":" + port);
            o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("isPhone", BuildConfig.IS_PHONE);
            o.put("exposeLan", BuildConfig.EXPOSE_LAN);
            o.put("appId", BuildConfig.APPLICATION_ID);
        } catch (JSONException ignored) {
        }
        return json(o);
    }

    /**
     * GET /api/decoders —— 列出本机系统 MediaCodec 提供的视频解码能力。
     * 供网页后台「播放设置」展示：哪些编码能硬解、哪些只有软解，
     * 方便判断某个片源播不了时该不该切「系统解码」。
     */
    private Response apiDecoders() {
        JSONObject o = new JSONObject();
        JSONArray list = new JSONArray();
        int hw = 0, sw = 0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.media.MediaCodecList mc =
                        new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                for (android.media.MediaCodecInfo info : mc.getCodecInfos()) {
                    if (info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) {
                        if (!type.startsWith("video/")) continue;
                        boolean hardware = false;
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                hardware = info.isHardwareAccelerated();
                            } else {
                                String n = info.getName().toLowerCase(java.util.Locale.US);
                                hardware = !(n.contains("software") || n.contains(".sw.")
                                        || n.contains("ffmpeg") || n.contains("omx.google."));
                            }
                        } catch (Exception ignored) {
                        }
                        String key = type + "|" + (hardware ? "hw" : "sw");
                        if (!seen.add(key)) continue;
                        JSONObject d = new JSONObject();
                        d.put("mime", type);
                        d.put("codec", info.getName());
                        d.put("hardware", hardware);
                        list.put(d);
                        if (hardware) hw++; else sw++;
                    }
                }
            }
            o.put("ok", true);
            o.put("decoders", list);
            o.put("hardwareCount", hw);
            o.put("softwareCount", sw);
            o.put("decodeMode", Store.vodDecodeMode(ctx));      // 兼容旧后台
            o.put("vodDecodeMode", Store.vodDecodeMode(ctx));
            o.put("liveDecodeMode", Store.liveDecodeMode(ctx));
            o.put("vodAspect", Store.vodAspect(ctx));
            o.put("liveAspect", Store.liveAspect(ctx));
            o.put("channelReverse", Store.channelReverse(ctx));
            o.put("launcherMode", Store.launcherMode(ctx));
            o.put("autoBoot", Store.autoBoot(ctx));
            o.put("homeAliasEnabled", LauncherMode.isHomeEnabled(ctx));
        } catch (Exception e) {
            try {
                o.put("ok", false);
                o.put("error", String.valueOf(e.getMessage()));
            } catch (JSONException ignored) {
            }
        }
        return json(o);
    }

    /** 取局域网 IPv4（排除回环/蜂窝/USB 虚拟网卡，优先 wlan）。 */
    public static String getLanIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String n = ni.getName().toLowerCase(Locale.US);
                if (n.contains("rmnet") || n.contains("ccmni") || n.contains("usb") || n.contains("dummy")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) {
                        String ip = a.getHostAddress();
                        if (n.startsWith("wlan")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "127.0.0.1";
    }

    private Response apiQr(IHTTPSession session) {
        String text = param(session, "text");
        if (TextUtils.isEmpty(text)) return notFound();
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480, hints);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            writePng(m, bos);
            byte[] png = bos.toByteArray();
            Response r = newFixedLengthResponse(Response.Status.OK, "image/png",
                    new ByteArrayInputStream(png), png.length);
            r.addHeader("Cache-Control", "no-store");
            return r;
        } catch (Exception e) {
            return jsonError("二维码生成失败");
        }
    }

    /** 无 android.graphics 依赖的 PNG 编码（二维码只有黑白两色，走调色板 PNG 最简单） */
    private static void writePng(BitMatrix m, OutputStream os) throws IOException {
        int w = m.getWidth(), h = m.getHeight();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0); // filter: None
            for (int x = 0; x < w; x++) {
                raw.write(m.get(x, y) ? 0 : 1); // 索引 0=黑 1=白
            }
        }
        byte[] idat = deflate(raw.toByteArray());

        DataOut o = new DataOut(os);
        o.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        // IHDR: 宽 高 位深8 颜色类型3(调色板) 压缩0 滤波0 隔行0
        o.chunk("IHDR", new DataOut().w32(w).w32(h).w8(8).w8(3).w8(0).w8(0).w8(0).bytes());
        o.chunk("PLTE", new byte[]{0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        o.chunk("IDAT", idat);
        o.chunk("IEND", new byte[0]);
        os.flush();
    }

    private static byte[] deflate(byte[] data) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        return bos.toByteArray();
    }

    /** PNG 块写入小工具（自带 CRC32） */
    private static class DataOut {
        private final OutputStream os;
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        DataOut() {
            this.os = null;
        }

        DataOut(OutputStream os) {
            this.os = os;
        }

        DataOut w32(int v) throws IOException {
            bos.write((v >>> 24) & 0xFF);
            bos.write((v >>> 16) & 0xFF);
            bos.write((v >>> 8) & 0xFF);
            bos.write(v & 0xFF);
            return this;
        }

        DataOut w8(int v) throws IOException {
            bos.write(v);
            return this;
        }

        byte[] bytes() {
            return bos.toByteArray();
        }

        void write(byte[] b) throws IOException {
            os.write(b);
        }

        void chunk(String type, byte[] data) throws IOException {
            byte[] t = type.getBytes("US-ASCII");
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(t);
            crc.update(data);
            writeLen(os, data.length);
            os.write(t);
            os.write(data);
            writeLen(os, (int) crc.getValue());
        }

        private static void writeLen(OutputStream os, int v) throws IOException {
            os.write((v >>> 24) & 0xFF);
            os.write((v >>> 16) & 0xFF);
            os.write((v >>> 8) & 0xFF);
            os.write(v & 0xFF);
        }
    }

    // ---------------------------------------------------------------- 配置接口

    /**
     * GET  /api/backup  导出本机全部数据（SharedPreferences 全表 + config.json）
     * POST /api/backup  导入备份，用于换包名后把旧版数据整体搬过来
     *
     * 换 applicationId 后 Android 会当成另一个 App，旧包私有目录读不到，
     * 所以由用户先在旧版导出文件、装新版后导入，做到零丢失。
     */
    private Response apiBackup(IHTTPSession session, Method method) {
        if (method == Method.GET) {
            try {
                JSONObject root = new JSONObject();
                root.put("ok", true);
                root.put("v", 1);
                root.put("app", "iLaTV");
                root.put("fromPackage", ctx.getPackageName());
                root.put("time", System.currentTimeMillis());
                synchronized (cfgLock) {
                    root.put("config", new JSONObject(config.toString()));
                }
                JSONObject prefs = new JSONObject();
                Map<String, ?> all = ctx.getSharedPreferences(
                        "libretv_java", Context.MODE_PRIVATE).getAll();
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    prefs.put(e.getKey(), e.getValue());
                }
                root.put("prefs", prefs);
                return json(root);
            } catch (Exception e) {
                return jsonError("导出失败: " + e.getMessage());
            }
        }
        JSONObject body = bodyJson(session);
        if (body == null) return jsonError("备份内容为空");
        JSONObject prefs = body.optJSONObject("prefs");
        JSONObject cfg = body.optJSONObject("config");
        if (prefs == null && cfg == null) return jsonError("不是有效的 iLaTV 备份文件");
        int restored = 0;
        boolean liveUpgraded = false;
        // 旧版备份（还没有比例迁移标记）里显式存的「自适应」其实是旧默认档，
        // 导入时一并归到新的默认「原始比例」，避免恢复完数据又变回自适应
        boolean oldAspectBackup = prefs != null
                && !prefs.has("aspect_default_migrated_v1");
        if (prefs != null) {
            SharedPreferences sp = ctx.getSharedPreferences(
                    "libretv_java", Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            java.util.Iterator<String> it = prefs.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = prefs.opt(k);
                if (v instanceof String) {
                    String sv = (String) v;
                    if (oldAspectBackup && "auto".equals(sv)
                            && ("vod_aspect_v1".equals(k) || "live_aspect_v1".equals(k)))
                        sv = Store.DEFAULT_ASPECT;
                    // 旧版备份里若还存着旧内置直播源，导入时原地升级，
                    // 避免换完包名、恢复完数据却仍播着失效的旧地址
                    if ("live_subs".equals(k) || "live_channels_cache_subs_v1".equals(k)) {
                        String up = sv;
                        String[] olds = Store.legacyLiveUrls();
                        for (int li = 0; li < olds.length; li++)
                            up = up.replace(olds[li], Store.DEFAULT_LIVE_URL);
                        if (!up.equals(sv)) { sv = up; liveUpgraded = true; }
                    }
                    ed.putString(k, sv);
                }
                else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) ed.putInt(k, (Integer) v);
                else if (v instanceof Long) ed.putLong(k, (Long) v);
                else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
                else if (v instanceof Float) ed.putFloat(k, (Float) v);
                else continue;
                restored++;
            }
            ed.commit();
        }
        boolean cfgRestored = false;
        if (cfg != null) {
            String iu = cfg.optString("iptvUrl", "");
            if (Store.isLegacyLiveUrl(iu)) {
                try {
                    cfg.put("iptvUrl", DEFAULT_IPTV_URL);
                    liveUpgraded = true;
                } catch (JSONException ignored) {
                }
            }
            synchronized (cfgLock) {
                config = cfg;
                saveConfigLocked();
                iptvCacheTs = 0;
            }
            cfgRestored = true;
        }
        if (liveUpgraded) {
            Store.clearLiveCache(ctx);   // 旧频道缓存作废，下次进直播立即拉新源
            iptvCacheTs = 0;
        }
        WebAdmin.markDirty();
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("restored", restored);
            out.put("config", cfgRestored);
            out.put("liveUpgraded", liveUpgraded);
        } catch (JSONException ignored) {
        }
        return json(out);
    }

    /** 对外回显的配置副本：SMB 网盘里存着密码，改由 /api/smb 单独返回脱敏列表 */
    private JSONObject publicConfig() {
        try {
            JSONObject o = new JSONObject(config.toString());
            o.remove("smbShares");
            return o;
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try { o.put("sources", new JSONArray()); } catch (JSONException ignored) {}
            return o;
        }
    }

    private Response apiConfig(IHTTPSession session, Method method) {
        synchronized (cfgLock) {
            if (method == Method.GET) {
                // 启动页存在 SharedPreferences（MainActivity / IPTV 功能菜单直接读），
                // 这里拼进返回值供网页后台回显，避免两份配置不一致。
                JSONObject out = publicConfig();
                try {
                    out.put("startupPage", Store.startupPage(ctx));
                    out.put("decodeMode", Store.vodDecodeMode(ctx));      // 兼容旧后台
                    out.put("vodDecodeMode", Store.vodDecodeMode(ctx));
                    out.put("liveDecodeMode", Store.liveDecodeMode(ctx));
                    out.put("vodAspect", Store.vodAspect(ctx));
                    out.put("liveAspect", Store.liveAspect(ctx));
                    out.put("channelReverse", Store.channelReverse(ctx));
                    out.put("launcherMode", Store.launcherMode(ctx));
                    out.put("autoBoot", Store.autoBoot(ctx));
                    out.put("homeAliasEnabled", LauncherMode.isHomeEnabled(ctx));
                    out.put("theme", currentTheme());   // 全局主题色（v72）
                    out.put("mode", currentMode());     // 明暗模式（v73）
                    out.put("liveUiScale", currentLiveUiScale());     // 边框缩放（v74）
                    out.put("epgFontScale", currentEpgFontScale());   // 字体大小（v74）
                    // 旧配置里的 system（系统播放器）已移除，回显时归一化为自动选择
                    String pe = config.optString("playEngine", "auto");
                    if (!"auto".equals(pe) && !"exo".equals(pe) && !"sysdec".equals(pe)) {
                        out.put("playEngine", "auto");
                    }
                } catch (Exception e) {
                    out = publicConfig();
                }
                return json(out);
            }
            JSONObject body = bodyJson(session);
            try {
                if (body.has("startupPage")) {
                    Store.setStartupPage(ctx, body.optString("startupPage", "home"));
                }
                if (body.has("sources")) {
                    JSONArray arr = body.getJSONArray("sources");
                    // 校验每一项
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.getJSONObject(i);
                        String name = s.optString("name", "").trim();
                        String url = s.optString("url", "").trim().replaceAll("/+$", "");
                        if (name.isEmpty()) return jsonError("第 " + (i + 1) + " 个源缺少名称");
                        if (!url.startsWith("http://") && !url.startsWith("https://"))
                            return jsonError("第 " + (i + 1) + " 个源的地址必须以 http(s):// 开头");
                        s.put("name", name);
                        s.put("url", url);
                    }
                    config.put("sources", arr);
                }
                if (body.has("skipIntro")) config.put("skipIntro", Math.max(0, body.optInt("skipIntro", 0)));
                if (body.has("skipOutro")) config.put("skipOutro", Math.max(0, body.optInt("skipOutro", 0)));
                if (body.has("filterAdult")) config.put("filterAdult", body.optBoolean("filterAdult", true));
                if (body.has("playEngine")) {
                    String pe = body.optString("playEngine", "auto");
                    if (!pe.equals("auto") && !pe.equals("exo") && !pe.equals("sysdec")) {
                        pe = "auto";   // 去掉的 system / web 等旧值一律回落到自动选择
                    }
                    config.put("playEngine", pe);
                    // 点播三档直接同步给原点播播放器（读 Store），仍全程留在 app 内播放
                    Store.setVodDecodeMode(ctx, pe);
                }
                if (body.has("liveDecodeMode")) {
                    // 直播解码独立档位：网页后台与电视端「功能菜单 → 解码方式」共用
                    // 同一份 Store 配置，两边改完即时同步，互不影响点播。
                    Store.setLiveDecodeMode(ctx, body.optString("liveDecodeMode", "auto"));
                }
                if (body.has("vodAspect")) {
                    // 点播画面比例：网页后台与点播播放器「播放菜单 → 画面比例」共用
                    Store.setVodAspect(ctx, body.optString("vodAspect", Store.DEFAULT_ASPECT));
                }
                if (body.has("liveAspect")) {
                    // 直播画面比例：网页后台与电视端「功能菜单 → 画面比例」共用，
                    // 独立 key，不与点播互相覆盖
                    Store.setLiveAspect(ctx, body.optString("liveAspect", Store.DEFAULT_ASPECT));
                }
                if (body.has("channelReverse")) {
                    // 直播换台方向反转：网页后台与电视端「功能菜单 → 换台反转」共用
                    Store.setChannelReverse(ctx, body.optBoolean("channelReverse", false));
                }
                if (body.has("launcherMode")) {
                    // 应用模式：保存后立刻启停 HOME alias，不用重启 App
                    Store.setLauncherMode(ctx, body.optString("launcherMode", "normal"));
                    LauncherMode.apply(ctx);
                }
                if (body.has("autoBoot")) {
                    Store.setAutoBoot(ctx, body.optBoolean("autoBoot", false));
                }
                boolean appearanceChanged = false;
                if (body.has("theme")) {
                    String t = body.optString("theme", DEFAULT_THEME).trim();
                    boolean valid = false;
                    for (String x : THEMES) if (x.equals(t)) { valid = true; break; }
                    String applied = valid ? t : DEFAULT_THEME;
                    if (!applied.equals(currentTheme())) appearanceChanged = true;
                    config.put("theme", applied);
                }
                if (body.has("mode")) {
                    String m = body.optString("mode", DEFAULT_MODE).trim();
                    boolean valid = false;
                    for (String x : MODES) if (x.equals(m)) { valid = true; break; }
                    String applied = valid ? m : DEFAULT_MODE;
                    if (!applied.equals(currentMode())) appearanceChanged = true;
                    config.put("mode", applied);
                }
                if (body.has("liveUiScale")) {
                    int v = Store.clampScale(body.optInt("liveUiScale", Store.SCALE_DEF));
                    if (v != currentLiveUiScale()) appearanceChanged = true;
                    config.put("liveUiScale", v);
                    Store.setLiveUiScale(ctx, v);      // 原生选台页 / 抽屉读这个镜像
                }
                if (body.has("epgFontScale")) {
                    int v = Store.clampScale(body.optInt("epgFontScale", Store.SCALE_DEF));
                    if (v != currentEpgFontScale()) appearanceChanged = true;
                    config.put("epgFontScale", v);
                    Store.setEpgFontScale(ctx, v);     // 节目单弹窗读这个镜像
                }
                if (appearanceChanged) notifyAppearance();
                if (body.has("iptvUrl")) {
                    String iu = body.optString("iptvUrl", "").trim();
                    if (!iu.isEmpty() && (!iu.startsWith("http://") && !iu.startsWith("https://")))
                        return jsonError("直播源地址必须以 http(s):// 开头");
                    if (!iu.equals(config.optString("iptvUrl", ""))) iptvCacheTs = 0; // 换源：让缓存失效
                    config.put("iptvUrl", iu);
                }
                saveConfigLocked();
            } catch (JSONException e) {
                return jsonError("配置格式错误");
            }
            return json(publicConfig());
        }
    }

    // ---------------------------------------------------------------- 直播源管理（原生直播模块共用 Store）
    // 网页后台读写的是 Store.liveSubs（SharedPreferences），这正是原生直播 LiveLoader 读取的同一份数据，
    // 因此后台改动能直接在前台直播生效；保存后清缓存并置脏标记，正在播放的页面 onResume 会自动重载。

    /**
     * GET  /api/live-subs           返回 {ok, subs:[{name,url,enabled}]}
     * POST /api/live-subs {subs:[]} 整体替换直播订阅列表（支持添加/编辑/删除/启停）
     */
    private Response apiLiveSubs(IHTTPSession session, Method method) {
        if (method == Method.GET) {
            JSONArray arr = new JSONArray();
            try {
                for (Model.LiveSub s : Store.liveSubs(ctx)) arr.put(s.toJson());
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("subs", arr);
                return json(o);
            } catch (Exception e) {
                return jsonError("读取直播源失败: " + e.getMessage());
            }
        }
        // POST：整体替换
        JSONObject body = bodyJson(session);
        if (!body.has("subs")) return jsonError("缺少 subs 字段");
        JSONArray arr;
        try {
            arr = body.getJSONArray("subs");
        } catch (Exception e) {
            return jsonError("subs 格式错误");
        }
        java.util.List<Model.LiveSub> list = new java.util.ArrayList<Model.LiveSub>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("name", "").trim();
            String url = s.optString("url", "").trim();
            boolean enabled = s.optBoolean("enabled", true);
            if (name.isEmpty()) return jsonError("第 " + (i + 1) + " 个源缺少名称");
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                return jsonError("第 " + (i + 1) + " 个源的地址必须以 http(s):// 开头");
            list.add(new Model.LiveSub(name, url, enabled));
        }
        Store.saveLiveSubs(ctx, list);
        Store.clearLiveCache(ctx);          // 订阅变了：丢弃旧频道缓存，前台下次加载重新拉取
        WebAdmin.markDirty();               // 通知正在前台的直播页 onResume 时重载

        // 同步兼容旧的 config.iptvUrl（取第一个启用的源），让网页版直播页也跟随
        try {
            String first = "";
            for (Model.LiveSub s : list) if (s.enabled) { first = s.url; break; }
            if (!first.isEmpty() && !first.equals(config.optString("iptvUrl", ""))) {
                config.put("iptvUrl", first);
                iptvCacheTs = 0;
                saveConfigLocked();
            }
        } catch (Exception ignored) {}

        JSONObject o = new JSONObject();
        try { o.put("ok", true); o.put("count", list.size()); } catch (JSONException ignored) {}
        return json(o);
    }

    // ---------------------------------------------------------------- 直播分组显示 / 隐藏（v70）

    /**
     * GET  /api/live-groups  → 全量分组清单（**不过滤**，否则隐藏后无法恢复）
     *      {ok, groups:[{name,count,hidden}], total, hiddenCount, source}
     * POST /api/live-groups {hidden:["分组A",...]} → 整体替换隐藏名单
     *
     * 分组来自两条通路的并集：
     *  ① 原生直播模块的合并缓存 Store.liveCache（所有启用订阅 + 本机频道）
     *  ② 网页直播页 /api/iptv 的单订阅解析缓存 iptvChannelsCache
     * 同名分组合并、频道数取较大值，按名字匹配隐藏，因此两条通路都会生效。
     */
    private Response apiLiveGroups(IHTTPSession session, Method method) {
        if (method == Method.POST) {
            JSONObject body = bodyJson(session);
            if (!body.has("hidden")) return jsonError("缺少 hidden 字段");
            java.util.List<String> names = new ArrayList<>();
            try {
                JSONArray arr = body.getJSONArray("hidden");
                for (int i = 0; i < arr.length(); i++) {
                    String g = arr.optString(i, "").trim();
                    if (!g.isEmpty()) names.add(g);
                }
            } catch (JSONException e) {
                return jsonError("hidden 必须是字符串数组");
            }
            Store.saveHiddenLiveGroups(ctx, names);
            WebAdmin.markDirty();   // 通知前台直播页下次 onResume 重载
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("hiddenCount", Store.hiddenLiveGroups(ctx).size());
            } catch (JSONException ignored) {}
            return json(o);
        }

        // GET：全量分组（基于未过滤的缓存），缓存为空时懒拉一次
        List<Model.Channel> all = Store.liveCache(ctx);
        String source = "cache";
        if (all.isEmpty()) {
            try {
                java.util.concurrent.Future<List<Model.Channel>> fut =
                        pool.submit(new java.util.concurrent.Callable<List<Model.Channel>>() {
                            @Override public List<Model.Channel> call() {
                                return Api.loadAllLive(
                                        Store.enabledLiveSubs(ctx), Store.manualChannels(ctx));
                            }
                        });
                List<Model.Channel> fresh = fut.get(8, java.util.concurrent.TimeUnit.SECONDS);
                if (fresh != null && !fresh.isEmpty()) {
                    Store.saveLiveCache(ctx, fresh);   // 存的是全量，不含隐藏过滤
                    all = fresh;
                    source = "fresh";
                }
            } catch (Exception ignored) {}
        }

        LinkedHashMap<String, Integer> counts = Model.countLiveGroups(all);
        // 补充网页直播页单订阅解析出的分组（可能命名不同，按名字各自可管）
        JSONArray web = iptvChannelsCache;
        if (web != null) {
            for (int i = 0; i < web.length(); i++) {
                JSONObject ch = web.optJSONObject(i);
                if (ch == null) continue;
                String g = ch.optString("group", "").trim();
                // 网页直播页解析器把无分组频道记为「其他」，原生模块记为「未分组」，
                // 这里统一成一个名字，设置页只出现一项，隐藏时两条通路一起生效。
                if (g.isEmpty() || "其他".equals(g)) g = "未分组";
                Integer n = counts.get(g);
                counts.put(g, n == null ? 1 : n);   // 只补名字，不重复累加频道数
            }
        }

        java.util.Set<String> hidden = Store.hiddenLiveGroups(ctx);
        JSONArray ga = new JSONArray();
        int hiddenCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            boolean h = hidden.contains(e.getKey());
            if (h) hiddenCount++;
            JSONObject g = new JSONObject();
            try {
                g.put("name", e.getKey());
                g.put("count", e.getValue());
                g.put("hidden", h);
                ga.put(g);
            } catch (JSONException ignored) {}
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("groups", ga);
            o.put("total", counts.size());
            o.put("hiddenCount", hiddenCount);
            o.put("source", source);
            if (counts.isEmpty()) o.put("hint", "还没有频道分组，请先在电视上打开一次 IPTV 直播，或检查直播源是否可用");
        } catch (JSONException ignored) {}
        return json(o);
    }

    // ---------------------------------------------------------------- TVBox 订阅导入（A 档：纯配置层）

    /**
     * POST /api/tvbox
     * 请求：{ url: "https://.../tvbox.json", raw: "可选，直接粘贴的配置文本",
     *        mode: "append"|"replace", vod: true, live: true, dryRun: false }
     * 返回：{ ok, name, sitesTotal, livesTotal, unsupported,
     *        vod:[候选], live:[候选], vodAdded, vodSkipped, liveAdded, liveSkipped,
     *        warnings:[...], dryRun }
     *
     * 只合并「能当苹果 CMS 采集接口用」的点播站与 lives.type=0 的 m3u/txt 直播订阅；
     * Spider/JAR、remote、XML、parses、epg、logo 等一律跳过计数（属于 B/C 档）。
     * 解析交给 TvBoxImporter（纯 Java），这里只负责去重、落盘、清缓存。
     */
    private Response apiTvbox(IHTTPSession session) {
        if (session.getMethod() != Method.POST) return jsonError("请用 POST 导入");
        JSONObject body = bodyJson(session);
        String url = body.optString("url", "").trim();
        String raw = body.optString("raw", "").trim();
        String mode = body.optString("mode", "append");
        boolean doVod = body.optBoolean("vod", true);
        boolean doLive = body.optBoolean("live", true);
        boolean dryRun = body.optBoolean("dryRun", false);
        if (url.isEmpty() && raw.isEmpty())
            return jsonError("请填写 TVBox 订阅地址，或粘贴配置内容");
        // url 与 raw 同时给时以 url 为准（前端会自动分流，这里兜底）
        if (!url.isEmpty()) raw = "";

        TvBoxImporter.Result r;
        try {
            r = TvBoxImporter.load(url, raw);
        } catch (Exception e) {
            return jsonError("订阅拉取/解析失败：" + e.getMessage());
        }

        boolean replace = "replace".equals(mode);
        // 去重合并是纯数据操作（TvBoxImporter.merge，可桌面单测）；
        // dryRun 时只算统计、绝不写回 config / Store。
        TvBoxImporter.Merged vodMerge = doVod
                ? TvBoxImporter.merge(config.optJSONArray("sources"), r.vod, replace)
                : null;
        TvBoxImporter.Merged liveMerge = null;
        if (doLive) {
            JSONArray cur = new JSONArray();
            java.util.List<Model.LiveSub> oldSubs = Store.liveSubs(ctx);
            for (Model.LiveSub s : oldSubs) {
                try {
                    JSONObject x = new JSONObject();
                    x.put("name", s.name); x.put("url", s.url); x.put("enabled", s.enabled);
                    cur.put(x);
                } catch (JSONException ignored) {}
            }
            liveMerge = TvBoxImporter.merge(cur, r.live, replace);
        }

        int vodAdded = vodMerge == null ? 0 : vodMerge.added;
        int vodSkipped = vodMerge == null ? 0 : vodMerge.skipped;
        int liveAdded = liveMerge == null ? 0 : liveMerge.added;
        int liveSkipped = liveMerge == null ? 0 : liveMerge.skipped;

        if (!dryRun) {
            synchronized (cfgLock) {
                try {
                    if (vodMerge != null) {
                        config.put("sources", vodMerge.items);
                        saveConfigLocked();
                    }
                    if (liveMerge != null && liveAdded > 0) {
                        java.util.List<Model.LiveSub> subs = new java.util.ArrayList<Model.LiveSub>();
                        for (int i = 0; i < liveMerge.items.length(); i++) {
                            JSONObject o = liveMerge.items.optJSONObject(i);
                            if (o == null) continue;
                            subs.add(new Model.LiveSub(o.optString("name", ""),
                                    o.optString("url", ""), o.optBoolean("enabled", true)));
                        }
                        Store.saveLiveSubs(ctx, subs);
                        Store.clearLiveCache(ctx);      // 订阅变了：丢弃旧频道缓存
                        WebAdmin.markDirty();           // 前台直播页 onResume 自动重载
                        // 兼容旧的 config.iptvUrl（网页降级直播页读它）
                        String first = "";
                        for (Model.LiveSub s : subs) if (s.enabled) { first = s.url; break; }
                        if (!first.isEmpty() && !first.equals(config.optString("iptvUrl", ""))) {
                            config.put("iptvUrl", first);
                            iptvCacheTs = 0;
                            saveConfigLocked();
                        }
                    }
                } catch (Exception e) {
                    return jsonError("导入保存失败：" + e.getMessage());
                }
            }
        }

        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("dryRun", dryRun);
            o.put("name", r.name);
            o.put("sitesTotal", r.sitesTotal);
            o.put("livesTotal", r.livesTotal);
            o.put("unsupported", r.unsupported);
            o.put("vod", r.vod);
            o.put("live", r.live);
            o.put("vodAdded", vodAdded);
            o.put("vodSkipped", vodSkipped);
            o.put("liveAdded", liveAdded);
            o.put("liveSkipped", liveSkipped);
            JSONArray w = new JSONArray();
            for (String x : r.warnings) w.put(x);
            o.put("warnings", w);
            if (r.unsupported > 0) {
                o.put("note", "已跳过 " + r.unsupported + " 个不支持的源（Spider/JAR、remote、XML 或非 http 地址）；A 档只导入 CMS JSON 采集站与 m3u/txt 直播订阅");
            }
        } catch (JSONException ignored) {}
        return json(o);
    }

    // ---------------------------------------------------------------- IPTV 直播

    /**
     * GET  /api/iptv?force=1   拉取并解析 m3u 频道列表（带 6 小时缓存），返回 {ok, url, count, groups:[{name, channels:[...]}]}
     * POST /api/iptv {url}     仅保存直播源地址（等价 config 里的 iptvUrl），并让缓存失效
     */
    private Response apiIptv(IHTTPSession session, Method method) {
        if (method == Method.POST) {
            JSONObject body = bodyJson(session);
            String iu = body.optString("url", "").trim();
            if (iu.isEmpty() || (!iu.startsWith("http://") && !iu.startsWith("https://")))
                return jsonError("订阅地址必须以 http(s):// 开头");
            synchronized (cfgLock) {
                try { config.put("iptvUrl", iu); } catch (JSONException ignored) {}
                saveConfigLocked();
            }
            iptvCacheTs = 0;
            JSONObject o = new JSONObject();
            try { o.put("ok", true); o.put("url", iu); } catch (JSONException ignored) {}
            return json(o);
        }
        // GET
        String url;
        String testUrl = param(session, "url");   // 单源测试：临时地址，不改配置、不污染全局缓存
        if (testUrl != null && (testUrl.startsWith("http://") || testUrl.startsWith("https://"))) {
            try {
                String m3u = fetchText(testUrl);
                JSONArray channels = parseM3u(m3u, testUrl);
                if (channels == null || channels.length() == 0)
                    return jsonError("未能解析出频道，请检查订阅地址");
                return iptvPayload(testUrl, channels, false);   // 测试显示该源全部频道
            } catch (Exception e) {
                return jsonError("拉取订阅失败: " + e.getMessage());
            }
        }
        synchronized (cfgLock) {
            url = config.optString("iptvUrl", DEFAULT_IPTV_URL);
        }
        String force = param(session, "force");
        JSONArray cache = iptvChannelsCache;
        if (cache != null && (force == null || !force.equals("1"))
                && System.currentTimeMillis() - iptvCacheTs < IPTV_CACHE_TTL) {
            return iptvPayload(url, cache, true);
        }
        try {
            String m3u = fetchText(url);
            JSONArray channels = parseM3u(m3u, url);
            if (channels == null || channels.length() == 0) {
                if (cache != null) return iptvPayload(url, cache, true); // 拉取失败/空：回退旧缓存
                return jsonError("未能解析出频道，请检查直播源地址是否可用");
            }
            iptvChannelsCache = channels;
            iptvCacheTs = System.currentTimeMillis();
            return iptvPayload(url, channels, true);
        } catch (Exception e) {
            if (cache != null) return iptvPayload(url, cache, true);
            return jsonError("拉取直播源失败: " + e.getMessage());
        }
    }

    /**
     * 把扁平频道数组按 group 分组，组装返回体。
     * applyHidden=true 时按「分组管理」的隐藏名单剔除分组（网页直播页用）；
     * 单源订阅测试传 false，诊断时始终显示该源的全部频道。
     */
    private Response iptvPayload(String url, JSONArray channels, boolean applyHidden) {
        java.util.Set<String> hidden = applyHidden
                ? Store.hiddenLiveGroups(ctx) : new java.util.HashSet<String>();
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("url", url);
            LinkedHashMap<String, JSONArray> groups = new LinkedHashMap<>();
            int visible = 0;
            for (int i = 0; i < channels.length(); i++) {
                JSONObject ch = channels.optJSONObject(i);
                if (ch == null) continue;
                String raw = ch.optString("group", "其他").trim();
                // 隐藏判断用与「分组管理」一致的归一名字（空组 → 未分组）；
                // 返回给网页直播页的分组名保持历史口径（空组 → 其他），不影响显示。
                String norm = (raw.isEmpty() || "其他".equals(raw)) ? "未分组" : raw;
                if (hidden.contains(norm)) continue;
                visible++;
                String g = raw.isEmpty() ? "其他" : raw;
                JSONArray arr = groups.get(g);
                if (arr == null) { arr = new JSONArray(); groups.put(g, arr); }
                arr.put(ch);
            }
            o.put("count", visible);
            JSONArray ga = new JSONArray();
            for (Map.Entry<String, JSONArray> e : groups.entrySet()) {
                JSONObject g = new JSONObject();
                g.put("name", e.getKey());
                g.put("channels", e.getValue());
                ga.put(g);
            }
            o.put("groups", ga);
            if (visible == 0 && channels.length() > 0) {
                o.put("hint", "全部分组已隐藏，可在设置 → 分组管理里重新显示");
            }
        } catch (JSONException ignored) {}
        return json(o);
    }

    /** 拉取纯文本（UTF-8，自动处理 BOM） */
    private String fetchText(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "*/*");
        int code = conn.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        byte[] buf = readAll(conn.getInputStream());
        conn.disconnect();
        int off = 0;
        if (buf.length >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) off = 3;
        return new String(buf, off, buf.length - off, "UTF-8");
    }

    /** 解析 m3u：支持 #EXTINF 的 tvg-name/tvg-logo/group-title 属性、#EXTGRP 分组 */
    private JSONArray parseM3u(String m3u, String baseUrl) {
        JSONArray out = new JSONArray();
        if (m3u == null) return out;
        String[] lines = m3u.split("\\r?\\n");
        String pendingName = null, pendingLogo = "", pendingGroup = "", extGroup = "", pendingUa = "";
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTGRP:")) {
                extGroup = line.substring(8).trim();
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                int comma = line.indexOf(',');
                String name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                String logo = attr(line, "tvg-logo");
                String group = attr(line, "group-title");
                String ua = attr(line, "http-user-agent");
                if (group.isEmpty()) group = extGroup;
                pendingName = name.isEmpty() ? "" : name;
                pendingLogo = logo;
                pendingGroup = group;
                pendingUa = ua;
                continue;
            }
            if (line.startsWith("#")) continue; // 其它 #EXT 指令
            // 非注释行：频道 URL
            if (pendingName != null && !line.isEmpty()) {
                String stream = line;
                // 相对地址补全
                if (!stream.startsWith("http://") && !stream.startsWith("https://")) {
                    try {
                        URL b = new URL(baseUrl);
                        stream = new URL(b, stream).toString();
                    } catch (Exception ignored) {}
                }
                try {
                    JSONObject ch = new JSONObject();
                    ch.put("name", pendingName);
                    ch.put("logo", pendingLogo);
                    ch.put("group", pendingGroup.isEmpty() ? "其他" : pendingGroup);
                    ch.put("url", stream);
                    if (!pendingUa.isEmpty()) ch.put("ua", pendingUa);
                    out.put(ch);
                } catch (JSONException ignored) {}
            }
            pendingName = null; pendingLogo = ""; pendingGroup = ""; pendingUa = "";
        }
        return out;
    }

    /** 从 #EXTINF 行里取属性（兼容 tvg-logo="x" 与 tvg-logo=x） */
    private static String attr(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) return "";
        int s = i + key.length() + 1;
        if (s >= line.length()) return "";
        char c = line.charAt(s);
        if (c == '"' || c == '\'') {
            int e = line.indexOf(c, s + 1);
            return e > s ? line.substring(s + 1, e).trim() : "";
        }
        int e = s;
        while (e < line.length() && line.charAt(e) != ',' && !Character.isWhitespace(line.charAt(e))) e++;
        return line.substring(s, e).trim();
    }

    // ---------------------------------------------------------------- 手机推送 / 电视轮询

    private Response apiPush(IHTTPSession session) {
        JSONObject body = bodyJson(session);
        String action = body.optString("action", "");
        if (action.isEmpty()) return jsonError("缺少 action");
        try {
            body.put("pushId", UUID.randomUUID().toString());
        } catch (JSONException ignored) {
        }
        synchronized (pushLock) {
            pendingPush = body;   // 保留兜底：首页被回收 / WebView 冻结恢复后由 /api/poll 取到
        }
        // 立刻主动喂给首页 JS，不等 2 秒轮询（播放时 WebView 的定时器会被系统冻结）。
        // 即时送达与轮询可能拿到同一条，由前端 pushId 去重。
        boolean delivered = MainActivity.dispatchPush(body.toString());
        if (!delivered) {
            // 首页不在了（被系统回收）：apk / live 这两类原生就能办，直接办掉，
            // 其余（play / search / config）仍留给重建后的首页轮询。
            if ("apk".equals(action)) {
                ApkInstaller.install(ctx, body.optString("path", ""),
                        body.optString("name", ""), body.optLong("token", 0L));
            } else if ("live".equals(action)) {
                startPushedLive(body);
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("delivered", delivered);
        } catch (JSONException ignored) {
        }
        return json(o);
    }

    /** 首页被回收时的直播推送兜底：直接起直播页（NEW_TASK + SINGLE_TOP） */
    private void startPushedLive(JSONObject body) {
        try {
            Intent i = new Intent(ctx, LivePlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("pushName", body.optString("name", ""));
            JSONArray arr = body.optJSONArray("urls");
            ArrayList<String> urls = new ArrayList<>();
            if (arr != null) {
                for (int k = 0; k < arr.length(); k++) {
                    String u = arr.optString(k, "");
                    if (!u.isEmpty()) urls.add(u);
                }
            }
            i.putStringArrayListExtra("pushUrls", urls);
            final Intent fi = i;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try { ctx.startActivity(fi); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {
        }
    }

    private Response apiPoll() {
        JSONObject o;
        synchronized (pushLock) {
            o = pendingPush;
            pendingPush = null;
        }
        if (o == null) o = new JSONObject();
        return json(o);
    }

    // ---------------------------------------------------------------- 直播推送搜索

    /**
     * GET /api/live-search?wd=央视&force=1
     * 搜索电视端直播源里的频道（手机后台「TV 推送 → 直播推送」）。
     * 频道列表优先用原生直播模块已合并的缓存（含所有已启用订阅 + 本机频道）；
     * 缓存为空或 force=1 时并发重新拉取一次，仍拿不到才回退到首页面板的单订阅地址。
     */
    private Response apiLiveSearch(IHTTPSession session) {
        String wd = param(session, "wd");
        boolean force = "1".equals(param(session, "force"));
        List<Model.Channel> all = Store.liveCache(ctx);
        String source = "cache";
        if (all.isEmpty() || force) {
            try {
                Future<List<Model.Channel>> fut = pool.submit(new Callable<List<Model.Channel>>() {
                    @Override
                    public List<Model.Channel> call() {
                        return Api.loadAllLive(Store.enabledLiveSubs(ctx), Store.manualChannels(ctx));
                    }
                });
                List<Model.Channel> fresh = fut.get(12, TimeUnit.SECONDS);
                if (fresh != null && !fresh.isEmpty()) {
                    Store.saveLiveCache(ctx, fresh);
                    all = fresh;
                    source = "fresh";
                }
            } catch (Exception ignored) {
            }
        }
        if (all.isEmpty()) {
            try {
                String url;
                synchronized (cfgLock) {
                    url = config.optString("iptvUrl", DEFAULT_IPTV_URL);
                }
                JSONArray arr = parseM3u(fetchText(url), url);
                List<Model.Channel> fb = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    Model.Channel ch = new Model.Channel(
                            c.optString("name", ""), c.optString("group", "其他"), c.optString("logo", ""));
                    String u = c.optString("url", "");
                    if (!u.isEmpty()) ch.urls.add(u);
                    if (!ch.name.isEmpty() && !ch.urls.isEmpty()) fb.add(ch);
                }
                if (!fb.isEmpty()) { all = fb; source = "fallback"; }
            } catch (Exception ignored) {
            }
        }
        JSONObject r = LiveSearch.filter(all, wd);
        try {
            r.put("source", source);
            r.put("channels", all == null ? 0 : all.size());
            if (all == null || all.isEmpty()) {
                r.put("hint", "电视端还没有加载直播频道，请在电视上打开一次 IPTV 直播，或检查订阅源是否可用");
            }
        } catch (JSONException ignored) {
        }
        return json(r);
    }

    // ---------------------------------------------------------------- APK 升级推送

    /**
     * POST /api/push-apk：手机直接把 APK 二进制 PUT 过来（Content-Type: octet-stream，
     * 文件名在 X-File-Name 头里，百分号编码）。校验是含 AndroidManifest.xml 的 ZIP 后
     * 存到 cache/push/update.apk，并让电视端立即弹出安装界面。
     */
    private Response apiPushApk(IHTTPSession session) {
        JSONObject o = new JSONObject();
        try {
            String cl = header(session, "content-length");
            long len = cl == null ? -1 : Long.parseLong(cl.trim());
            if (len < 100 * 1024 || len > 200L * 1024 * 1024)
                return jsonError("APK 大小不合法（需 100KB ~ 200MB）");

            String name = "update.apk";
            String rawName = header(session, "x-file-name");
            if (rawName != null && !rawName.isEmpty()) {
                name = new File(URLDecoder.decode(rawName, "UTF-8")).getName();  // 防路径穿越
                if (name.isEmpty()) name = "update.apk";
            }

            File dir = new File(ctx.getCacheDir(), "push");
            if (!dir.isDirectory() && !dir.mkdirs()) return jsonError("无法创建升级包缓存目录");
            File tmp = new File(dir, "update.apk.tmp");
            File dst = new File(dir, "update.apk");

            InputStream is = session.getInputStream();
            FileOutputStream fos = new FileOutputStream(tmp);
            long left = len, written = 0;
            byte[] buf = new byte[64 * 1024];
            try {
                while (left > 0) {
                    int n = is.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) break;
                    fos.write(buf, 0, n);
                    left -= n;
                    written += n;
                }
            } finally {
                try { fos.flush(); } catch (Exception ignored) {}
                try { fos.close(); } catch (Exception ignored) {}
            }
            if (left > 0 || !isApk(tmp)) {
                tmp.delete();
                return jsonError("上传不完整或文件不是有效的 APK");
            }
            if (dst.exists() && !dst.delete()) {
                tmp.delete();
                return jsonError("旧安装包清理失败");
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return jsonError("保存升级包失败");
            }

            // 用毫秒时间戳而非 nanoTime：token 会经 JS 双精度数字回传，nanoTime(~1e17) 会丢精度
            long token = System.currentTimeMillis();
            JSONObject push = new JSONObject();
            push.put("action", "apk");
            push.put("path", dst.getAbsolutePath());
            push.put("name", name);
            push.put("size", written);
            push.put("token", token);
            push.put("pushId", UUID.randomUUID().toString());
            synchronized (pushLock) {
                pendingPush = push;   // WebView 轮询兜底
            }
            // 直接以 Application Context 调起：MainActivity 在后台 stop 时（正在播放）也能弹安装界面。
            // 轮询若再拿到同一条，由 pushId + ApkInstaller 的 token 双重去重，不会弹两次。
            ApkInstaller.install(ctx, dst.getAbsolutePath(), name, token);

            o.put("ok", true);
            o.put("name", name);
            o.put("size", written);
            o.put("installing", true);
        } catch (Exception e) {
            return jsonError("推送升级包失败：" + e.getMessage());
        }
        return json(o);
    }

    /** 粗校验：ZIP 里必须有 AndroidManifest.xml，避免把任意文件送去安装 */
    private static boolean isApk(File f) {
        ZipFile z = null;
        try {
            z = new ZipFile(f);
            return z.getEntry("AndroidManifest.xml") != null;
        } catch (Exception e) {
            return false;
        } finally {
            if (z != null) {
                try { z.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** GET /api/home-chooser：在电视上弹出系统「选择默认桌面」对话框 */
    private Response apiHomeChooser() {
        if (!"home".equals(Store.launcherMode(ctx))) {
            return jsonError("请先把应用模式设为「桌面应用」并保存");
        }
        LauncherMode.openHomeChooser(ctx);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("hint", "请在电视上选择 iLaTV 并勾选「始终」");
        } catch (JSONException ignored) {
        }
        return json(o);
    }

    // ---------------------------------------------------------------- 清除缓存

    /** 清除系统缓存（WebView HTTP 缓存等）+ 搜索缓存 + 服务端观看记录，返回释放的字节数 */
    private Response apiClearCache() {
        synchronized (searchCacheLock) {
            searchCache.clear();
        }
        // 同时清空服务端共享观看记录，保证电视/手机两端一致
        synchronized (historyLock) {
            saveHistoryUnsafe(new JSONArray());
        }
        long freed = 0;
        try {
            freed += wipeDir(ctx.getCacheDir());
        } catch (Exception ignored) {
        }
        try {
            File ext = ctx.getExternalCacheDir();
            if (ext != null) freed += wipeDir(ext);
        } catch (Exception ignored) {
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("freed", freed);
        } catch (JSONException ignored) {
        }
        return json(o);
    }

    /** 删除目录下全部内容（不删目录本身），返回删除文件的累计大小 */
    private static long wipeDir(File dir) {
        long total = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) total += deleteRec(f);
        return total;
    }

    private static long deleteRec(File f) {
        long size = 0;
        try {
            if (f.isDirectory()) {
                File[] cs = f.listFiles();
                if (cs != null) for (File c : cs) size += deleteRec(c);
            }
            size += f.length();
            f.delete();
        } catch (Exception ignored) {
        }
        return size;
    }

    // ---------------------------------------------------------------- 搜索（聚合）

    private Response apiSearch(IHTTPSession session) {
        JSONObject body = bodyJson(session);
        final String wd = body.optString("wd", "").trim();
        if (wd.isEmpty() || wd.length() > 100) return jsonError("搜索关键词无效");
        final boolean filterAdult = body.optBoolean("filterAdult", config.optBoolean("filterAdult", true));
        JSONArray arr = sourcesArr();
        if (arr == null || arr.length() == 0) return jsonError("没有可用源，请用手机扫码添加");

        // 1) 命中缓存直接秒回
        final String cacheKey = wd + "|" + filterAdult + "|" + configSignature(arr);
        JSONObject cached = getSearchCache(cacheKey);
        if (cached != null) return json(cached);

        // 2) 并发请求所有源，先完成先收集；到全局预算就返回已有结果，绝不等最慢的源
        int n = arr.length();
        CompletionService<JSONObject> cs = new ExecutorCompletionService<>(pool);
        List<Future<JSONObject>> submitted = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final JSONObject src = arr.optJSONObject(i);
            final String key = "" + i;
            submitted.add(cs.submit(() -> searchOneSource(key, src, wd, filterAdult)));
        }
        JSONArray list = new JSONArray();
        JSONArray failures = new JSONArray();
        final long deadline = System.currentTimeMillis() + SEARCH_BUDGET_MS;
        int got = 0;
        while (got < n) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;  // 超时：不再等待剩余慢源
            try {
                Future<JSONObject> f = cs.poll(remain, TimeUnit.MILLISECONDS);
                if (f == null) break;
                got++;
                JSONObject out = f.get();
                if (out.has("error")) failures.put(out);
                else {
                    JSONArray l = out.optJSONArray("list");
                    if (l != null) for (int j = 0; j < l.length(); j++) list.put(l.get(j));
                }
            } catch (Exception e) {
                // 单个源异常不影响整体
            }
        }
        // 高延迟源直接放弃：取消预算内未完成的请求，释放线程
        for (Future<JSONObject> f : submitted) {
            if (!f.isDone()) f.cancel(true);
        }
        JSONObject result = aggregate(list, wd, filterAdult);
        try {
            result.put("failures", failures);
            result.put("ms", System.currentTimeMillis() - (deadline - SEARCH_BUDGET_MS));
        } catch (JSONException ignored) {
        }
        putSearchCache(cacheKey, result);
        return json(result);
    }

    /** 源配置签名：源列表变化后搜索缓存自动失效 */
    private static String configSignature(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null) sb.append(s.optString("url", "")).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private JSONObject getSearchCache(String key) {
        synchronized (searchCacheLock) {
            Object[] e = searchCache.get(key);
            if (e != null && System.currentTimeMillis() - (Long) e[0] < SEARCH_CACHE_TTL) {
                return (JSONObject) e[1];
            }
            if (e != null) searchCache.remove(key);
            return null;
        }
    }

    private void putSearchCache(String key, JSONObject value) {
        synchronized (searchCacheLock) {
            if (searchCache.size() > 100) searchCache.clear();  // 简单上限，避免无限增长
            searchCache.put(key, new Object[]{System.currentTimeMillis(), value});
        }
    }

    /** 单源搜索：?ac=videolist&wd=xx&pg=N，参考 LibreTV cms-parser */
    private JSONObject searchOneSource(String key, JSONObject src, String wd, boolean filterAdult) {
        JSONObject out = new JSONObject();
        try {
            out.put("sourceKey", key);
        } catch (JSONException ignored) {
        }
        if (src == null) {
            putErr(out, "无效的源配置");
            return out;
        }
        String name = src.optString("name", key);
        String base = src.optString("url", "").replaceAll("/+$", "");
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            putErr(out, "无效的源地址");
            return out;
        }
        try {
            // 只抓第一页：搜索场景下第一页基本覆盖目标结果，避免串行翻页拖慢整体
            JSONObject first = httpGetJson(base + "?ac=videolist&wd=" + enc(wd) + "&pg=1", SEARCH_TIMEOUT_MS);
            JSONArray list = new JSONArray();
            parseSearchList(first, key, name, base, list);
            try {
                out.put("list", list);
            } catch (JSONException ignored) {
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            putErr(out, msg == null ? "请求失败" : msg);
        }
        return out;
    }

    private static void putErr(JSONObject o, String err) {
        try {
            o.put("error", err);
        } catch (JSONException ignored) {
        }
    }

    /** Apple CMS 列表 → 统一搜索条目 */
    private static void parseSearchList(JSONObject data, String key, String name, String url, JSONArray out)
            throws JSONException {
        if (data == null) throw new JSONException("空响应");
        JSONArray list = data.optJSONArray("list");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject vod = list.optJSONObject(i);
            if (vod == null) continue;
            JSONObject item = new JSONObject();
            item.put("sourceKey", key);
            item.put("sourceName", name);
            item.put("sourceUrl", url);
            item.put("vodId", String.valueOf(vod.opt("vod_id") == null ? "" : vod.opt("vod_id")));
            item.put("name", vod.optString("vod_name", ""));
            item.put("pic", vod.optString("vod_pic", ""));
            item.put("typeName", vod.optString("type_name", ""));
            item.put("year", vod.optString("vod_year", ""));
            item.put("area", vod.optString("vod_area", ""));
            item.put("remarks", vod.optString("vod_remarks", ""));
            out.put(item);
        }
    }

    /**
     * 聚合：同源去重 + 成人过滤 + 相关性过滤，再按「归一化片名」把不同源里
     * 的同一部片合并成一张卡片（sources 存所有可用源，详情页可切换）。
     */
    private JSONObject aggregate(JSONArray list, String wd, boolean filterAdult) {
        JSONObject result = new JSONObject();
        String nwd = normalizeTitle(wd);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        java.util.LinkedHashMap<String, java.util.List<JSONObject>> groups =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("sourceKey") + "_" + item.optString("vodId");
            if (!seen.add(id)) continue;
            if (filterAdult && isAdult(item.optString("typeName"))) continue;
            if (!isRelevant(item.optString("name"), wd, nwd)) continue;
            String mk = mergeKey(item.optString("name"));
            if (mk.isEmpty()) mk = id; // 片名异常时退回不合并
            java.util.List<JSONObject> g = groups.get(mk);
            if (g == null) {
                g = new java.util.ArrayList<>();
                groups.put(mk, g);
            }
            // 同一源里只保留第一个条目（同源同名多版本不重复出现在切源列表里）
            boolean dup = false;
            for (JSONObject x : g) {
                if (x.optString("sourceKey").equals(item.optString("sourceKey"))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) g.add(item);
        }
        java.util.List<JSONObject> merged = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.List<JSONObject>> en : groups.entrySet()) {
            java.util.List<JSONObject> g = en.getValue();
            JSONObject first = g.get(0);
            JSONObject m = new JSONObject();
            try {
                m.put("mergeKey", en.getKey());
                m.put("name", first.optString("name", ""));
                m.put("pic", firstNonEmpty(g, "pic"));
                m.put("typeName", firstNonEmpty(g, "typeName"));
                m.put("year", firstNonEmpty(g, "year"));
                m.put("area", firstNonEmpty(g, "area"));
                m.put("remarks", firstNonEmpty(g, "remarks"));
                m.put("sourceKey", first.optString("sourceKey"));
                m.put("sourceName", first.optString("sourceName"));
                m.put("sourceUrl", first.optString("sourceUrl"));
                m.put("vodId", first.optString("vodId"));
                JSONArray sources = new JSONArray();
                for (JSONObject src : g) {
                    JSONObject o = new JSONObject();
                    o.put("sourceKey", src.optString("sourceKey"));
                    o.put("sourceName", src.optString("sourceName"));
                    o.put("vodId", src.optString("vodId"));
                    o.put("remarks", src.optString("remarks"));
                    o.put("pic", src.optString("pic"));
                    sources.put(o);
                }
                m.put("sources", sources);
                m.put("sourceCount", sources.length());
            } catch (JSONException ignored) {
            }
            merged.add(m);
        }
        // 排序：精确命中优先，其次可用源多，再次片名
        final String target = nwd;
        Collections.sort(merged, (a, b) -> {
            boolean ea = normalizeTitle(a.optString("name")).equals(target);
            boolean eb = normalizeTitle(b.optString("name")).equals(target);
            if (ea != eb) return ea ? -1 : 1;
            int c = b.optInt("sourceCount", 1) - a.optInt("sourceCount", 1);
            if (c != 0) return c;
            return a.optString("name").compareTo(b.optString("name"));
        });
        JSONArray sorted = new JSONArray();
        for (JSONObject o : merged) sorted.put(o);
        try {
            result.put("list", sorted);
        } catch (JSONException ignored) {
        }
        return result;
    }

    private static String firstNonEmpty(java.util.List<JSONObject> g, String key) {
        for (JSONObject o : g) {
            String v = o.optString(key, "");
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** 合并键：归一化后去掉画质/状态后缀（保留季数、部数等有区分度的内容，避免串剧） */
    private static String mergeKey(String name) {
        String s = normalizeTitle(name);
        s = s.replaceAll("(?i)(hd|bd|1080p|720p|2160p|4k|蓝光|高清|超清|国语|粤语|中字|未删减|完整版|全集|完结|抢先版|dvd|web版).*$", "");
        s = s.replaceAll("更新至[0-9]+集.*$", "");
        s = s.replaceAll("共[0-9]+集.*$", "");
        return s;
    }

    private static final String[] ADULT_KEYWORDS = {
            "伦理片", "福利", "倫理片", "福利片", "日本伦理", "日本福利", "擦边", "韩国伦理", "里番",
            "门事件", "萝莉少女", "制服诱惑", "国产传媒", "黑丝诱惑", "无码", "有码", "SWAG",
            "网红主播", "色情片", "福利视频", "91视频", "自拍偷拍", "mini传媒"
    };

    private static boolean isAdult(String typeName) {
        if (typeName == null || typeName.isEmpty()) return false;
        for (String k : ADULT_KEYWORDS) if (typeName.contains(k)) return true;
        return false;
    }

    /** 归一化标题：去空白与常见标点符号，转小写 */
    private static String normalizeTitle(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    /** 相关性过滤：归一化后需包含完整关键词（多词时每个词都命中） */
    private static boolean isRelevant(String name, String wd, String nwd) {
        if (nwd.isEmpty()) return true;
        String nName = normalizeTitle(name);
        if (nName.isEmpty()) return false;
        if (nName.contains(nwd)) return true;
        String[] tokens = wd.trim().split("\\s+");
        if (tokens.length > 1) {
            for (String t : tokens) {
                String nt = normalizeTitle(t);
                if (!nt.isEmpty() && !nName.contains(nt)) return false;
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 详情

    private Response apiDetail(IHTTPSession session) {
        String k = param(session, "k");
        String id = param(session, "id");
        if (k == null || id == null || !id.matches("[\\w-]+")) return jsonError("无效的参数");
        JSONObject src = findSource(k);
        if (src == null) return jsonError("无效的源");
        String name = src.optString("name", k);
        String base = src.optString("url", "").replaceAll("/+$", "");
        try {
            JSONObject data = httpGetJson(base + "?ac=videolist&ids=" + enc(id), 10000);
            JSONObject out = parseDetail(data, k, name, base);
            if (out.optJSONArray("episodes") != null && out.optJSONArray("episodes").length() > 0) {
                return json(out);
            }
        } catch (Exception ignored) {
        }
        // 列表接口拿不到播放地址时，尝试详情页 HTML 提取
        try {
            String detailRoot = base.replaceAll("(https?://[^/]+).*", "$1");
            String html = httpGetString(detailRoot + "/?vod_detail_id=" + id + ".html", 8000);
            return json(parseDetailHtml(html, k, name, base));
        } catch (Exception e) {
            return jsonError("获取详情失败：" + e.getMessage());
        }
    }

    private static JSONObject parseDetail(JSONObject data, String key, String name, String url) throws JSONException {
        JSONObject out = new JSONObject();
        if (data == null) throw new JSONException("空响应");
        JSONArray list = data.optJSONArray("list");
        if (list == null || list.length() == 0) throw new JSONException("详情为空");
        JSONObject vod = list.optJSONObject(0);
        JSONArray episodes = extractEpisodes(vod.optString("vod_play_url", ""));
        if (episodes.length() == 0) episodes = extractFromText(vod.optString("vod_content", ""));
        JSONObject info = new JSONObject();
        info.put("title", vod.optString("vod_name", ""));
        info.put("cover", vod.optString("vod_pic", ""));
        info.put("desc", vod.optString("vod_content", ""));
        info.put("typeName", vod.optString("type_name", ""));
        info.put("year", vod.optString("vod_year", ""));
        info.put("area", vod.optString("vod_area", ""));
        info.put("remarks", vod.optString("vod_remarks", ""));
        info.put("sourceKey", key);
        info.put("sourceName", name);
        out.put("episodes", episodes);
        out.put("videoInfo", info);
        return out;
    }

    /** vod_play_url: 组1$$$组2，组内 集1$URL1#集2$URL2。优先含 m3u8 的线路，否则用第一条。 */
    private static JSONArray extractEpisodes(String playUrl) throws JSONException {
        JSONArray eps = new JSONArray();
        if (playUrl == null || playUrl.isEmpty()) return eps;
        String[] groups = playUrl.split("\\$\\$\\$");
        JSONArray first = null;
        JSONArray withM3u8 = null;
        for (String g : groups) {
            JSONArray cur = new JSONArray();
            String[] items = g.split("#");
            for (String it : items) {
                int idx = it.indexOf('$');
                String u = idx >= 0 ? it.substring(idx + 1) : "";
                if (u.startsWith("http://") || u.startsWith("https://")) cur.put(u);
            }
            if (cur.length() == 0) continue;
            if (first == null) first = cur;
            if (withM3u8 == null) {
                for (int i = 0; i < cur.length(); i++) {
                    if (cur.optString(i).contains(".m3u8")) {
                        withM3u8 = cur;
                        break;
                    }
                }
            }
        }
        JSONArray best = withM3u8 != null ? withM3u8 : first;
        if (best != null) return best;
        return eps;
    }

    private static JSONArray extractFromText(String text) throws JSONException {
        JSONArray eps = new JSONArray();
        if (text == null) return eps;
        Matcher m = Pattern.compile("\\$(https?://[^\"'\\s]+?\\.m3u8)").matcher(text);
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        while (m.find()) set.add(m.group(1));
        for (String s : set) eps.put(s);
        return eps;
    }

    private static JSONObject parseDetailHtml(String html, String key, String name, String url) throws JSONException {
        JSONObject out = new JSONObject();
        JSONArray eps = extractFromText(html);
        Matcher m2 = Pattern.compile("\\$(https?://[^\"'\\s]+?/\\d{8}/\\d+_[a-f0-9]+/index\\.m3u8)").matcher(html);
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        while (m2.find()) set.add(m2.group(1));
        if (eps.length() == 0) for (String s : set) eps.put(s);
        JSONObject info = new JSONObject();
        Matcher t = Pattern.compile("<h1[^>]*>([^<]+)</h1>").matcher(html);
        if (t.find()) info.put("title", t.group(1).trim());
        info.put("sourceKey", key);
        info.put("sourceName", name);
        out.put("episodes", eps);
        out.put("videoInfo", info);
        return out;
    }

    // ---------------------------------------------------------------- HLS 代理

    /**
     * 通用媒体代理：
     * - m3u8：加 UA/Referer 拉取，改写内部 URL 为 /hls?url=...，支持 Range 透传；
     * - ts/mp4/其它：流式转发 + Range。
     */
    /** 轻量探测流类型：只读取开头少量字节，返回 m3u8/flv/mp4/ts/unknown，供前端选择播放器 */
    private Response probeStream(IHTTPSession session) {
        String target = param(session, "url");
        if (target == null || target.isEmpty()) return txt("unknown");
        String ua = param(session, "ua");
        if (ua == null || ua.isEmpty()) ua = UA;
        try {
            HttpURLConnection conn = openFollow(target, ua, null);
            String ctype = conn.getContentType();
            if (ctype == null) ctype = "";
            ctype = ctype.toLowerCase(Locale.US);
            String finalUrl = conn.getURL().toString().toLowerCase(Locale.US);
            InputStream raw = conn.getInputStream();
            byte[] head = new byte[512];
            int n = 0;
            try {
                while (n < head.length) {
                    int r = raw.read(head, n, head.length - n);
                    if (r <= 0) break;
                    n += r;
                }
            } catch (Exception ignored) {
            }
            try { raw.close(); } catch (Exception ignored) {}
            String s = new String(head, 0, n, "ISO-8859-1");
            String type = "unknown";
            if (looksLikeM3u8(s) || ctype.contains("mpegurl") || ctype.contains("m3u8") || finalUrl.contains(".m3u8")) {
                type = "m3u8";
            } else if (n >= 3 && head[0] == 'F' && head[1] == 'L' && head[2] == 'V') {
                type = "flv";                       // HTTP-FLV：flv.js / mpegts.js
            } else if (n >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                type = "mp4";                       // ISO BMFF: ftyp at offset 4
            } else if (n >= 188 && head[0] == 0x47) {
                type = "ts";                        // MPEG-TS 同步字节 0x47
            } else if (ctype.contains("mpegurl")) {
                type = "m3u8";
            } else if (ctype.contains("flv")) {
                type = "flv";
            }
            return txt(type);
        } catch (Exception e) {
            String t = (target.toLowerCase(Locale.US));
            if (t.contains(".flv")) return txt("flv");
            if (t.contains(".m3u8")) return txt("m3u8");
            if (t.contains(".mp4")) return txt("mp4");
            return txt("unknown");
        }
    }

    private Response txt(String s) {
        Response r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", s);
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    /**
     * FLV 专用透传：直播长流，不预读、不设读超时（0=无限），chunked 持续推送。
     * flv.js 通过 MSE 边下边播，最怕代理层预读/超时/固定长度把流截断导致几秒就停。
     */
    private Response flvProxy(IHTTPSession session) {
        String target = param(session, "url");
        if (target == null || target.isEmpty()) return notFound();
        String ua = param(session, "ua");
        if (ua == null || ua.isEmpty()) ua = UA;
        try {
            HttpURLConnection conn = openFollow(target, ua, null, 0);   // 0 = 无读超时
            int code = conn.getResponseCode();
            String ctype = conn.getContentType();
            if (ctype == null || ctype.isEmpty()) ctype = "video/x-flv";
            InputStream is = conn.getInputStream();
            Response r = newChunkedResponse(Response.Status.OK, ctype, is);
            r.addHeader("Access-Control-Allow-Origin", "*");
            r.addHeader("Cache-Control", "no-store");
            r.addHeader("Access-Control-Expose-Headers", "Content-Type");
            return r;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                    "flv proxy error: " + e.getMessage());
        }
    }

    private Response hlsProxy(IHTTPSession session) {
        String target = param(session, "url");
        if (target == null || target.isEmpty()) return notFound();
        // 频道可指定专属 User-Agent（如 aptv 源要求 AptvPlayer-UA，否则 404）
        String ua = param(session, "ua");
        if (ua == null || ua.isEmpty()) ua = UA;
        try {
            String range = header(session, "Range");
            // 手动跟随 302/301：直播源常先跳转到真实 CDN，且跳转后必须继续带 UA
            HttpURLConnection conn = openFollow(target, ua, range);
            int code = conn.getResponseCode();
            String ctype = conn.getContentType();
            if (ctype == null) ctype = "application/octet-stream";
            ctype = ctype.toLowerCase(Locale.US);
            String finalUrl = conn.getURL().toString();   // 用跳转后的真实地址做相对路径基准

            // 健壮的 m3u8 识别：很多自建直播代理（如 /huya/12345）返回的 m3u8
            // Content-Type 是 text/plain 或 application/octet-stream，路径也无 .m3u8 后缀。
            // 因此预读开头字节做内容嗅探，命中 #EXTM3U 即按播放列表改写。
            InputStream raw = conn.getInputStream();
            final int SNIFF = 1024;   // 只需开头 1KB 即可判断，避免把整个 ts 片读进来
            PushbackInputStream pin = new PushbackInputStream(raw, SNIFF + 64);
            byte[] head = new byte[SNIFF];
            int hn = 0;
            try {
                while (hn < SNIFF) {
                    int r = pin.read(head, hn, SNIFF - hn);
                    if (r <= 0) break;
                    hn += r;
                    // 已能判断是播放列表就立即停止；否则凑够 1KB（覆盖 BOM/空行/注释）
                    if (looksLikeM3u8(new String(head, 0, hn, "ISO-8859-1"))) break;
                }
            } catch (Exception ignored) {
            }
            String headStr = hn > 0 ? new String(head, 0, hn, "ISO-8859-1") : "";
            boolean isM3u8 = looksLikeM3u8(headStr)
                    || ctype.contains("mpegurl") || ctype.contains("m3u8")
                    || finalUrl.toLowerCase(Locale.US).contains(".m3u8")
                    || target.toLowerCase(Locale.US).contains(".m3u8");

            if (isM3u8) {
                byte[] rest = readAll(pin);           // 播放列表通常很小，已读头部一并拼接
                byte[] full = new byte[hn + rest.length];
                System.arraycopy(head, 0, full, 0, hn);
                System.arraycopy(rest, 0, full, hn, rest.length);
                String text = new String(full, "UTF-8");
                String rewritten = rewriteM3u8(text, finalUrl, ua);
                Response r = newFixedLengthResponse(Response.Status.lookup(code) == null ? Response.Status.OK : Response.Status.lookup(code),
                        "application/vnd.apple.mpegurl", rewritten);
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Cache-Control", "no-store");
                return r;
            }
            // 二进制流：把预读的字节退回，再透传（含 Range/206）
            if (hn > 0) pin.unread(head, 0, hn);
            long len = -1;
            String cl = conn.getHeaderField("Content-Length");
            if (cl != null) try {
                len = Long.parseLong(cl.trim());
            } catch (NumberFormatException ignored) {
            }
            Response r;
            Response.Status st = code == 206 ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
            if (len >= 0) {
                r = newFixedLengthResponse(st, ctype, pin, len);
            } else {
                r = newChunkedResponse(st, ctype, pin);
            }
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null) r.addHeader("Content-Range", cr);
            r.addHeader("Access-Control-Allow-Origin", "*");
            return r;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                    "proxy error: " + e.getMessage());
        }
    }

    private String header(IHTTPSession session, String name) {
        Map<String, String> h = session.getHeaders();
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /** 内容嗅探：判断一段文本是否为 HLS 播放列表（容忍 UTF-8 BOM / 前导空白换行） */
    private static boolean looksLikeM3u8(String s) {
        if (s == null) return false;
        int i = 0;
        // 跳过 BOM：Unicode \uFEFF，以及用 ISO-8859-1 读取时的 UTF-8 BOM 字节串 ï»¿
        if (s.length() >= 3 && s.charAt(0) == '\uFEFF') i = 3;
        else if (s.length() >= 3 && s.charAt(0) == '\u00EF' && s.charAt(1) == '\u00BB' && s.charAt(2) == '\u00BF') i = 3;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\uFEFF') { i++; continue; }
            break;
        }
        if (i >= s.length()) return false;
        String probe = s.substring(i).toUpperCase(Locale.US);
        // 真实 m3u8 一定以 #EXTM3U 开头；容错也接受 #EXT-X 起始的片段
        return probe.startsWith("#EXTM3U") || probe.startsWith("#EXT-X-");
    }

    /** m3u8 内容改写：相对路径 → 绝对 URL → /hls?url=enc(...)，保持 EXT-X-KEY 等 URI 原样可播 */
    private static String rewriteM3u8(String text, String base, String ua) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append(line).append('\n');
                continue;
            }
            if (t.startsWith("#")) {
                // 改写 URI="..." 属性（KEY / MAP / MEDIA / I-FRAME）
                Matcher m = Pattern.compile("(URI=\")([^\"]+)(\")").matcher(t);
                StringBuffer lr = new StringBuffer();
                while (m.find()) {
                    String abs = absolutize(m.group(2), base);
                    m.appendReplacement(lr, Matcher.quoteReplacement("URI=\"" + proxyUrl(abs, ua) + "\""));
                }
                m.appendTail(lr);
                sb.append(lr.toString()).append('\n');
            } else {
                String abs = absolutize(t, base);
                sb.append(proxyUrl(abs, ua)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 跟随 301/302 跳转拉取连接，每一跳都带上指定 UA；最多 5 跳防环 */
    private static HttpURLConnection openFollow(String target, String ua, String range) throws IOException {
        return openFollow(target, ua, range, 20000);
    }

    /** readTimeoutMs=0 表示无读超时（直播长流用，避免几十秒无数据被切断） */
    private static HttpURLConnection openFollow(String target, String ua, String range, int readTimeoutMs) throws IOException {
        String cur = target;
        HttpURLConnection conn = null;
        for (int i = 0; i < 5; i++) {
            URL url = new URL(cur);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Referer", url.getProtocol() + "://" + url.getHost() + "/");
            if (range != null) conn.setRequestProperty("Range", range);
            int c = conn.getResponseCode();
            if (c == 301 || c == 302 || c == 303 || c == 307 || c == 308) {
                String loc = conn.getHeaderField("Location");
                if (loc == null || loc.isEmpty()) break;
                URL next = new URL(new URL(cur), loc);
                cur = next.toString();
                conn.disconnect();
                continue;
            }
            return conn;   // 非跳转：交给调用方读取
        }
        return conn;
    }

    private static String absolutize(String u, String base) {
        try {
            return new URL(new URL(base), u).toString();
        } catch (MalformedURLException e) {
            return u;
        }
    }

    private static String proxyUrl(String abs, String ua) {
        String p = "/hls?url=" + enc(abs);
        if (ua != null && !ua.isEmpty()) p += "&ua=" + enc(ua);
        return p;
    }

    // ---------------------------------------------------------------- HTTP 工具

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private JSONObject httpGetJson(String url) throws IOException, JSONException {
        return httpGetJson(url, 8000);
    }

    private JSONObject httpGetJson(String url, int timeoutMs) throws IOException, JSONException {
        return new JSONObject(httpGetString(url, timeoutMs));
    }

    private String httpGetString(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json, text/html, */*");
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("HTTP " + code);
        return new String(readAll(is), "UTF-8");
    }
}
