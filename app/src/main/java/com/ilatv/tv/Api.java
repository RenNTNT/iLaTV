package com.ilatv.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * MacCMS V10 / 苹果CMS Provide 接口对接（纯 Java + org.json）。
 * 另含直播播放列表解析：M3U / M3U8 与 TXT（#genre# 分组）按内容自动识别，
 * HTTP 响应按 UTF-8 严格解码、失败回退 GBK。
 *
 * 接口形如：https://域名/api.php/provide/vod/?ac=videolist&pg=1&wd=关键词
 * 返回 {code, msg, list:[{vod_id, vod_name, vod_pic, vod_content,
 *                         vod_play_from, vod_play_url, ...}]}
 *
 * 播放地址约定：多线路用 $$$ 分隔，同线路多集用 # 分隔，
 * 单集「名称$地址」。不同站点分隔符略有出入，这里做了容错。
 */
public final class Api {

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 12000;
    private static final int PER_SOURCE_LIMIT = 30;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36 LibreTV-Java/1.0";

    private Api() {}

    // ---------- HTTP ----------

    public static String get(String url) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            // 关键：明确要原始字节。部分 ROM / 代理下 HttpURLConnection 的自动 gzip
            // 会失效，压缩字节被当文本解码就是整页乱码（最常见的「订阅读出来乱码」）。
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IOException("HTTP " + code + " 且无响应体");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            if (code >= 400) throw new IOException("HTTP " + code);
            byte[] raw = maybeGunzip(bos.toByteArray(), conn.getHeaderField("Content-Encoding"));
            return decodeText(raw);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 响应体解压：服务器 / 中间代理可能无视 identity 仍返回 gzip。
     * br 没有 JDK 内置解压，明确报错而不是把压缩字节当文本解成乱码。
     */
    public static byte[] maybeGunzip(byte[] raw, String contentEncoding) throws IOException {
        if (raw == null || raw.length == 0) return raw;
        boolean isGzip = raw.length >= 2
                && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
        if (!isGzip && contentEncoding != null
                && contentEncoding.toLowerCase().contains("gzip")) isGzip = true;
        if (isGzip) {
            GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = gz.read(b)) != -1) out.write(b, 0, n);
            gz.close();
            return out.toByteArray();
        }
        if (contentEncoding != null
                && (contentEncoding.toLowerCase().contains("br")
                    || contentEncoding.toLowerCase().contains("brotli"))) {
            throw new IOException("服务器返回 br 压缩，无法解析；请改用 jsDelivr 等镜像地址");
        }
        return raw;
    }

    /**
     * 智能解码订阅文本（v1.13.2 强化）：
     *  1) BOM：UTF-8 / UTF-16LE / UTF-16BE / UTF-32LE / UTF-32BE
     *  2) 无 BOM 的 UTF-16：按 NUL 字节奇偶位置启发（必须放在 UTF-8 之前，
     *     因为 UTF-16LE 的 ASCII 字节序列在 UTF-8 下「合法」但会解出带 NUL 的乱码）
     *  3) 整份严格 UTF-8 成功 → 直接用（绝大多数 GitHub raw / TXT 走这条）
     *  4) 失败 → 逐行混合：每行单独判 UTF-8，失败该行用 GBK。
     *     不再「一处非法字节就把整份文件回退 GBK」——那会把本来正确的
     *     UTF-8 中文全带歪，是混合编码列表乱码的主因。
     */
    public static String decodeText(byte[] data) {
        if (data == null || data.length == 0) return "";
        int total = data.length;

        // 1) BOM
        if (startsWith(data, (byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x00))
            return decodeWith(data, 4, total - 4, "UTF-32LE");
        if (startsWith(data, (byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0xFF))
            return decodeWith(data, 4, total - 4, "UTF-32BE");
        if (startsWith(data, (byte) 0xFF, (byte) 0xFE))
            return decodeWith(data, 2, total - 2, "UTF-16LE");
        if (startsWith(data, (byte) 0xFE, (byte) 0xFF))
            return decodeWith(data, 2, total - 2, "UTF-16BE");
        int off = startsWith(data, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF) ? 3 : 0;
        int len = total - off;
        if (len <= 0) return "";

        // 2) 无 BOM 的 UTF-16 启发
        int sample = Math.min(len, 200);
        int evenNull = 0, oddNull = 0;
        for (int i = 0; i < sample; i++) {
            if (data[off + i] == 0) {
                if ((i & 1) == 0) evenNull++; else oddNull++;
            }
        }
        if ((evenNull + oddNull) > sample / 5) {
            if (oddNull > evenNull * 3) return decodeWith(data, off, len, "UTF-16LE");
            if (evenNull > oddNull * 3) return decodeWith(data, off, len, "UTF-16BE");
        }

        // 3) 快路径：整份严格 UTF-8
        String all = tryUtf8(data, off, len);
        if (all != null) return all;

        // 4) 逐行混合：0x0A 在 GBK 双字节里不会出现，可按字节安全切行
        StringBuilder sb = new StringBuilder();
        int start = off, end = total;
        for (int i = start; i < end; i++) {
            if (data[i] == 0x0A) {
                appendChunk(sb, data, start, i - start);
                sb.append('\n');
                start = i + 1;
            }
        }
        if (start < end) appendChunk(sb, data, start, end - start);
        return sb.toString();
    }

    private static boolean startsWith(byte[] d, byte... sig) {
        if (d.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if (d[i] != sig[i]) return false;
        return true;
    }

    private static String decodeWith(byte[] d, int off, int len, String enc) {
        try {
            return new String(d, off, Math.max(0, len), enc);
        } catch (Exception e) {
            return new String(d, off, Math.max(0, len), Charset.forName("UTF-8"));
        }
    }

    /** 严格 UTF-8；含任何非法字节返回 null（交给上层逐行 / GBK） */
    private static String tryUtf8(byte[] d, int off, int len) {
        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(d, off, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** 单行 / 单块：UTF-8 优先，失败该块用 GBK；不牵连其它行 */
    private static void appendChunk(StringBuilder sb, byte[] d, int off, int len) {
        if (len <= 0) return;
        String s = tryUtf8(d, off, len);
        sb.append(s != null ? s : decodeWith(d, off, len, "GBK"));
    }

    // ---------- 单源搜索 ----------

    public static List<Model.Video> search(final Model.Source src, final String kw)
            throws Exception {
        String base = src.url.trim();
        String sep = base.contains("?")
                ? (base.endsWith("?") || base.endsWith("&") ? "" : "&")
                : "?";
        String url = base + sep + "ac=videolist&pg=1&at=json&wd="
                + URLEncoder.encode(kw, "UTF-8");

        String body = get(url).trim();
        if (!body.startsWith("{")) {
            throw new IOException("返回的不是 JSON（可能是 XML 或拦截页）");
        }
        JSONObject root = new JSONObject(body);
        JSONArray list = root.optJSONArray("list");
        List<Model.Video> out = new ArrayList<>();
        if (list == null) return out;

        for (int i = 0; i < list.length() && i < PER_SOURCE_LIMIT; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Model.Video v = parseVideo(o);
            if (v.name.isEmpty()) continue;
            // 线路名带上源名，聚合后才知道该换哪个源
            for (Model.Line line : v.lines) {
                line.from = src.name + " · " + line.from;
            }
            v.sources.add(src.name);
            out.add(v);
        }
        return out;
    }

    private static Model.Video parseVideo(JSONObject o) {
        Model.Video v = new Model.Video();
        v.id = String.valueOf(o.optInt("vod_id", 0));
        v.name = clean(o.optString("vod_name", ""));
        v.pic = o.optString("vod_pic", "");
        v.remark = clean(o.optString("vod_remarks", o.optString("vod_note", "")));
        v.content = stripHtml(o.optString("vod_content", ""));
        v.lines = parsePlayUrl(
                o.optString("vod_play_from", ""),
                o.optString("vod_play_url", ""));
        return v;
    }

    /** 解析 vod_play_from / vod_play_url → 线路 + 集数 */
    public static List<Model.Line> parsePlayUrl(String fromStr, String urlStr) {
        List<Model.Line> lines = new ArrayList<>();
        if (urlStr == null || urlStr.trim().isEmpty()) return lines;

        // 线路分隔：常见 $$$，少数站点用 ####
        String[] groups = urlStr.split("\\$\\$\\$|####");
        String[] froms = fromStr.split("\\$\\$\\$|####|,");

        for (int g = 0; g < groups.length; g++) {
            String group = groups[g].trim();
            if (group.isEmpty()) continue;
            String from = (g < froms.length && !froms[g].trim().isEmpty())
                    ? froms[g].trim() : ("线路" + (g + 1));
            Model.Line line = new Model.Line(from);

            String[] segs = group.split("#");
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i].trim();
                if (seg.isEmpty()) continue;
                String epName;
                String epUrl;
                int p = seg.indexOf('$');
                if (p >= 0) {
                    epName = seg.substring(0, p).trim();
                    epUrl = seg.substring(p + 1).trim();
                } else {
                    // 容错：有的源把 $ 写成中文逗号 / 全角＄
                    int q = seg.indexOf('＄');
                    int r = seg.indexOf('，');
                    if (q >= 0 || r >= 0) {
                        int k = q >= 0 ? q : r;
                        epName = seg.substring(0, k).trim();
                        epUrl = seg.substring(k + 1).trim();
                    } else {
                        epName = "第" + (line.episodes.size() + 1) + "集";
                        epUrl = seg;
                    }
                }
                if (epUrl.isEmpty()) continue;
                if (epName.isEmpty()) epName = "第" + (line.episodes.size() + 1) + "集";
                line.episodes.add(new Model.Episode(epName, epUrl));
            }
            if (!line.episodes.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .trim();
    }

    // ---------- 多源并发聚合 ----------

    /**
     * 并发请求所有启用的源，按片名合并同名结果。
     * 任一源超时 / 出错不会影响其它源；必须在子线程调用。
     */
    public static List<Model.Video> searchAll(List<Model.Source> sources, final String kw) {
        final Map<String, Model.Video> merged = new LinkedHashMap<>();
        if (sources == null || sources.isEmpty()) return new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(6, Math.max(1, sources.size())));
        List<Future<List<Model.Video>>> futures = new ArrayList<>();
        for (final Model.Source s : sources) {
            futures.add(pool.submit(new Callable<List<Model.Video>>() {
                @Override
                public List<Model.Video> call() throws Exception {
                    return search(s, kw);
                }
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                List<Model.Video> one = futures.get(i).get(14, TimeUnit.SECONDS);
                synchronized (merged) {
                    for (Model.Video v : one) {
                        Model.Video target = merged.get(v.name);
                        if (target == null) {
                            merged.put(v.name, v);
                        } else {
                            target.lines.addAll(v.lines);
                            if (!target.sources.contains(v.sources.get(0))) {
                                target.sources.addAll(v.sources);
                            }
                            if (target.pic.isEmpty()) target.pic = v.pic;
                            if (target.content.isEmpty()) target.content = v.content;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 单源失败：跳过，其余源照常出结果
            }
        }
        pool.shutdownNow();
        return new ArrayList<>(merged.values());
    }

    // ---------- 订阅导入 ----------

    /**
     * 从公开 URL 导入源列表。兼容：
     *  - 裸数组   [{"name":"x","url":"https://..."}]
     *  - 对象包   {"sources":[...]} / {"list":[...]} / {"sites":[{"key","name","api"}]}
     * 复杂 TVBOX 配置（jar / 解析规则）不在支持范围，见 README。
     */
    public static List<Model.Source> fetchSubscription(String url) throws Exception {
        String body = get(url).trim();
        JSONArray arr;
        if (body.startsWith("[")) {
            arr = new JSONArray(body);
        } else {
            JSONObject root = new JSONObject(body);
            arr = root.optJSONArray("sources");
            if (arr == null) arr = root.optJSONArray("list");
            if (arr == null) arr = root.optJSONArray("sites");
            if (arr == null) throw new JSONException("未找到源数组");
        }

        List<Model.Source> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", o.optString("key", ""));
            String u = o.optString("url", o.optString("api", ""));
            if (u.startsWith("http://") || u.startsWith("https://")) {
                out.add(new Model.Source(name.isEmpty() ? ("源" + (i + 1)) : name, u));
            }
        }
        return out;
    }

    // ---------- 直播 M3U ----------

    /**
     * 解析 M3U / M3U8 播放列表。
     * 支持 #EXTINF 的 tvg-name / tvg-logo / group-title、#EXTGRP，
     * 同名频道自动合并为多条线路；非 HTTP(S) 地址会被跳过（组播 / RTMP 不在支持范围）。
     */
    public static List<Model.Channel> parseM3u(String body, String defaultGroup) {
        Map<String, Model.Channel> byName = new LinkedHashMap<>();
        if (body == null) return new ArrayList<>();
        String[] lines = body.split("\\r?\\n");

        String pendingName = null;
        String pendingGroup = null;
        String pendingLogo = "";

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "";
                String gt = attr(line, "group-title");
                // 不要覆盖前面 #EXTGRP 已设好的分组
                if (!gt.isEmpty()) pendingGroup = gt;
                else if (pendingGroup == null) pendingGroup = "";
                pendingLogo = attr(line, "tvg-logo");
                String tvgName = attr(line, "tvg-name");
                if (pendingName.isEmpty() && !tvgName.isEmpty()) pendingName = tvgName;
                continue;
            }
            if (line.startsWith("#EXTGRP")) {
                String g = line.substring(7).trim();
                // 标准写法是 #EXTGRP:分组名，去掉冒号前缀；也兼容 #EXTGRP 分组名
                if (g.startsWith(":")) g = g.substring(1).trim();
                pendingGroup = g;
                continue;
            }
            if (line.startsWith("#")) continue;   // 其它 #KODIPROP / #EXTVLCOPT 等忽略

            // 地址行：严格清洗，被代理/压缩污染的「http:// + 乱码」直接丢弃
            final String urlLine = cleanUrl(line);
            if (urlLine.isEmpty()) {
                pendingName = null;
                pendingGroup = null;
                pendingLogo = "";
                continue;
            }
            String name = (pendingName == null || pendingName.isEmpty())
                    ? guessName(urlLine) : pendingName;
            String group = (pendingGroup == null || pendingGroup.isEmpty())
                    ? defaultGroup : pendingGroup;
            if (group == null || group.isEmpty()) group = "未分组";

            Model.Channel ch = byName.get(name);
            if (ch == null) {
                ch = new Model.Channel(name, group, pendingLogo);
                byName.put(name, ch);
            }
            if (!ch.urls.contains(urlLine)) ch.urls.add(urlLine);

            pendingName = null;
            pendingGroup = null;
            pendingLogo = "";
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 解析 TXT 直播源（diyp / 影视仓 / TVBoxGUI 一类「名称,地址」格式）。
     *
     * 兼容：
     *  - 分组行「央视,#genre#」「卫视，#genre#」（大小写不敏感）
     *  - 频道行「CCTV-1 综合,http://a.m3u8」（半角 / 全角逗号）
     *  - 多线路「名称,http://a#http://b」「名称,http://a|http://b」
     *    「名称,http://a,http://b」（只在逗号后紧跟 http 时才切，不误伤含逗号的 query）
     *  - 纯地址行 / 空格分隔「CCTV-1 http://a.m3u8」
     *  - # 开头注释行、空行
     * 非 HTTP(S)（udp / rtmp / 组播）跳过；同名频道合并线路。
     * TXT 无台标字段，logo 留空；分组靠上面取到的组名，没分组用订阅名兜底。
     */
    public static List<Model.Channel> parseTxt(String body, String defaultGroup) {
        Map<String, Model.Channel> byName = new LinkedHashMap<>();
        if (body == null) return new ArrayList<>();
        String group = (defaultGroup == null || defaultGroup.isEmpty())
                ? "未分组" : defaultGroup;

        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            // 分组行：名称 + ,#genre#（半角 / 全角逗号都行）
            int gp = line.toLowerCase().indexOf("#genre#");
            if (gp >= 0) {
                String g = line.substring(0, gp)
                        .replaceAll("[,，\\s]+$", "").trim();
                if (!g.isEmpty()) group = g;
                continue;
            }
            if (line.startsWith("#")) continue;    // 注释 / 其它标记

            String name;
            String rest;
            int comma = firstComma(line);
            if (comma >= 0) {
                name = line.substring(0, comma).trim();
                rest = line.substring(comma + 1).trim();
            } else {
                int hp = firstHttp(line);
                if (hp < 0) continue;              // 既没逗号也没地址，跳过
                name = line.substring(0, hp).replaceAll("[,，\\s]+$", "").trim();
                rest = line.substring(hp).trim();
            }
            if (rest.isEmpty()) continue;

            // 多线路：# / | / 「逗号后紧跟 http」三种分隔
            String[] parts = rest.split("[,，](?=https?://)|#|\\|");
            List<String> urls = new ArrayList<>();
            for (String part : parts) {
                String u = cleanUrl(part);
                if (!u.isEmpty() && !urls.contains(u)) urls.add(u);
            }
            if (urls.isEmpty()) continue;          // udp / rtmp / 组播 / 被污染的乱码地址
            if (name.isEmpty()) name = guessName(urls.get(0));

            Model.Channel ch = byName.get(name);
            if (ch == null) {
                ch = new Model.Channel(name, group, "");
                byName.put(name, ch);
            }
            for (String u : urls) {
                if (!ch.urls.contains(u)) ch.urls.add(u);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * URL 合法性清洗 —— 修「名称正常、播放地址是乱码」的关键。
     *
     * TXT 里的频道名是中文、地址是纯 ASCII。若订阅响应被代理注入拦截页、
     * 或 gzip 压缩字节被当文本解，常见结果是「http:// 后面跟一串乱码字符」：
     * 旧逻辑只判断 startsWith("http://") 就收下了，于是列表里名字好好的、
     * 地址却是一串乱码，播放必然失败。这里做严格校验：
     *  - 去掉首尾空白与不可见控制字符（含 BOM / 零宽字符）
     *  - 只接受可打印 ASCII（真正的 URL；含中文/全角/替换符一律判为污染）
     *  - 拒绝 UTF-8 替换符 \uFFFD、「锟」「拷」「乱码」等典型乱码标志
     *  - host 必须含点且只由域名字符组成
     * 不合格返回 ""，调用方直接丢弃该地址。
     */
    public static String cleanUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        // 去掉首尾所有非可打印字符（BOM、零宽空格、控制字符）
        while (!u.isEmpty()) {
            char c = u.charAt(0);
            if (c < 0x20 || c == 0x7F || c == '\uFEFF' || c == '\u200B' || c == '\u200C'
                    || c == '\u200D' || c == '\u00A0') u = u.substring(1);
            else break;
        }
        while (!u.isEmpty()) {
            char c = u.charAt(u.length() - 1);
            if (c < 0x20 || c == 0x7F || c == '\uFEFF' || c == '\u200B' || c == '\u00A0') {
                u = u.substring(0, u.length() - 1);
            } else break;
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "";

        // 整串必须是可打印 ASCII（0x20~0x7E）。URL 里出现任何非 ASCII，
        // 几乎必然是压缩字节 / 拦截页被错误解码的产物。
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c < 0x20 || c > 0x7E) return "";
        }
        if (u.indexOf('\uFFFD') >= 0 || u.contains("锟") || u.contains("拷")) return "";

        // host 只允许 ASCII 域名字符（允许内网单标签主机名 / IP / 带端口，
        // 如 http://nas/live.m3u8、http://192.168.1.5:8080/x.m3u8）。
        // 乱码的特征是「出现非 ASCII」，上面那道可打印 ASCII 检查已经挡住，
        // 这里不再强制 host 必须含点，避免误杀内网自建源。
        try {
            String host = new URL(u).getHost();
            if (host == null || host.isEmpty()) return "";
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == ':')) return "";
            }
        } catch (Exception e) {
            return "";
        }
        return u;
    }

    private static int firstComma(String s) {
        int a = s.indexOf(',');
        int b = s.indexOf('，');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static int firstHttp(String s) {
        int a = s.indexOf("http://");
        int b = s.indexOf("https://");
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /**
     * 按内容自动分派：有 #EXTINF / #EXTM3U 走 M3U，否则走 TXT。
     * 只看内容不看 URL 后缀，所以 .txt 里放 M3U、.m3u 里放 TXT 都能读。
     * TXT 解析不到频道时再回退一次 M3U（兼容无 #EXTINF 的纯地址列表）。
     */
    public static List<Model.Channel> parsePlaylist(String body, String defaultGroup) {
        if (body == null) return new ArrayList<>();
        String t = body.trim();
        if (t.contains("#EXTINF") || t.startsWith("#EXTM3U")) {
            return parseM3u(body, defaultGroup);
        }
        List<Model.Channel> txt = parseTxt(body, defaultGroup);
        if (txt.isEmpty()) return parseM3u(body, defaultGroup);
        return txt;
    }

    /** 取 #EXTINF 里的 key="value" 属性，单双引号都兼容 */
    private static String attr(String line, String key) {
        Matcher m = Pattern.compile(key + "\\s*=\\s*[\"']([^\"']*)[\"']")
                .matcher(line);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String guessName(String url) {
        try {
            String path = new URL(url).getPath();
            int p = path.lastIndexOf('/');
            String s = p >= 0 ? path.substring(p + 1) : path;
            s = s.replaceAll("\\.(m3u8|ts|mp4|flv)(\\?.*)?$", "");
            return s.isEmpty() ? "未命名频道" : s;
        } catch (Exception e) {
            return "未命名频道";
        }
    }

    /** 一次直播列表拉取的结果，带订阅成功/失败计数，供「失败沿用缓存」判定。 */
    public static class LiveLoadResult {
        public final List<Model.Channel> channels = new ArrayList<>();
        public int okSubs = 0;
        public int failSubs = 0;
    }

    /**
     * 并发拉取所有订阅（M3U / TXT 自动识别）+ 合并本机手动频道，
     * 按频道名去重并合并线路。单份订阅失败不影响其它订阅。必须在子线程调用。
     *
     * 与旧版 loadAllLive 的差别：额外统计成功 / 失败订阅数。
     * 「订阅返回空列表」也算失败，避免代理返回拦截页时把 0 个频道当成成功，
     * 从而错误地把缓存覆盖成空表。
     */
    public static LiveLoadResult loadAllLiveStatus(final List<Model.LiveSub> subs,
                                                   final List<Model.Channel> manual) {
        final LiveLoadResult res = new LiveLoadResult();
        final Map<String, Model.Channel> merged = new LinkedHashMap<>();
        // 手动频道放最前
        if (manual != null) {
            for (Model.Channel ch : manual) merged.put(ch.name, ch);
        }

        if (subs == null || subs.isEmpty()) {
            res.channels.addAll(merged.values());
            return res;
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(5, Math.max(1, subs.size())));
        List<Future<List<Model.Channel>>> futures = new ArrayList<>();
        for (final Model.LiveSub s : subs) {
            futures.add(pool.submit(new Callable<List<Model.Channel>>() {
                @Override
                public List<Model.Channel> call() throws Exception {
                    String body = get(s.url);
                    return parsePlaylist(body, s.name.isEmpty() ? "订阅频道" : s.name);
                }
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                List<Model.Channel> one = futures.get(i).get(14, TimeUnit.SECONDS);
                if (one == null || one.isEmpty()) {
                    res.failSubs++;
                    continue;                       // 空结果按失败算
                }
                res.okSubs++;
                synchronized (merged) {
                    for (Model.Channel ch : one) {
                        Model.Channel target = merged.get(ch.name);
                        if (target == null) {
                            merged.put(ch.name, ch);
                        } else {
                            for (String u : ch.urls) {
                                if (!target.urls.contains(u)) target.urls.add(u);
                            }
                            if (target.logo.isEmpty()) target.logo = ch.logo;
                        }
                    }
                }
            } catch (Exception ignored) {
                res.failSubs++;
            }
        }
        pool.shutdownNow();
        res.channels.addAll(merged.values());
        return res;
    }

    /** 兼容旧调用：只取频道列表 */
    public static List<Model.Channel> loadAllLive(final List<Model.LiveSub> subs,
                                                   final List<Model.Channel> manual) {
        return loadAllLiveStatus(subs, manual).channels;
    }
}
