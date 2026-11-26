package com.ilatv.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 全部数据模型，纯 Java POJO，不依赖任何框架。 */
public final class Model {

    private Model() {}

    /**
     * 点播「跳过片头 / 片尾」配置（v1.13.8）。
     * 全局一份，App 设置页与网页后台读写同一份；仅 PlayerActivity 使用，
     * 直播播放页完全不读取，保证「只对点播有效」。
     *
     * auto=true：开播自动 seek 到 introSec；播放进入片尾区间自动切下一集
     * auto=false：区间内显示「跳过片头 / 跳过片尾」按钮，点一下才执行
     */
    public static class SkipConfig {
        public static final int MAX_SEC = 600;

        public boolean enabled = true;
        public int introSec = 90;
        public int outroSec = 60;
        public boolean auto = true;

        public static int clampSec(int s) {
            if (s < 0) return 0;
            return s > MAX_SEC ? MAX_SEC : s;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("introSec", introSec);
            o.put("outroSec", outroSec);
            o.put("auto", auto);
            return o;
        }

        public static SkipConfig fromJson(JSONObject o) {
            SkipConfig s = new SkipConfig();
            if (o == null) return s;
            s.enabled = o.optBoolean("enabled", true);
            s.introSec = clampSec(o.optInt("introSec", 90));
            s.outroSec = clampSec(o.optInt("outroSec", 60));
            s.auto = o.optBoolean("auto", true);
            return s;
        }

        /**
         * 自动跳过片头时应 seek 到的位置；返回 -1 表示本集不该跳。
         * 纯函数，不依赖 Android，可单独跑单测。
         *
         * 保护：片头比整片还长（target 距结尾不足 5 秒）时不跳，
         * 否则一开播就会被直接拽到片尾。
         */
        public long introTargetMs(long posMs, long durMs) {
            if (!enabled || !auto || introSec <= 0) return -1L;
            long target = introSec * 1000L;
            if (durMs <= 0) return -1L;                 // 时长还没读到，等下一次 tick
            if (target >= durMs - 5000L) return -1L;   // 片头过长，放弃
            if (posMs >= target) return -1L;           // 已经过了片头（含续播）
            return target;
        }

        /** 是否处于片头区间（手动按钮显示 / 自动跳过前的位置判断） */
        public boolean inIntro(long posMs, long durMs) {
            if (!enabled || introSec <= 0 || durMs <= 0) return false;
            long end = introSec * 1000L;
            return end < durMs - 5000L && posMs < end;
        }

        /** 是否进入片尾区间（自动切下一集 / 手动「跳过片尾」按钮） */
        public boolean inOutro(long posMs, long durMs) {
            if (!enabled || outroSec <= 0 || durMs <= 0) return false;
            long start = durMs - outroSec * 1000L;
            return start > 0 && posMs >= start;
        }
    }

    /**
     * 直播选台分组的顺序与选中定位（v1.13.10）。
     * 顺序固定：★ 我的收藏(kind=1) → 全部频道(kind=0) → 各普通分组(kind=2)。
     * 这些是纯静态方法、不依赖 Android，可单独跑 JVM 单测；
     * LivePicker（播放页抽屉）与 LiveActivity（全屏选台页）共用同一份判定。
     */
    public static final class LiveGroupRef {
        public final String name;
        public final int kind;
        public LiveGroupRef(String name, int kind) { this.name = name; this.kind = kind; }
    }

    public static final String LIVE_GROUP_ALL = "全部频道";
    public static final String LIVE_GROUP_FAV = "★ 我的收藏";
    public static final int LIVE_KIND_ALL = 0;
    public static final int LIVE_KIND_FAV = 1;
    public static final int LIVE_KIND_NORMAL = 2;

    // ---------- 直播分组隐藏（v70）：纯逻辑，不依赖 Android，可单独 JVM 单测 ----------

    /** 分组名归一：空 / null 一律归到「未分组」，与解析器、选台页口径一致。 */
    public static String liveGroupName(Channel ch) {
        return (ch == null || ch.group == null || ch.group.isEmpty()) ? "未分组" : ch.group;
    }

    /**
     * 按隐藏分组名单过滤频道列表。
     * hidden 为空时原样返回 in（不拷贝）；否则返回新 ArrayList，绝不修改 in。
     * 缓存里永远保留全量频道，过滤只在「交付给界面」时发生，取消隐藏即可恢复。
     */
    public static List<Channel> filterHiddenGroups(List<Channel> in, java.util.Set<String> hidden) {
        if (in == null) return new ArrayList<Channel>();
        if (hidden == null || hidden.isEmpty()) return in;
        List<Channel> out = new ArrayList<Channel>();
        for (Channel ch : in) {
            if (!hidden.contains(liveGroupName(ch))) out.add(ch);
        }
        return out;
    }

    /** 统计每个分组的频道数，保持频道首次出现的顺序。 */
    public static LinkedHashMap<String, Integer> countLiveGroups(List<Channel> in) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<String, Integer>();
        if (in == null) return m;
        for (Channel ch : in) {
            String g = liveGroupName(ch);
            Integer n = m.get(g);
            m.put(g, n == null ? 1 : n + 1);
        }
        return m;
    }

    /** 按 kind + 名字找回分组；kind 0/1 只认类型，普通分组还要比对名字。 */
    public static int liveGroupIndex(List<LiveGroupRef> gs, String name, int kind) {
        if (gs == null) return -1;
        for (int i = 0; i < gs.size(); i++) {
            LiveGroupRef g = gs.get(i);
            if (g == null || g.kind != kind) continue;
            if (kind == LIVE_KIND_NORMAL && (name == null || !name.equals(g.name))) continue;
            return i;
        }
        return -1;
    }

    /** 「全部频道」所在位置；异常情况下退到第 0 项。 */
    public static int liveGroupAll(List<LiveGroupRef> gs) {
        int i = liveGroupIndex(gs, LIVE_GROUP_ALL, LIVE_KIND_ALL);
        return i >= 0 ? i : 0;
    }

    /**
     * 重建分组列表后该选中哪一项：
     * 有旧选中（keepKind>=0）就按 kind+名字找回；找不到或首次构建 → 落到「全部频道」。
     */
    public static int liveGroupPick(List<LiveGroupRef> gs, int keepKind, String keepName) {
        if (keepKind >= 0) {
            int i = liveGroupIndex(gs, keepName, keepKind);
            if (i >= 0) return i;
        }
        return liveGroupAll(gs);
    }

    /**
     * 片名记忆：按片名保存各自的「跳过片头 / 片尾」配置（v1.13.9）。
     *
     * 存的是 JSONArray，每项 {name, enabled, introSec, outroSec, auto}，
     * 直接用原片名做 key，避免归一化后丢失展示名；查找时再做三级匹配。
     * 全部是纯静态方法、不依赖 Android，可单独跑 JVM 单测。
     */
    public static final class SkipTitles {
        private SkipTitles() {}

        public static final int MAX = 200;      // 防止 SharedPreferences 无限膨胀

        /** 归一化：去首尾空白、去掉所有空白字符、英文统一小写。中文不受影响。 */
        public static String norm(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!Character.isWhitespace(c)) sb.append(c);
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        }

        /** 解析本机存的 JSON 数组；坏数据跳过，重复片名后者覆盖。 */
        public static LinkedHashMap<String, SkipConfig> parse(String raw) {
            LinkedHashMap<String, SkipConfig> m = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) return m;
            try {
                JSONArray a = new JSONArray(raw);
                return fromArray(a);
            } catch (Exception ignored) {
                // 坏 JSON / 非数组：当作没有片名记忆，绝不让播放器起不来
                return m;
            }
        }

        /** 后台整表保存 / 订阅导入共用：校验片名与秒数，超限截断。 */
        public static LinkedHashMap<String, SkipConfig> fromArray(JSONArray a) {
            LinkedHashMap<String, SkipConfig> m = new LinkedHashMap<>();
            if (a == null) return m;
            for (int i = 0; i < a.length() && m.size() < MAX; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                if (name.isEmpty()) continue;
                SkipConfig s = SkipConfig.fromJson(o);
                m.put(name, s);           // 同名以后者为准，同时保持原有顺序
            }
            return m;
        }

        /** 序列化成 JSON 数组字符串（供本机存储 / 导出配置 / /api/state）。 */
        public static String serialize(Map<String, SkipConfig> m) {
            return toArray(m).toString();
        }

        public static JSONArray toArray(Map<String, SkipConfig> m) {
            JSONArray a = new JSONArray();
            if (m == null) return a;
            for (Map.Entry<String, SkipConfig> e : m.entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey().trim();
                if (name.isEmpty() || e.getValue() == null) continue;
                try {
                    JSONObject o = e.getValue().toJson();
                    o.put("name", name);
                    a.put(o);
                } catch (JSONException ignored) {
                }
            }
            return a;
        }

        /**
         * 解析优先级：精确片名 → 归一化匹配（空白 / 大小写差异）→ 全局默认。
         * 没设过这部片就返回全局配置，不影响其它片。
         */
        public static SkipConfig resolve(Map<String, SkipConfig> m,
                                         String title, SkipConfig global) {
            if (m == null || m.isEmpty() || title == null) return global;
            String t = title.trim();
            if (t.isEmpty()) return global;
            SkipConfig hit = m.get(t);
            if (hit != null) return hit;
            String n = norm(t);
            for (Map.Entry<String, SkipConfig> e : m.entrySet()) {
                if (n.equals(norm(e.getKey()))) return e.getValue();
            }
            return global;
        }

        public static boolean has(Map<String, SkipConfig> m, String title) {
            if (m == null || m.isEmpty() || title == null || title.trim().isEmpty())
                return false;
            return resolve(m, title, null) != null;
        }

        /** 写入 / 覆盖一部片；超过上限淘汰最早写入的一条。 */
        public static void put(LinkedHashMap<String, SkipConfig> m,
                               String title, SkipConfig s) {
            if (m == null || title == null || s == null) return;
            String t = title.trim();
            if (t.isEmpty()) return;
            // 若已存在（含归一化等价），先删掉旧 key，避免同一部片存两条
            String n = norm(t);
            String old = null;
            for (String k : m.keySet()) {
                if (k.equals(t) || norm(k).equals(n)) { old = k; break; }
            }
            if (old != null) m.remove(old);
            while (m.size() >= MAX) {
                String first = m.keySet().iterator().next();
                m.remove(first);
            }
            m.put(t, s);
        }

        /** 删除本片记忆；返回是否真的删掉了。 */
        public static boolean remove(Map<String, SkipConfig> m, String title) {
            if (m == null || title == null || title.trim().isEmpty()) return false;
            String t = title.trim();
            if (m.remove(t) != null) return true;
            String n = norm(t);
            String found = null;
            for (String k : m.keySet()) {
                if (norm(k).equals(n)) { found = k; break; }
            }
            if (found != null) { m.remove(found); return true; }
            return false;
        }
    }

    /** 一个 MacCMS VOD 采集接口 */
    public static class Source implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public String url;
        public boolean enabled = true;      // 停用后不参与搜索，但配置保留

        public Source() {}
        public Source(String name, String url) {
            this.name = name;
            this.url = url;
        }
        public Source(String name, String url, boolean enabled) {
            this.name = name;
            this.url = url;
            this.enabled = enabled;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name == null ? "" : name);
            o.put("url", url == null ? "" : url);
            o.put("enabled", enabled);
            return o;
        }

        public static Source fromJson(JSONObject o) {
            // 旧版本没有 enabled 字段，缺省视为启用，保证覆盖安装后源不丢
            return new Source(o.optString("name", ""), o.optString("url", ""),
                    o.optBoolean("enabled", true));
        }
    }

    /** 一份直播 M3U 订阅 */
    public static class LiveSub implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public String url;
        public boolean enabled = true;      // 停用后不拉取，但配置保留

        public LiveSub() {}
        public LiveSub(String name, String url) {
            this.name = name;
            this.url = url;
        }
        public LiveSub(String name, String url, boolean enabled) {
            this.name = name;
            this.url = url;
            this.enabled = enabled;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name == null ? "" : name);
            o.put("url", url == null ? "" : url);
            o.put("enabled", enabled);
            return o;
        }

        public static LiveSub fromJson(JSONObject o) {
            return new LiveSub(o.optString("name", ""), o.optString("url", ""),
                    o.optBoolean("enabled", true));
        }
    }

    /** 一个直播频道；同名频道的多条线路合并到 urls */
    public static class Channel implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public String group;
        public String logo;
        public List<String> urls = new ArrayList<>();
        public boolean manual;          // 是否本机手动添加（可删除）

        public Channel() {}
        public Channel(String name, String group, String logo) {
            this.name = name;
            this.group = group;
            this.logo = logo == null ? "" : logo;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name == null ? "" : name);
            o.put("group", group == null ? "" : group);
            o.put("logo", logo == null ? "" : logo);
            org.json.JSONArray a = new org.json.JSONArray();
            for (String u : urls) a.put(u);
            o.put("urls", a);
            o.put("manual", manual);   // 直播列表缓存要保留「是否本机频道」
            return o;
        }

        public static Channel fromJson(JSONObject o) {
            Channel c = new Channel(
                    o.optString("name", ""),
                    o.optString("group", "本机频道"),
                    o.optString("logo", ""));
            org.json.JSONArray a = o.optJSONArray("urls");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    String u = a.optString(i, "");
                    if (!u.isEmpty()) c.urls.add(u);
                }
            }
            // 旧数据（本机频道 / 导出配置）没有该字段，默认 true 保持兼容
            c.manual = o.optBoolean("manual", true);
            return c;
        }
    }

    /** 单集：集名 + 播放地址 */
    public static class Episode implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public String url;

        public Episode(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name == null ? "" : name);
            o.put("url", url == null ? "" : url);
            return o;
        }

        public static Episode fromJson(JSONObject o) {
            return new Episode(o.optString("name", ""), o.optString("url", ""));
        }
    }

    /** 一条播放线路（一个播放组内的一串集数） */
    public static class Line implements Serializable {
        private static final long serialVersionUID = 1L;
        public String from;
        public List<Episode> episodes = new ArrayList<>();

        public Line(String from) {
            this.from = from;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("from", from == null ? "" : from);
            JSONArray a = new JSONArray();
            for (Episode e : episodes) a.put(e.toJson());
            o.put("episodes", a);
            return o;
        }

        public static Line fromJson(JSONObject o) {
            Line l = new Line(o.optString("from", ""));
            JSONArray a = o.optJSONArray("episodes");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject eo = a.optJSONObject(i);
                    if (eo == null) continue;
                    Episode e = Episode.fromJson(eo);
                    if (!e.url.isEmpty()) l.episodes.add(e);
                }
            }
            return l;
        }
    }

    /** 一部影片；同名影片会把多个来源的线路合并进来 */
    public static class Video implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id;
        public String name;
        public String pic;
        public String remark;
        public String content;
        public List<Line> lines = new ArrayList<>();
        public List<String> sources = new ArrayList<>();

        public int episodeCount() {
            int n = 0;
            for (Line l : lines) n += l.episodes.size();
            return n;
        }

        public int sourceCount() {
            return sources.size();
        }

        /** 序列化完整选集树，供本机缓存（最近观看 / 单地址入口恢复用） */
        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("name", name == null ? "" : name);
            o.put("pic", pic == null ? "" : pic);
            o.put("remark", remark == null ? "" : remark);
            o.put("content", content == null ? "" : content);
            JSONArray ls = new JSONArray();
            for (Line l : lines) ls.put(l.toJson());
            o.put("lines", ls);
            JSONArray ss = new JSONArray();
            for (String s : sources) ss.put(s == null ? "" : s);
            o.put("sources", ss);
            return o;
        }

        public static Video fromJson(JSONObject o) {
            Video v = new Video();
            v.id = o.optString("id", "");
            v.name = o.optString("name", "");
            v.pic = o.optString("pic", "");
            v.remark = o.optString("remark", "");
            v.content = o.optString("content", "");
            JSONArray ls = o.optJSONArray("lines");
            if (ls != null) {
                for (int i = 0; i < ls.length(); i++) {
                    JSONObject lo = ls.optJSONObject(i);
                    if (lo == null) continue;
                    Line l = Line.fromJson(lo);
                    if (!l.episodes.isEmpty()) v.lines.add(l);
                }
            }
            JSONArray ss = o.optJSONArray("sources");
            if (ss != null) {
                for (int i = 0; i < ss.length(); i++) {
                    String s = ss.optString(i, "");
                    if (!s.isEmpty() && !v.sources.contains(s)) v.sources.add(s);
                }
            }
            return v;
        }
    }

    /** 观看进度，按片名 + 集名记录，存本机 */
    public static class History implements Serializable {
        private static final long serialVersionUID = 1L;
        public String videoName;
        public String episodeName;
        public String sourceName;
        public String playUrl;
        public long positionMs;
        public long updatedAt;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("videoName", videoName == null ? "" : videoName);
            o.put("episodeName", episodeName == null ? "" : episodeName);
            o.put("sourceName", sourceName == null ? "" : sourceName);
            o.put("playUrl", playUrl == null ? "" : playUrl);
            o.put("positionMs", positionMs);
            o.put("updatedAt", updatedAt);
            return o;
        }

        public static History fromJson(JSONObject o) {
            History h = new History();
            h.videoName = o.optString("videoName", "");
            h.episodeName = o.optString("episodeName", "");
            h.sourceName = o.optString("sourceName", "");
            h.playUrl = o.optString("playUrl", "");
            h.positionMs = o.optLong("positionMs", 0L);
            h.updatedAt = o.optLong("updatedAt", 0L);
            return h;
        }
    }
}
