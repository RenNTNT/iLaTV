package com.ilatv.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * SMB 网盘：配置存取 + 目录浏览 + 视频流打开。
 *
 * <p>配置存在 TvServer 的 config.json 里（key = smbShares），因此手机后台、
 * 电视首页、数据备份导入导出天然共用同一份数据。密码只落本机文件，
 * 对外 API 一律不回传明文（编辑时留空即表示「不修改」）。
 *
 * <p>路径全部用 / 分隔，并且用 new SmbFile(parent, name) 逐级构造，
 * 不走 URL 字符串拼接——中文目录名、空格、特殊字符都能正确工作，
 * 也顺带避免了路径穿越（每段都单独校验）。
 */
public final class SmbStore {

    private static final String KEY = "smbShares";

    /** 常见的本地视频文件；ExoPlayer 对 mp4/mkv/ts/webm 支持最好 */
    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mkv", "avi", "mov", "ts", "m2ts", "mts", "webm",
            "flv", "wmv", "mpg", "mpeg", "3gp", "rmvb", "rm", "vob", "divx", "f4v"));

    /** CIFSContext 构造有点重，按配置签名缓存；配置一改就整体清空 */
    private static final ConcurrentHashMap<String, CIFSContext> CTX_CACHE = new ConcurrentHashMap<>();

    private SmbStore() {}

    // ---------------------------------------------------------------- 配置

    /** 原始配置数组（含密码），仅供服务端内部使用 */
    public static JSONArray rawShares(JSONObject config) {
        JSONArray a = config.optJSONArray(KEY);
        return a == null ? new JSONArray() : a;
    }

    /** 对外输出：去掉密码，只给一个 hasPass 标记 */
    public static JSONArray publicShares(JSONObject config) {
        JSONArray src = rawShares(config);
        JSONArray out = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject s = src.optJSONObject(i);
            if (s == null) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("id", s.optString("id", ""));
                o.put("name", s.optString("name", ""));
                o.put("host", s.optString("host", ""));
                o.put("port", s.optInt("port", 445));
                o.put("share", s.optString("share", ""));
                o.put("domain", s.optString("domain", ""));
                o.put("user", s.optString("user", ""));
                o.put("root", s.optString("root", "/"));
                o.put("hasPass", !s.optString("pass", "").isEmpty());
                out.put(o);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    public static JSONObject find(JSONObject config, String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray a = rawShares(config);
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s != null && id.equals(s.optString("id", ""))) return s;
        }
        return null;
    }

    /**
     * 新增 / 编辑一个网盘。input 里 pass 为空时：
     * 新增 = 匿名，编辑 = 保留原密码（前端不回显明文，留空即不改）。
     * 返回 {ok, id} 或 {ok:false, error}。
     */
    public static JSONObject saveShare(JSONObject config, JSONObject input) {
        JSONObject out = new JSONObject();
        try {
            String host = input.optString("host", "").trim();
            String share = input.optString("share", "").trim();
            if (host.isEmpty()) { out.put("ok", false); out.put("error", "服务器地址不能为空"); return out; }
            if (share.isEmpty()) { out.put("ok", false); out.put("error", "共享名不能为空"); return out; }

            String id = input.optString("id", "").trim();
            String oldPass = "";
            int replaceAt = -1;
            JSONArray arr = rawShares(config);
            if (id.isEmpty()) {
                id = "s" + System.currentTimeMillis();
            } else {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s != null && id.equals(s.optString("id", ""))) {
                        replaceAt = i;
                        oldPass = s.optString("pass", "");
                        break;
                    }
                }
            }

            String pass = input.optString("pass", "");
            if (pass.isEmpty() && replaceAt >= 0) pass = oldPass;

            JSONObject s = new JSONObject();
            s.put("id", id);
            s.put("name", input.optString("name", "").trim());
            s.put("host", host);
            s.put("port", Math.max(1, Math.min(65535, input.optInt("port", 445))));
            s.put("share", share);
            s.put("domain", input.optString("domain", "").trim());
            s.put("user", input.optString("user", "").trim());
            s.put("pass", pass);
            String root = normalizePath(input.optString("root", "/"));
            s.put("root", root);

            if (replaceAt >= 0) arr.put(replaceAt, s);
            else arr.put(s);
            config.put(KEY, arr);
            CTX_CACHE.clear();

            out.put("ok", true);
            out.put("id", id);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", "保存失败：" + e.getMessage());
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    public static JSONObject deleteShare(JSONObject config, String id) {
        JSONObject out = new JSONObject();
        try {
            JSONArray arr = rawShares(config);
            JSONArray next = new JSONArray();
            boolean hit = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && id.equals(s.optString("id", ""))) { hit = true; continue; }
                next.put(s);
            }
            config.put(KEY, next);
            CTX_CACHE.clear();
            out.put("ok", hit);
            if (!hit) out.put("error", "未找到该网盘");
        } catch (Exception e) {
            try { out.put("ok", false); out.put("error", e.getMessage()); } catch (JSONException ignored) {}
        }
        return out;
    }

    // ---------------------------------------------------------------- 浏览

    /** 列出某个网盘某个目录下的文件夹与视频文件 */
    public static JSONObject browse(JSONObject config, String id, String path) {
        JSONObject out = new JSONObject();
        JSONObject share = find(config, id);
        try {
            if (share == null) { out.put("ok", false); out.put("error", "网盘不存在，请重新添加"); return out; }
            String cur = normalizePath(path == null || path.isEmpty() ? share.optString("root", "/") : path);
            SmbFile dir = fileFor(share, cur, true);

            SmbFile[] files = dir.listFiles();
            List<Item> items = new ArrayList<>();
            if (files != null) {
                for (SmbFile f : files) {
                    try {
                        String nm = trimSlash(f.getName());
                        if (nm.isEmpty() || nm.startsWith(".")) continue;
                        boolean isDir = f.isDirectory();
                        long size = isDir ? 0 : f.length();
                        items.add(new Item(nm, isDir, size,
                                joinPath(cur, nm), isDir ? false : isVideo(nm)));
                    } catch (Exception ignored) {
                        // 单个文件没权限/掉线不影响整个列表
                    }
                }
            }
            Collections.sort(items, new Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    if (a.dir != b.dir) return a.dir ? -1 : 1;   // 文件夹在前
                    return a.name.compareToIgnoreCase(b.name);
                }
            });

            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("name", it.name);
                o.put("dir", it.dir);
                o.put("size", it.size);
                o.put("path", it.path);
                o.put("video", it.video);
                arr.put(o);
            }
            out.put("ok", true);
            out.put("id", id);
            out.put("name", share.optString("name", share.optString("host", "SMB")));
            out.put("path", cur);
            out.put("parent", parentPath(cur, share.optString("root", "/")));
            out.put("items", arr);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", friendlyError(e));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 搜索（轻量版）

    // 实时遍历的保护阀：目录层级太深 / 共享里文件太多 / NAS 响应慢时都要能及时收手，
    // 不能因为一次搜索把内置 HTTP 服务或电视主线程拖住。
    private static final int SEARCH_MAX_DEPTH = 6;
    private static final int SEARCH_MAX_DIRS = 300;
    private static final int SEARCH_MAX_HITS = 80;
    private static final long SEARCH_TIMEOUT_MS = 9000;

    /**
     * 按文件名模糊搜索所有已添加 SMB 网盘里的视频。
     *
     * <p>轻量版：不建索引，收到关键词时实时递归列目录，命中规则——
     * ① 文件名（去扩展名）包含关键词的全部子串；② 所在目录名命中时，
     * 该目录下所有视频都算命中（片名做文件夹、集数做文件名的常见存法）。
     * 中文、大小写、多关键词（空格/点/下划线分隔，AND）都支持；不做拼音首字母。
     *
     * <p>单个网盘连不上或超时只跳过它，不影响其它网盘的结果。
     */
    public static JSONObject search(JSONObject config, String wd) {
        JSONObject out = new JSONObject();
        try {
            String q = wd == null ? "" : wd.trim();
            out.put("ok", true);
            out.put("wd", q);
            JSONArray shares = rawShares(config);
            out.put("hasShares", shares.length() > 0);
            out.put("items", new JSONArray());
            out.put("count", 0);
            out.put("truncated", false);
            if (q.isEmpty() || shares.length() == 0) return out;

            String[] keys = searchKeys(q);
            final long deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS;
            final int[] dirCount = {0};
            List<Hit> hits = new ArrayList<>();
            boolean truncated = false;

            for (int i = 0; i < shares.length(); i++) {
                JSONObject sh = shares.optJSONObject(i);
                if (sh == null) continue;
                if (System.currentTimeMillis() > deadline
                        || dirCount[0] >= SEARCH_MAX_DIRS || hits.size() >= SEARCH_MAX_HITS) {
                    truncated = true;
                    break;
                }
                try {
                    String root = normalizePath(sh.optString("root", "/"));
                    walk(sh, root, fileFor(sh, root, true), keys, 0, false, hits, dirCount, deadline);
                } catch (Exception ignored) {
                    // 某个网盘掉线 / 账号错，跳过继续搜下一个
                }
            }
            if (dirCount[0] >= SEARCH_MAX_DIRS || System.currentTimeMillis() > deadline) truncated = true;

            Collections.sort(hits, new Comparator<Hit>() {
                @Override public int compare(Hit a, Hit b) {
                    if (a.rank != b.rank) return a.rank - b.rank;     // 完全匹配 > 前缀 > 包含 > 目录继承
                    return a.name.compareToIgnoreCase(b.name);
                }
            });

            JSONArray arr = new JSONArray();
            int n = Math.min(hits.size(), SEARCH_MAX_HITS);
            for (int i = 0; i < n; i++) {
                Hit h = hits.get(i);
                JSONObject o = new JSONObject();
                o.put("id", h.id);
                o.put("shareName", h.shareName);
                o.put("name", h.name);
                o.put("path", h.path);
                o.put("parent", h.parent);     // 前端据此拉同目录视频组成选集
                o.put("size", h.size);
                arr.put(o);
            }
            out.put("items", arr);
            out.put("count", arr.length());
            out.put("truncated", truncated || hits.size() > n);
            out.put("dirs", dirCount[0]);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", friendlyError(e));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private static void walk(JSONObject share, String path, SmbFile dir, String[] keys,
                             int depth, boolean inherited, List<Hit> hits,
                             int[] dirCount, long deadline) {
        if (depth > SEARCH_MAX_DEPTH || dirCount[0] >= SEARCH_MAX_DIRS
                || hits.size() >= SEARCH_MAX_HITS || System.currentTimeMillis() > deadline) return;
        dirCount[0]++;

        // 父目录命中后，所有子目录继承命中（/电影/庆余年/第一季/E01.mp4 也能搜到「庆余年」）
        boolean folderMatch = inherited || matches(dirNameOf(path), keys);
        SmbFile[] fs;
        try {
            fs = dir.listFiles();
        } catch (Exception e) {
            return;
        }
        if (fs == null) return;

        for (SmbFile f : fs) {
            if (hits.size() >= SEARCH_MAX_HITS || System.currentTimeMillis() > deadline) return;
            try {
                String nm = trimSlash(f.getName());
                if (nm.isEmpty() || nm.startsWith(".")) continue;
                String child = joinPath(path, nm);
                if (f.isDirectory()) {
                    if (depth + 1 <= SEARCH_MAX_DEPTH)
                        walk(share, child, f, keys, depth + 1, folderMatch, hits, dirCount, deadline);
                } else if (isVideo(nm)) {
                    String base = stripExt(nm);
                    int rank = folderMatch ? 3 : (matches(base, keys) ? rankOf(base, keys) : -1);
                    if (rank >= 0) {
                        long sz = 0;
                        try { sz = f.length(); } catch (Exception ignored) {}
                        hits.add(new Hit(share.optString("id", ""),
                                share.optString("name", share.optString("host", "SMB")),
                                nm, child, path, sz, rank));
                    }
                }
            } catch (Exception ignored) {
                // 单个文件取属性失败不影响整体
            }
        }
    }

    /** 关键词按常见分隔符拆成多个子串，命中要求全部出现（AND） */
    private static String[] searchKeys(String q) {
        String low = q.toLowerCase(Locale.US).trim();
        List<String> ks = new ArrayList<>();
        for (String s : low.split("[\\s,，、/\\\\._-]+")) {
            if (!s.isEmpty() && !ks.contains(s)) ks.add(s);
        }
        if (ks.isEmpty() && !low.isEmpty()) ks.add(low);
        return ks.toArray(new String[0]);
    }

    private static boolean matches(String target, String[] keys) {
        if (keys.length == 0) return false;
        String t = target.toLowerCase(Locale.US);
        for (String k : keys) if (!t.contains(k)) return false;
        return true;
    }

    private static int rankOf(String base, String[] keys) {
        String t = base.toLowerCase(Locale.US);
        String first = keys[0];
        if (t.equals(first)) return 0;
        if (t.startsWith(first)) return 1;
        return 2;
    }

    private static String dirNameOf(String path) {
        String p = normalizePath(path);
        if (p.equals("/")) return "";
        int k = p.lastIndexOf('/');
        return k < 0 ? p : p.substring(k + 1);
    }

    private static String stripExt(String name) {
        int k = name.lastIndexOf('.');
        if (k > 0 && VIDEO_EXT.contains(name.substring(k + 1).toLowerCase(Locale.US)))
            return name.substring(0, k);
        return name;
    }

    private static final class Hit {
        final String id, shareName, name, path, parent;
        final long size;
        final int rank;
        Hit(String id, String shareName, String name, String path, String parent, long size, int rank) {
            this.id = id; this.shareName = shareName; this.name = name;
            this.path = path; this.parent = parent; this.size = size; this.rank = rank;
        }
    }

    /** 打开一个视频文件：返回可 skip 的输入流 + 总长度 + MIME，供 /smb 做 Range 代理 */
    public static Stream open(JSONObject config, String id, String path) throws Exception {
        JSONObject share = find(config, id);
        if (share == null) throw new IllegalArgumentException("网盘不存在");
        SmbFile f = fileFor(share, normalizePath(path), false);
        long len = f.length();
        InputStream in = f.getInputStream();
        return new Stream(in, len, mimeOf(f.getName()));
    }

    public static final class Stream {
        public final InputStream in;
        public final long length;
        public final String mime;
        Stream(InputStream in, long length, String mime) {
            this.in = in; this.length = length; this.mime = mime;
        }
    }

    private static final class Item {
        final String name; final boolean dir; final long size; final String path; final boolean video;
        Item(String n, boolean d, long s, String p, boolean v) { name = n; dir = d; size = s; path = p; video = v; }
    }

    // ---------------------------------------------------------------- SMB 上下文

    private static CIFSContext context(JSONObject share) throws Exception {
        String key = share.optString("id", "") + "|" + share.optString("host", "") + "|"
                + share.optInt("port", 445) + "|" + share.optString("share", "") + "|"
                + share.optString("domain", "") + "|" + share.optString("user", "") + "|"
                + share.optString("pass", "").hashCode();
        CIFSContext cached = CTX_CACHE.get(key);
        if (cached != null) return cached;

        Properties p = new Properties();
        // 让 SMB1 ~ SMB3 自动协商：老 NAS 只开 SMB1，新 NAS 关了 SMB1 也能连
        p.setProperty("jcifs.smb.client.minVersion", "SMB1");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB300");
        p.setProperty("jcifs.smb.client.responseTimeout", "10000");
        p.setProperty("jcifs.smb.client.soTimeout", "20000");
        p.setProperty("jcifs.smb.client.connTimeout", "10000");
        p.setProperty("jcifs.smb.client.dfs.disabled", "true");
        CIFSContext ctx = new BaseContext(new PropertyConfiguration(p));
        String user = share.optString("user", "").trim();
        String pass = share.optString("pass", "");
        if (!user.isEmpty() || !pass.isEmpty()) {
            ctx = ctx.withCredentials(new NtlmPasswordAuthenticator(
                    share.optString("domain", "").trim(), user, pass));
        }
        CTX_CACHE.put(key, ctx);
        return ctx;
    }

    /**
     * 逐级构造 SmbFile：smb://host[:port]/ → share/ → path 每一级。
     * dir=true 时最后一段补 / 表示目录。每段都拒绝 .. \ : 等，避免越出共享根。
     */
    private static SmbFile fileFor(JSONObject share, String path, boolean dir) throws Exception {
        String host = share.optString("host", "").trim();
        int port = share.optInt("port", 445);
        StringBuilder sb = new StringBuilder("smb://");
        sb.append(host);
        if (port > 0 && port != 445) sb.append(':').append(port);
        sb.append('/');

        CIFSContext ctx = context(share);
        SmbFile cur = new SmbFile(sb.toString(), ctx);
        cur = new SmbFile(cur, trimSlash(share.optString("share", "")) + "/");

        List<String> segs = splitPath(path);
        for (int i = 0; i < segs.size(); i++) {
            String seg = segs.get(i);
            boolean last = (i == segs.size() - 1);
            String nm = seg + ((!last || dir) ? "/" : "");
            cur = new SmbFile(cur, nm);
        }
        return cur;
    }

    private static List<String> splitPath(String path) {
        List<String> out = new ArrayList<>();
        String p = normalizePath(path);
        if (p.equals("/")) return out;
        for (String s : p.split("/")) {
            if (s.isEmpty()) continue;
            if (s.equals(".") || s.equals("..") || s.indexOf('\\') >= 0
                    || s.indexOf(':') >= 0 || s.indexOf('\0') >= 0)
                throw new IllegalArgumentException("路径不合法：" + s);
            out.add(s);
        }
        return out;
    }

    private static String normalizePath(String p) {
        if (p == null || p.trim().isEmpty()) return "/";
        String s = p.trim().replace('\\', '/');
        if (!s.startsWith("/")) s = "/" + s;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        // 简单收敛 . 与 ..，避免客户端传相对片段越权
        StringBuilder b = new StringBuilder("/");
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                int k = b.lastIndexOf("/", b.length() - 2);
                if (k >= 0) b.setLength(k + 1);
                continue;
            }
            b.append(seg).append('/');
        }
        String r = b.toString();
        return r.length() > 1 ? r.substring(0, r.length() - 1) : "/";
    }

    private static String joinPath(String base, String name) {
        return base.equals("/") ? "/" + name : base + "/" + name;
    }

    /** 上级目录；已经到起始目录或根时返回空串，前端据此禁用「返回上级」 */
    private static String parentPath(String cur, String root) {
        String r = normalizePath(root);
        if (cur.equals("/") || cur.equals(r)) return "";
        int k = cur.lastIndexOf('/');
        return k <= 0 ? "/" : cur.substring(0, k);
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int k = s.lastIndexOf('/');
        return k >= 0 ? s.substring(k + 1) : s;
    }

    private static boolean isVideo(String name) {
        int k = name.lastIndexOf('.');
        return k >= 0 && VIDEO_EXT.contains(name.substring(k + 1).toLowerCase(Locale.US));
    }

    private static String mimeOf(String name) {
        int k = name.lastIndexOf('.');
        String e = k >= 0 ? name.substring(k + 1).toLowerCase(Locale.US) : "";
        if ("mp4".equals(e) || "m4v".equals(e)) return "video/mp4";
        if ("mkv".equals(e)) return "video/x-matroska";
        if ("webm".equals(e)) return "video/webm";
        if ("ts".equals(e) || "m2ts".equals(e) || "mts".equals(e)) return "video/mp2t";
        if ("avi".equals(e)) return "video/x-msvideo";
        if ("mov".equals(e)) return "video/quicktime";
        if ("flv".equals(e)) return "video/x-flv";
        return "application/octet-stream";
    }

    private static String friendlyError(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.toLowerCase(Locale.US).contains("logon failure")
                || m.toLowerCase(Locale.US).contains("auth")
                || m.toLowerCase(Locale.US).contains("access denied"))
            return "用户名或密码不对 / 没有访问权限";
        if (m.toLowerCase(Locale.US).contains("timeout") || m.toLowerCase(Locale.US).contains("refused")
                || m.toLowerCase(Locale.US).contains("unreachable") || m.toLowerCase(Locale.US).contains("failed to connect"))
            return "连不上服务器：检查地址、端口和同一局域网";
        if (m.toLowerCase(Locale.US).contains("not exist") || m.toLowerCase(Locale.US).contains("not found")
                || m.toLowerCase(Locale.US).contains("cannot find"))
            return "目录或共享名不存在";
        return "读取失败：" + m;
    }
}
