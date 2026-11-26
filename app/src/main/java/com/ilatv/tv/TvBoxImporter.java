package com.ilatv.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TVBox 订阅「A 档」解析器：只做纯配置层的事情，不加载任何外部代码。
 *
 * 输入：一个订阅 URL，或直接粘贴的配置文本。
 * 支持：明文 JSON / Base64 / TVBox「8位随机串**Base64」加密 / gzip / zip 离线包 /
 *       多仓 txt（每行一个订阅地址）/ UTF-8 BOM / 前后空白与注释包裹。
 *
 * 输出：把 sites 里「能当苹果 CMS JSON 采集接口用」的站点、lives 里 type=0 的
 *       m3u/txt 直播订阅提取成候选数组，交给 TvServer 去重合并进现有配置。
 *
 * 明确不支持（计数跳过，不报错）：
 *   - sites.type=3 Spider / csp_* 类站点（需 DexClassLoader 执行 jar，属 C 档）
 *   - sites.type=4 remote、type=0 XML（本工程 /api/search 只解析 JSON）
 *   - parses / flags / ijk / doh / epg / logo / wallpaper 等非源字段
 *   - clan:// 本地路径、proxy:// 等自定义协议
 *
 * 纯 Java + org.json，无 Android 依赖（Base64 自己实现，便于桌面 JVM 单测）。
 */
public final class TvBoxImporter {

    private static final String UA = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 iLaTV/TVBoxImporter";
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_REPOS = 8;          // 多仓 txt 最多展开几个订阅
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private TvBoxImporter() {}

    public static final class Result {
        public final JSONArray vod = new JSONArray();     // [{name,url,type}]
        public final JSONArray live = new JSONArray();   // [{name,url,type,epg}]
        public final List<String> warnings = new ArrayList<String>();
        public String name = "";
        public int sitesTotal;
        public int livesTotal;
        public int unsupported;
    }

    /** 合并结果：items 是最终应落盘的完整数组（保留原有条目原样），added/skipped 只统计候选 */
    public static final class Merged {
        public final JSONArray items = new JSONArray();
        public int added;
        public int skipped;
    }

    /**
     * 按规范化后的 url 去重合并（append）或从空开始（replace）。
     * 纯数据操作，不落盘、不依赖 Android，便于桌面 JVM 单测；dryRun 由调用方决定是否写回。
     * 候选里的 name/url/enabled 会复制成新对象；current 里的对象原样保留（不丢 enabled 等字段）。
     */
    public static Merged merge(JSONArray current, JSONArray candidates, boolean replace) {
        Merged m = new Merged();
        List<String> seen = new ArrayList<String>();
        if (!replace && current != null) {
            for (int i = 0; i < current.length(); i++) {
                JSONObject o = current.optJSONObject(i);
                if (o == null) continue;
                String u = normalize(o.optString("url", ""));
                if (u.isEmpty() || seen.contains(u)) continue;
                seen.add(u);
                m.items.put(o);
            }
        }
        if (candidates != null) {
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject s = candidates.optJSONObject(i);
                if (s == null) continue;
                String u = normalize(s.optString("url", ""));
                if (!isHttp(u) || seen.contains(u)) { m.skipped++; continue; }
                seen.add(u);
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", s.optString("name", "未命名源"));
                    o.put("url", u);
                    o.put("enabled", true);
                    if (s.has("epg")) o.put("epg", s.optString("epg", ""));
                    m.items.put(o);
                    m.added++;
                } catch (JSONException ignored) {}
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- 对外入口

    /** @param url 订阅地址（可空）  @param raw 直接粘贴的配置文本（可空，优先于 url） */
    public static Result load(String url, String raw) throws Exception {
        Result r = new Result();
        String seed = raw == null ? "" : raw.trim();
        String source = url == null ? "" : url.trim();

        if (!seed.isEmpty()) {
            // 粘贴内容：可能就是 JSON，也可能是加密串 / 多仓列表
            consume(seed.getBytes("UTF-8"), r, null, 0);
        } else {
            if (source.isEmpty()) throw new IllegalArgumentException("订阅地址为空");
            if (!isHttp(source)) throw new IllegalArgumentException("只支持 http(s):// 订阅地址");
            fetchInto(source, r, 0);
        }

        if (r.sitesTotal + r.livesTotal == 0) {
            r.warnings.add("没有读到 sites / lives，检查订阅地址是否返回 TVBox 配置 JSON");
        }
        return r;
    }

    /** 递归拉取；多仓 txt 会把每行当作新订阅地址继续展开 */
    private static void fetchInto(String url, Result r, int depth) throws Exception {
        if (depth > 2) { r.warnings.add("多仓嵌套过深，已停止展开：" + url); return; }
        byte[] bytes = httpGetBytes(url);
        consume(bytes, r, url, depth);
    }

    // ---------------------------------------------------------------- 解码分发

    private static void consume(byte[] bytes, Result r, String baseUrl, int depth) throws Exception {
        byte[] data = decompress(bytes);
        String text = decodeText(data);
        text = stripBom(text).trim();

        // 1) TVBox 加密配置：8 位随机串 ** Base64
        int star = text.indexOf("**");
        if (star >= 0 && star < 64) {
            String b64 = text.substring(star + 2).trim();
            String dec = decodeText(b64Decode(b64));
            if (looksJson(dec)) { collectConfig(parseConfig(dec), r); return; }
        }

        // 2) 整体 Base64（无 ** 前缀）
        if (!looksJson(text) && looksBase64(text)) {
            String dec = decodeText(b64Decode(text));
            if (looksJson(dec)) { collectConfig(parseConfig(dec), r); return; }
        }

        // 3) 明文 JSON（允许前面有注释 / 后面有多余字符）
        if (looksJson(text) || text.indexOf('{') >= 0) {
            JSONObject cfg = parseConfig(text);
            if (cfg != null) { collectConfig(cfg, r); return; }
        }

        // 4) 多仓 txt：每行一个订阅地址（支持「名称,url」）
        List<String> repos = parseRepoLines(text);
        if (!repos.isEmpty()) {
            r.warnings.add("识别为多仓列表，共 " + repos.size() + " 个订阅");
            int n = 0;
            for (String u : repos) {
                if (n++ >= MAX_REPOS) { r.warnings.add("超过 " + MAX_REPOS + " 个，其余已跳过"); break; }
                try {
                    fetchInto(u, r, depth + 1);
                } catch (Exception e) {
                    r.warnings.add("跳过失败订阅 " + shortUrl(u) + "：" + e.getMessage());
                }
            }
            return;
        }

        throw new IllegalArgumentException("无法识别配置格式（既不是 JSON/Base64，也不是多仓列表）");
    }

    /** gzip / zip 自动解；zip 取包内第一个 .json（按大小取最大的更保险） */
    private static byte[] decompress(byte[] b) throws IOException {
        if (b.length >= 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(b));
            return readAll(gz);
        }
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(b));
            ZipEntry e;
            byte[] best = null;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().toLowerCase();
                if (e.isDirectory() || !(n.endsWith(".json") || n.endsWith(".txt"))) continue;
                // 不能用 readAll()：它会 close 传入的流，而这里就是 ZipInputStream 本身，
                // 关掉后下一次 getNextEntry() 会抛 "Stream closed"。
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int k;
                while ((k = zis.read(buf)) > 0) {
                    bos.write(buf, 0, k);
                    if (bos.size() > MAX_BYTES) break;
                }
                byte[] cur = bos.toByteArray();
                if (cur.length > MAX_BYTES) continue;
                if (best == null || cur.length > best.length) best = cur;
            }
            zis.close();
            if (best != null) return best;
            throw new IOException("zip 包内没找到 .json / .txt 配置");
        }
        if (b.length > MAX_BYTES) throw new IOException("配置超过 4MB，已拒绝");
        return b;
    }

    // ---------------------------------------------------------------- 字段提取

    private static void collectConfig(JSONObject cfg, Result r) {
        if (cfg == null) return;
        if (r.name.isEmpty()) {
            String n = cfg.optString("name", "");
            if (n.isEmpty()) n = cfg.optString("title", "");
            r.name = n.trim();
        }

        JSONArray sites = cfg.optJSONArray("sites");
        if (sites != null) {
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s == null) continue;
                r.sitesTotal++;
                String api = s.optString("api", "").trim();
                String name = s.optString("name", "").trim();
                // 关键：TVBox 里 type 省略时默认是 3(Spider)，但很多「CMS JSON」站点压根不写 type。
                // 这里区分「没写 type」(-1，按 api 形态猜) 与「显式写了 0/1/3/4」。
                int type = s.has("type") ? s.optInt("type", 3) : -1;
                if (!isHttp(api) || !isCmsSite(type, api)) { r.unsupported++; continue; }
                if (name.isEmpty()) name = "TVBox 源 " + (r.vod.length() + 1);
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", cleanName(name));
                    o.put("url", normalize(api));
                    o.put("type", type);
                    r.vod.put(o);
                } catch (JSONException ignored) {}
            }
        }

        JSONArray lives = cfg.optJSONArray("lives");
        if (lives != null) {
            for (int i = 0; i < lives.length(); i++) {
                JSONObject s = lives.optJSONObject(i);
                if (s == null) continue;
                r.livesTotal++;
                String u = s.optString("url", "").trim();
                String name = s.optString("name", "").trim();
                int type = s.optInt("type", 0);
                if (!isHttp(u) || type != 0) { r.unsupported++; continue; }
                if (name.isEmpty()) name = "TVBox 直播 " + (r.live.length() + 1);
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", cleanName(name));
                    o.put("url", normalize(u));
                    o.put("type", type);
                    String epg = s.optString("epg", "").trim();
                    if (!epg.isEmpty()) o.put("epg", epg);
                    r.live.put(o);
                } catch (JSONException ignored) {}
            }
        }
    }

    /**
     * A 档只吃「苹果 CMS JSON 采集接口」：
     *   type=1 直接收；type=3(Spider/JAR)、4(remote) 一律跳过；
     *   显式 type=0(XML) 只有地址本身长得像 CMS JSON 采集口才收（本工程只发 JSON 请求）；
     *   没写 type(-1) 按地址形态猜，兼容大量不写 type 的 CMS 站点。
     */
    private static boolean isCmsSite(int type, String api) {
        if (type == 3 || type == 4) return false;
        if (type == 1) return true;
        String a = api.toLowerCase();
        return a.contains("api.php/provide/vod") || a.contains("api.php/vod")
                || a.contains("ac=videolist") || a.contains("/provide/vod");
    }

    // ---------------------------------------------------------------- 小工具

    private static byte[] httpGetBytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) throw new IOException("HTTP " + code);
        byte[] b = readAll(is);
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code);
        return b;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_BYTES * 2) throw new IOException("响应过大，已截断");
        }
        is.close();
        return bos.toByteArray();
    }

    private static String decodeText(byte[] b) {
        try { return new String(b, "UTF-8"); }
        catch (Exception ignored) { return new String(b); }
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** 允许配置前后有注释 / 包裹字符：截出最外层 {...} */
    private static JSONObject parseConfig(String text) throws JSONException {
        String t = text.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new JSONException("配置里没有 JSON 对象");
        return new JSONObject(t.substring(a, b + 1));
    }

    private static boolean looksJson(String t) {
        String s = t.trim();
        return s.startsWith("{") || s.startsWith("[");
    }

    private static boolean looksBase64(String t) {
        String s = t.replaceAll("\\s", "");
        return s.length() > 20 && s.matches("^[A-Za-z0-9+/=_\\-]+$");
    }

    private static List<String> parseRepoLines(String text) {
        List<String> out = new ArrayList<String>();
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue;
            // 「名称,https://...」取最后一段；整行就是 URL 也支持
            String u = s;
            if (s.indexOf(',') >= 0) {
                String[] parts = s.split(",");
                u = parts[parts.length - 1].trim();
            }
            if (isHttp(u) && out.indexOf(u) < 0) out.add(u);
        }
        return out;
    }

    /** 手写 Base64 解码，避免依赖 android.util.Base64 / java.util.Base64(API 26+) */
    private static byte[] b64Decode(String s) throws IOException {
        String t = s.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
        int[] T = new int[256];
        Arrays.fill(T, -1);
        String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < A.length(); i++) T[A.charAt(i)] = i;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '=') break;
            int v = T[c & 0xff];
            if (v < 0) continue;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) { bits -= 8; bos.write((acc >> bits) & 0xff); }
        }
        if (bos.size() == 0) throw new IOException("Base64 解码结果为空");
        return bos.toByteArray();
    }

    private static boolean isHttp(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String normalize(String u) {
        String s = u.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 去掉站点名里常见的装饰符，保留中文与常规字符 */
    private static String cleanName(String n) {
        String s = n.replaceAll("[\\p{Cntrl}]", "").trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "未命名源" : s;
    }

    private static String shortUrl(String u) {
        return u.length() > 60 ? u.substring(0, 60) + "…" : u;
    }
}
