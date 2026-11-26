package com.ilatv.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机存储：数据源配置、观看进度。
 *
 * 首次启动会播种两条**可删除**的内置订阅地址（一个点播采集接口、一份直播 TXT
 * 列表）。APK 里只存这两个 URL，不存任何影片、频道或播放列表内容——内容都是
 * 运行时从地址拉取。播种只在第一次发生，之后用户改 / 停用 / 删除都不会复活。
 */
public final class Store {

    private static final String PREF = "libretv_java";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_LIVE_SUBS = "live_subs";
    private static final String KEY_LIVE_MANUAL = "live_manual";
    private static final String KEY_LIVE_FAV = "live_fav";
    private static final String KEY_LAST_LIVE = "last_live";
    private static final String KEY_VOD_FAV = "vod_fav";
    private static final String KEY_SEEDED = "seeded_defaults_v1";
    private static final String KEY_SKIP = "vod_skip_config_v1";
    private static final String KEY_SKIP_TITLES = "vod_skip_titles_v1";
    private static final String KEY_STARTUP_PAGE = "startup_page_v1";
    private static final String KEY_DECODE_MODE = "decode_mode_v1";          // 点播
    private static final String KEY_LIVE_DECODE_MODE = "live_decode_mode_v1"; // 直播
    private static final String KEY_VOD_ASPECT = "vod_aspect_v1";             // 点播画面比例
    private static final String KEY_LIVE_ASPECT = "live_aspect_v1";           // 直播画面比例
    private static final String KEY_ASPECT_MIGRATED = "aspect_default_migrated_v1";
    private static final String KEY_CHANNEL_REVERSE = "channel_reverse_v1";    // 直播换台方向反转
    private static final String KEY_LAUNCHER_MODE = "launcher_mode_v1";        // normal / home
    private static final String KEY_AUTO_BOOT = "auto_boot_v1";                // 开机自启动
    private static final String KEY_HIDDEN_LIVE_GROUPS = "live_hidden_groups_v1"; // 直播分组显示/隐藏
    private static final String KEY_LIVE_UI_SCALE = "live_ui_scale_v1";      // 边框 + 框内字体百分比
    private static final String KEY_EPG_FONT_SCALE = "epg_font_scale_v1";    // 节目单 / 频道信息字体百分比

    // 内置默认源（仅地址；可编辑 / 停用 / 删除 / 清空，删后不自动恢复）
    public static final String DEFAULT_VOD_NAME = "360";
    public static final String DEFAULT_VOD_URL = "https://360zyzz.com/api.php/provide/vod";
    public static final String DEFAULT_LIVE_NAME = "直播电视";
    public static final String DEFAULT_LIVE_URL =
            "https://sub.ottiptv.cc/huyayqk.m3u";

    /** 旧版内置直播源（最早一版） */
    public static final String LEGACY_LIVE_URL = "https://live.zbds.top/tv/iptv4.txt";
    /** 上一版内置直播源（v46），启动时 / 恢复内置源 / 导入备份时一并升级 */
    public static final String PREV_LIVE_URL =
            "https://cdn.jsdelivr.net/gh/peterHchina/iptv@main/IPTV-V4.m3u";
    /** 旧版首页直播面板默认地址 */
    public static final String LEGACY_HOME_IPTV_URL =
            "https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u";

    /** 是否是「曾经的内置直播源」；只精确匹配，用户自填地址一律不动 */
    public static boolean isLegacyLiveUrl(String url) {
        return LEGACY_LIVE_URL.equals(url) || PREV_LIVE_URL.equals(url)
                || LEGACY_HOME_IPTV_URL.equals(url);
    }

    /** 备份 JSON 里做字符串替换用的全部旧内置地址 */
    public static String[] legacyLiveUrls() {
        return new String[]{LEGACY_LIVE_URL, PREV_LIVE_URL, LEGACY_HOME_IPTV_URL};
    }

    private Store() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------- 启动页：网页后台与 IPTV 功能菜单共用这一份配置 ----------

    /**
     * home     = 打开软件进首页（默认）
     * iptv     = 直接进 IPTV 直播
     * category = 直接进「影视分类」页
     */
    public static String startupPage(Context c) {
        String p = sp(c).getString(KEY_STARTUP_PAGE, "home");
        if ("iptv".equals(p) || "category".equals(p)) return p;
        return "home";
    }

    /** 只接受 home / iptv / category，非法值一律回落到 home */
    public static void setStartupPage(Context c, String page) {
        String p = ("iptv".equals(page) || "category".equals(page)) ? page : "home";
        sp(c).edit().putString(KEY_STARTUP_PAGE, p).apply();
    }

    // ---------- 直播选台界面缩放（v74）：网页后台 / 手机版设置页共用一份配置 ----------
    /** 50~200，默认 100 = 历版原样。原生选台页 / 抽屉 / 网页直播页都读这里。 */
    public static final int SCALE_MIN = 50, SCALE_MAX = 200, SCALE_DEF = 100;
    public static int clampScale(int v) {
        if (v < SCALE_MIN) return SCALE_MIN;
        if (v > SCALE_MAX) return SCALE_MAX;
        return v;
    }
    /** 边框大小：框宽 / 行高 / 内边距 / 徽章 + 框内字体一起按此百分比缩放 */
    public static int liveUiScale(Context c) {
        return clampScale(sp(c).getInt(KEY_LIVE_UI_SCALE, SCALE_DEF));
    }
    public static void setLiveUiScale(Context c, int pct) {
        sp(c).edit().putInt(KEY_LIVE_UI_SCALE, clampScale(pct)).apply();
    }
    /** 节目单 / 频道信息字体：独立于边框，单独按百分比缩放 */
    public static int epgFontScale(Context c) {
        return clampScale(sp(c).getInt(KEY_EPG_FONT_SCALE, SCALE_DEF));
    }
    public static void setEpgFontScale(Context c, int pct) {
        sp(c).edit().putInt(KEY_EPG_FONT_SCALE, clampScale(pct)).apply();
    }

    // ---------- 解码档位：点播（网页后台）与直播（电视功能菜单）各自独立记忆 ----------

    /**
     * 三档共用语义：
     * auto   = 自动选择（默认）：先按内置解码播；遇到解码类错误自动切到
     *          「系统解码」重建播放器重试一次
     * exo    = 只用内置解码，解码失败也不自动切换
     * sysdec = 系统解码：一上来就启用 MediaCodec 解码器回退（硬解失败转软解）
     *
     * 点播由网页后台「播放引擎」下拉控制，直播由 IPTV 功能菜单「解码方式」控制，
     * 两个 key 完全分开，改一边不会动另一边。
     */
    private static String normalizeMode(String mode) {
        return "sysdec".equals(mode) || "exo".equals(mode) ? mode : "auto";
    }

    /** 点播解码档位（沿用旧 key，老配置不丢） */
    public static String vodDecodeMode(Context c) {
        return normalizeMode(sp(c).getString(KEY_DECODE_MODE, "auto"));
    }

    /** 只接受 auto / exo / sysdec，其它值（含旧版 system / web）一律回落 auto */
    public static void setVodDecodeMode(Context c, String mode) {
        sp(c).edit().putString(KEY_DECODE_MODE, normalizeMode(mode)).apply();
    }

    /** 直播解码档位（独立 key） */
    public static String liveDecodeMode(Context c) {
        return normalizeMode(sp(c).getString(KEY_LIVE_DECODE_MODE, "auto"));
    }

    public static void setLiveDecodeMode(Context c, String mode) {
        sp(c).edit().putString(KEY_LIVE_DECODE_MODE, normalizeMode(mode)).apply();
    }

    // ---------- 点播画面比例：电视端播放菜单与网页后台共用 ----------

    /**
     * 画面比例合法值（点播 / 直播、电视菜单 / 网页后台共用，顺序一致）。
     * 自适应仍排第一位，但默认档改为「原始比例」（origin）。
     */
    public static final String DEFAULT_ASPECT = "origin";
    public static final String[] VOD_ASPECT_VALUES = {
            "auto", "origin", "full"
    };

    public static String vodAspect(Context c) {
        return normalizeAspect(sp(c).getString(KEY_VOD_ASPECT, DEFAULT_ASPECT));
    }

    public static void setVodAspect(Context c, String mode) {
        sp(c).edit().putString(KEY_VOD_ASPECT, normalizeAspect(mode)).apply();
    }

    /** 直播画面比例与点播共用三档合法值，但用独立 key 记忆，互不影响 */
    public static String liveAspect(Context c) {
        return normalizeAspect(sp(c).getString(KEY_LIVE_ASPECT, DEFAULT_ASPECT));
    }

    public static void setLiveAspect(Context c, String mode) {
        sp(c).edit().putString(KEY_LIVE_ASPECT, normalizeAspect(mode)).apply();
    }

    /**
     * 一次性迁移：旧版默认档是「自适应」，很多设备已经把 auto 显式写进配置。
     * 覆盖安装后把这两项从 auto 改成新的默认「原始比例」，只跑一次；
     * 之后用户主动在菜单/后台选「自适应」仍会照常保存、不会再被改回去。
     */
    public static void migrateAspectDefaults(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean(KEY_ASPECT_MIGRATED, false)) return;
        SharedPreferences.Editor ed = p.edit();
        if ("auto".equals(p.getString(KEY_VOD_ASPECT, "")))
            ed.putString(KEY_VOD_ASPECT, DEFAULT_ASPECT);
        if ("auto".equals(p.getString(KEY_LIVE_ASPECT, "")))
            ed.putString(KEY_LIVE_ASPECT, DEFAULT_ASPECT);
        ed.putBoolean(KEY_ASPECT_MIGRATED, true).apply();
    }

    /**
     * 归一化 + 旧配置兼容：早期的 18:9 / 16:9 / 4:3 / 背景模糊都是「留黑边」类，
     * 归到原始比例；裁剪归到全屏显示；其余非法值回落到默认「原始比例」。
     */
    private static String normalizeAspect(String mode) {
        if ("auto".equals(mode) || "origin".equals(mode) || "full".equals(mode)) return mode;
        if ("crop".equals(mode)) return "full";
        if ("18:9".equals(mode) || "16:9".equals(mode) || "4:3".equals(mode)
                || "blur".equals(mode)) return "origin";
        return DEFAULT_ASPECT;
    }

    // ---------- 直播换台反转：电视功能菜单与网页后台共用 ----------

    /**
     * 换台反转：默认 false（↑ / 频道+ / 媒体上一曲 = 上一个台，↓ = 下一个台）。
     * 开启后两组方向整体对调；←→ 切线路、数字选台、OK 确认都不受影响。
     */
    public static boolean channelReverse(Context c) {
        return sp(c).getBoolean(KEY_CHANNEL_REVERSE, false);
    }

    public static void setChannelReverse(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_CHANNEL_REVERSE, on).apply();
    }

    // ---------- 应用模式 / 开机自启动：手机后台「播放设置」与电视端共用 ----------

    /** 默认普通应用；home 会通过 activity-alias 变成系统可选桌面 */
    public static String launcherMode(Context c) {
        return "home".equals(sp(c).getString(KEY_LAUNCHER_MODE, "normal")) ? "home" : "normal";
    }

    public static void setLauncherMode(Context c, String mode) {
        sp(c).edit().putString(KEY_LAUNCHER_MODE,
                "home".equals(mode) ? "home" : "normal").apply();
    }

    /** 开机自启动，默认关闭 */
    public static boolean autoBoot(Context c) {
        return sp(c).getBoolean(KEY_AUTO_BOOT, false);
    }

    public static void setAutoBoot(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_AUTO_BOOT, on).apply();
    }

    // ---------- 内置默认源（首次播种一次） ----------

    public static boolean isDefaultVod(String url) {
        return DEFAULT_VOD_URL.equals(url);
    }

    public static boolean isDefaultLive(String url) {
        return DEFAULT_LIVE_URL.equals(url);
    }

    public static boolean defaultsSeeded(Context c) {
        return sp(c).getBoolean(KEY_SEEDED, false);
    }

    /**
     * 只在首次运行时写入两条内置订阅地址。按 URL 去重追加，不覆盖用户已有配置。
     * 置标记后即使用户把两条都删掉、清空、停用，重启也不会再出现——
     * 这就是「内置源可在 App 与网页后台删除」的实现方式。
     * @return 实际新增条数
     */
    public static int seedDefaults(Context c) {
        // 已经播种过的老版本：只做一次旧内置地址升级，不会把用户删掉的源再加回来
        if (defaultsSeeded(c)) return migrateLegacyLiveSubs(c) ? 1 : 0;
        int added = restoreDefaults(c);
        sp(c).edit().putBoolean(KEY_SEEDED, true).apply();
        return added;
    }

    /**
     * 把订阅列表里的旧内置直播地址换成新地址（只精确匹配旧内置项，
     * 用户自己填的地址一律不动）。换完清频道缓存，下次进直播立即拉新源。
     */
    private static boolean migrateLegacyLiveSubs(Context c) {
        List<Model.LiveSub> subs = liveSubs(c);
        boolean changed = false;
        for (int i = 0; i < subs.size(); i++) {
            Model.LiveSub s = subs.get(i);
            if (isLegacyLiveUrl(s.url)) {
                subs.set(i, new Model.LiveSub(s.name, DEFAULT_LIVE_URL, s.enabled));
                changed = true;
            }
        }
        if (changed) {
            saveLiveSubs(c, subs);
            clearLiveCache(c);
        }
        return changed;
    }

    /** 手动恢复内置源：按地址去重追加，不改动已有条目的名称 / 启用状态。 */
    public static int restoreDefaults(Context c) {
        int added = 0;

        List<Model.Source> src = sources(c);
        boolean hasVod = false;
        for (Model.Source s : src) {
            if (DEFAULT_VOD_URL.equals(s.url)) { hasVod = true; break; }
        }
        if (!hasVod) {
            src.add(new Model.Source(DEFAULT_VOD_NAME, DEFAULT_VOD_URL, true));
            saveSources(c, src);
            added++;
        }

        List<Model.LiveSub> subs = liveSubs(c);
        boolean hasLive = false;
        boolean changed = false;
        for (int i = 0; i < subs.size(); i++) {
            Model.LiveSub s = subs.get(i);
            if (DEFAULT_LIVE_URL.equals(s.url)) { hasLive = true; break; }
            if (isLegacyLiveUrl(s.url)) {
                // 旧内置源原地升级为新地址，不额外留一条已经失效的旧订阅
                subs.set(i, new Model.LiveSub(s.name, DEFAULT_LIVE_URL, s.enabled));
                hasLive = true;
                changed = true;
                break;
            }
        }
        if (!hasLive) {
            subs.add(new Model.LiveSub(DEFAULT_LIVE_NAME, DEFAULT_LIVE_URL, true));
            changed = true;
        }
        if (changed) {
            saveLiveSubs(c, subs);
            added++;
        }
        return added;
    }

    // ---------- 数据源 ----------

    public static List<Model.Source> sources(Context c) {
        List<Model.Source> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_SOURCES, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                Model.Source s = Model.Source.fromJson(a.getJSONObject(i));
                if (!s.url.isEmpty()) out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void saveSources(Context c, List<Model.Source> list) {
        JSONArray a = new JSONArray();
        try {
            for (Model.Source s : list) a.put(s.toJson());
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(KEY_SOURCES, a.toString()).apply();
    }

    public static void addSource(Context c, Model.Source s) {
        List<Model.Source> list = sources(c);
        for (Model.Source old : list) {
            if (old.url.equals(s.url)) return;   // 简单去重
        }
        list.add(s);
        saveSources(c, list);
    }

    /** 只返回启用中的源，供搜索用；停用的仍留在 sources() 里可再启用 */
    public static List<Model.Source> enabledSources(Context c) {
        List<Model.Source> out = new ArrayList<>();
        for (Model.Source s : sources(c)) {
            if (s.enabled) out.add(s);
        }
        return out;
    }

    /** 按列表位置覆盖一条（编辑名称 / 地址 / 启用状态） */
    public static void updateSource(Context c, int pos, Model.Source s) {
        List<Model.Source> list = sources(c);
        if (pos < 0 || pos >= list.size()) return;
        list.set(pos, s);
        saveSources(c, list);
    }

    public static void removeSource(Context c, int pos) {
        List<Model.Source> list = sources(c);
        if (pos < 0 || pos >= list.size()) return;
        list.remove(pos);
        saveSources(c, list);
    }

    // ---------- 点播跳过片头 / 片尾（仅点播有效） ----------

    /** 读取全局跳过配置；没有存过则返回默认（启用 / 片头 90 秒 / 片尾 60 秒 / 自动） */
    public static Model.SkipConfig skip(Context c) {
        String raw = sp(c).getString(KEY_SKIP, "");
        if (raw.isEmpty()) return new Model.SkipConfig();
        try {
            return Model.SkipConfig.fromJson(new JSONObject(raw));
        } catch (JSONException ignored) {
            return new Model.SkipConfig();
        }
    }

    public static void saveSkip(Context c, Model.SkipConfig s) {
        if (s == null) s = new Model.SkipConfig();
        s.introSec = Model.SkipConfig.clampSec(s.introSec);
        s.outroSec = Model.SkipConfig.clampSec(s.outroSec);
        try {
            sp(c).edit().putString(KEY_SKIP, s.toJson().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    // ---------- 片名记忆：每部剧单独的跳过秒数（v1.13.9） ----------

    /** 全部按片记忆（有序，key = 原片名）。网页后台整表保存也走这里。 */
    public static LinkedHashMap<String, Model.SkipConfig> skipTitles(Context c) {
        return Model.SkipTitles.parse(sp(c).getString(KEY_SKIP_TITLES, "[]"));
    }

    public static void saveSkipTitles(Context c, Map<String, Model.SkipConfig> m) {
        sp(c).edit().putString(KEY_SKIP_TITLES, Model.SkipTitles.serialize(m)).apply();
    }

    /** 播放页用：本片设过就用本片的，没设过回退全局默认。 */
    public static Model.SkipConfig skipForTitle(Context c, String title) {
        return Model.SkipTitles.resolve(skipTitles(c), title, skip(c));
    }

    public static boolean hasSkipTitle(Context c, String title) {
        return Model.SkipTitles.has(skipTitles(c), title);
    }

    public static void saveSkipForTitle(Context c, String title, Model.SkipConfig s) {
        if (s == null) s = new Model.SkipConfig();
        s.introSec = Model.SkipConfig.clampSec(s.introSec);
        s.outroSec = Model.SkipConfig.clampSec(s.outroSec);
        LinkedHashMap<String, Model.SkipConfig> m = skipTitles(c);
        Model.SkipTitles.put(m, title, s);
        saveSkipTitles(c, m);
    }

    /** 清除本片记忆，之后回到全局默认；返回是否真的删掉过。 */
    public static boolean removeSkipTitle(Context c, String title) {
        LinkedHashMap<String, Model.SkipConfig> m = skipTitles(c);
        if (!Model.SkipTitles.remove(m, title)) return false;
        saveSkipTitles(c, m);
        return true;
    }

    // ---------- 观看历史 ----------

    public static List<Model.History> history(Context c) {
        List<Model.History> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_HISTORY, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                out.add(Model.History.fromJson(a.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
        Collections.sort(out, new Comparator<Model.History>() {
            @Override
            public int compare(Model.History a, Model.History b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    /** 同一部片只保留最近一条记录 */
    public static void saveProgress(Context c, String videoName, String episodeName,
                                    String sourceName, String playUrl, long positionMs) {
        List<Model.History> list = history(c);
        List<Model.History> kept = new ArrayList<>();
        for (Model.History h : list) {
            if (!h.videoName.equals(videoName)) kept.add(h);
        }
        Model.History h = new Model.History();
        h.videoName = videoName;
        h.episodeName = episodeName;
        h.sourceName = sourceName;
        h.playUrl = playUrl;
        h.positionMs = positionMs;
        h.updatedAt = System.currentTimeMillis();
        kept.add(0, h);
        if (kept.size() > 100) kept = kept.subList(0, 100);

        JSONArray a = new JSONArray();
        try {
            for (Model.History x : kept) a.put(x.toJson());
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(KEY_HISTORY, a.toString()).apply();
    }

    public static Model.History lastOf(Context c, String videoName) {
        for (Model.History h : history(c)) {
            if (h.videoName.equals(videoName)) return h;
        }
        return null;
    }

    public static void removeHistory(Context c, String videoName) {
        List<Model.History> list = history(c);
        List<Model.History> kept = new ArrayList<>();
        for (Model.History h : list) {
            if (!h.videoName.equals(videoName)) kept.add(h);
        }
        JSONArray a = new JSONArray();
        try {
            for (Model.History x : kept) a.put(x.toJson());
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(KEY_HISTORY, a.toString()).apply();
    }

    public static void clearHistory(Context c) {
        sp(c).edit().remove(KEY_HISTORY).apply();
    }

    // ---------- 本机选集树缓存（修「最近观看打开没有选集」） ----------

    private static final String KEY_VIDEO_CACHE = "video_cache_v1";
    private static final int MAX_VIDEO_CACHE = 12;

    /**
     * 播放过一部带完整选集树的片子就缓存下来，按片名索引。
     * 独立于历史数组存放：历史最多 100 条且每次保存都要全量重写，
     * 若把 200+ 集的树塞进每条历史会让 SharedPreferences 膨胀到几 MB。
     * 只留最近 12 部，同名覆盖、超出淘汰最旧。
     */
    public static void saveVideoCache(Context c, String name, Model.Video v) {
        if (name == null || name.isEmpty() || v == null || v.lines.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY_VIDEO_CACHE, "[]"));
            JSONArray out = new JSONArray();
            JSONObject fresh = new JSONObject();
            fresh.put("name", name);
            fresh.put("ts", System.currentTimeMillis());
            fresh.put("video", v.toJson());
            out.put(fresh);
            int kept = 1;
            for (int i = 0; i < arr.length() && kept < MAX_VIDEO_CACHE; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || name.equals(o.optString("name", ""))) continue;
                out.put(o);
                kept++;
            }
            sp(c).edit().putString(KEY_VIDEO_CACHE, out.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** 按片名取回选集树；没有则返回 null（老记录走后台重搜补齐） */
    public static Model.Video videoCache(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY_VIDEO_CACHE, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !name.equals(o.optString("name", ""))) continue;
                JSONObject vo = o.optJSONObject("video");
                if (vo == null) continue;
                Model.Video v = Model.Video.fromJson(vo);
                return v.lines.isEmpty() ? null : v;
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    /**
     * 在选集树里定位历史记录对应的那一集，返回 {lineIndex, epIndex}。
     * 优先按播放地址精确匹配（换过线路名/集名也能命中），其次按
     * 「线路名 + 集名」，再退化为只按集名；找不到返回 {0,0}。
     * 纯静态方法，不依赖 Android 运行时，可单独跑单测。
     */
    public static int[] locateEpisode(Model.Video v, String playUrl,
                                      String episodeName, String sourceName) {
        int[] fallback = {0, 0};
        if (v == null || v.lines.isEmpty()) return fallback;

        if (playUrl != null && !playUrl.isEmpty()) {
            for (int li = 0; li < v.lines.size(); li++) {
                List<Model.Episode> eps = v.lines.get(li).episodes;
                for (int ei = 0; ei < eps.size(); ei++) {
                    if (playUrl.equals(eps.get(ei).url)) return new int[]{li, ei};
                }
            }
        }
        if (episodeName == null || episodeName.isEmpty()) return fallback;
        if (sourceName != null && !sourceName.isEmpty()) {
            for (int li = 0; li < v.lines.size(); li++) {
                if (!sourceName.equals(v.lines.get(li).from)) continue;
                List<Model.Episode> eps = v.lines.get(li).episodes;
                for (int ei = 0; ei < eps.size(); ei++) {
                    if (episodeName.equals(eps.get(ei).name)) return new int[]{li, ei};
                }
            }
        }
        for (int li = 0; li < v.lines.size(); li++) {
            List<Model.Episode> eps = v.lines.get(li).episodes;
            for (int ei = 0; ei < eps.size(); ei++) {
                if (episodeName.equals(eps.get(ei).name)) return new int[]{li, ei};
            }
        }
        return fallback;
    }

    // ---------- 直播订阅 ----------

    public static List<Model.LiveSub> liveSubs(Context c) {
        List<Model.LiveSub> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_LIVE_SUBS, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                Model.LiveSub s = Model.LiveSub.fromJson(a.getJSONObject(i));
                if (!s.url.isEmpty()) out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void saveLiveSubs(Context c, List<Model.LiveSub> list) {
        JSONArray a = new JSONArray();
        try {
            for (Model.LiveSub s : list) a.put(s.toJson());
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(KEY_LIVE_SUBS, a.toString()).apply();
    }

    public static void addLiveSub(Context c, Model.LiveSub s) {
        List<Model.LiveSub> list = liveSubs(c);
        for (Model.LiveSub old : list) {
            if (old.url.equals(s.url)) return;
        }
        list.add(s);
        saveLiveSubs(c, list);
    }

    public static List<Model.LiveSub> enabledLiveSubs(Context c) {
        List<Model.LiveSub> out = new ArrayList<>();
        for (Model.LiveSub s : liveSubs(c)) {
            if (s.enabled) out.add(s);
        }
        return out;
    }

    public static void updateLiveSub(Context c, int pos, Model.LiveSub s) {
        List<Model.LiveSub> list = liveSubs(c);
        if (pos < 0 || pos >= list.size()) return;
        list.set(pos, s);
        saveLiveSubs(c, list);
    }

    public static void removeLiveSub(Context c, int pos) {
        List<Model.LiveSub> list = liveSubs(c);
        if (pos < 0 || pos >= list.size()) return;
        list.remove(pos);
        saveLiveSubs(c, list);
    }

    // ---------- 直播分组显示 / 隐藏（v70，两版共用同一份配置） ----------

    /** 被隐藏的分组名集合；顺序按保存时的顺序，读写都不改频道缓存本身。 */
    public static java.util.LinkedHashSet<String> hiddenLiveGroups(Context c) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        String raw = sp(c).getString(KEY_HIDDEN_LIVE_GROUPS, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String g = a.optString(i, "").trim();
                if (!g.isEmpty()) out.add(g);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    /** 整体替换隐藏名单；空集合 = 全部分组显示。 */
    public static void saveHiddenLiveGroups(Context c, java.util.Collection<String> names) {
        JSONArray a = new JSONArray();
        if (names != null) {
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
            for (String n : names) {
                if (n == null) continue;
                String g = n.trim();
                if (!g.isEmpty() && seen.add(g)) a.put(g);
            }
        }
        sp(c).edit().putString(KEY_HIDDEN_LIVE_GROUPS, a.toString()).apply();
    }

    /** 交付给界面前过滤：缓存仍保持全量，取消隐藏即可立刻恢复。 */
    public static List<Model.Channel> filterHiddenChannels(Context c, List<Model.Channel> in) {
        return Model.filterHiddenGroups(in, hiddenLiveGroups(c));
    }

    public static boolean isLiveGroupHidden(Context c, String group) {
        String g = (group == null || group.isEmpty()) ? "未分组" : group;
        return hiddenLiveGroups(c).contains(g);
    }

    // ---------- 直播频道列表缓存（v1.13.7：先用缓存，再下载，失败沿用缓存） ----------

    private static final String KEY_LIVE_CACHE = "live_channels_cache_v1";
    private static final String KEY_LIVE_CACHE_TS = "live_channels_cache_ts_v1";
    private static final String KEY_LIVE_CACHE_SUBS = "live_channels_cache_subs_v1";

    /** 保存一次成功拉取后的完整合并频道列表（订阅频道 + 本机频道）。 */
    public static void saveLiveCache(Context c, List<Model.Channel> list) {
        JSONArray a = new JSONArray();
        try {
            if (list != null) {
                for (Model.Channel ch : list) a.put(ch.toJson());
            }
        } catch (JSONException ignored) {
        }
        sp(c).edit()
                .putString(KEY_LIVE_CACHE, a.toString())
                .putLong(KEY_LIVE_CACHE_TS, System.currentTimeMillis())
                .apply();
    }

    /** 读取上次成功拉取的频道列表；没有 / 已清空则返回空列表。 */
    public static List<Model.Channel> liveCache(Context c) {
        List<Model.Channel> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(KEY_LIVE_CACHE, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Model.Channel ch = Model.Channel.fromJson(o);
                if (!ch.name.isEmpty() && !ch.urls.isEmpty()) out.add(ch);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static long liveCacheTime(Context c) {
        return sp(c).getLong(KEY_LIVE_CACHE_TS, 0L);
    }

    /** 记录缓存对应的订阅签名，仅用于在 UI 上提示「订阅已变更」。 */
    public static String liveCacheSubs(Context c) {
        return sp(c).getString(KEY_LIVE_CACHE_SUBS, "");
    }

    public static void saveLiveCacheSubs(Context c, String sig) {
        sp(c).edit().putString(KEY_LIVE_CACHE_SUBS, sig == null ? "" : sig).apply();
    }

    public static void clearLiveCache(Context c) {
        sp(c).edit().remove(KEY_LIVE_CACHE).remove(KEY_LIVE_CACHE_TS)
                .remove(KEY_LIVE_CACHE_SUBS).apply();
    }

    /** 当前启用订阅的地址签名，订阅增删 / 启停后缓存来源会变化。 */
    public static String liveSubsSignature(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Model.LiveSub s : enabledLiveSubs(c)) sb.append(s.url).append('|');
        return sb.toString();
    }

    // ---------- 本机手动添加的单个频道 ----------

    public static List<Model.Channel> manualChannels(Context c) {
        List<Model.Channel> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_LIVE_MANUAL, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                out.add(Model.Channel.fromJson(a.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** 管理后台可整表覆盖本机频道 */
    public static void saveManualChannels(Context c, List<Model.Channel> list) {
        JSONArray a = new JSONArray();
        try {
            for (Model.Channel ch : list) a.put(ch.toJson());
        } catch (JSONException ignored) {
        }
        sp(c).edit().putString(KEY_LIVE_MANUAL, a.toString()).apply();
    }

    public static void addManualChannel(Context c, Model.Channel ch) {
        List<Model.Channel> list = manualChannels(c);
        for (Model.Channel old : list) {
            if (old.name.equals(ch.name) && old.urls.containsAll(ch.urls)) return;
        }
        list.add(ch);
        saveManualChannels(c, list);
    }

    public static void removeManualChannel(Context c, String name) {
        List<Model.Channel> list = manualChannels(c);
        List<Model.Channel> kept = new ArrayList<>();
        for (Model.Channel ch : list) {
            if (!ch.name.equals(name)) kept.add(ch);
        }
        saveManualChannels(c, kept);
    }

    /** 编辑手动频道：按旧名定位替换（允许改名、换地址） */
    public static void updateManualChannel(Context c, String oldName, Model.Channel ch) {
        List<Model.Channel> list = manualChannels(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(oldName)) {
                list.set(i, ch);
                break;
            }
        }
        saveManualChannels(c, list);
    }

    // ---------- 导出配置 ----------

    /**
     * 把点播源 + 直播订阅 + 手动频道 + 频道收藏 + 点播跳过设置导出为一份 JSON，
     * 可托管到公开 URL 后在「订阅导入」里填地址分发。
     * 格式兼容 Api.fetchSubscription() 的 sources 字段；skip 字段由 importConfig 识别。
     */
    public static String exportConfig(Context c) {
        JSONObject root = new JSONObject();
        try {
            JSONArray src = new JSONArray();
            for (Model.Source s : sources(c)) src.put(s.toJson());
            root.put("sources", src);

            JSONArray subs = new JSONArray();
            for (Model.LiveSub s : liveSubs(c)) subs.put(s.toJson());
            root.put("live_subs", subs);

            JSONArray chans = new JSONArray();
            for (Model.Channel ch : manualChannels(c)) chans.put(ch.toJson());
            root.put("channels", chans);

            JSONArray favs = new JSONArray();
            for (String n : liveFavs(c)) favs.put(n);
            root.put("favorites", favs);

            // 点播跳过片头 / 片尾设置随配置一起导出，托管后别人导入也能同步
            root.put("skip", skip(c).toJson());
            root.put("skip_titles", Model.SkipTitles.toArray(skipTitles(c)));
            // 直播分组显示 / 隐藏随配置一起导出，换包名迁移后不用重新设一遍
            JSONArray hg = new JSONArray();
            for (String g : hiddenLiveGroups(c)) hg.put(g);
            root.put("hidden_groups", hg);
            return root.toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    // ---------- 上次观看的直播频道 ----------

    public static String lastLive(Context c) {
        return sp(c).getString(KEY_LAST_LIVE, "");
    }

    public static void saveLastLive(Context c, String name) {
        sp(c).edit().putString(KEY_LAST_LIVE, name == null ? "" : name).apply();
    }

    // ---------- 直播频道收藏（按频道名，订阅刷新后仍能命中） ----------

    public static List<String> liveFavs(Context c) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_LIVE_FAV, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String n = a.optString(i, "");
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static boolean isLiveFav(Context c, String name) {
        return name != null && liveFavs(c).contains(name);
    }

    /** 切换收藏状态，返回切换后是否已收藏 */
    public static boolean toggleLiveFav(Context c, String name) {
        List<String> list = liveFavs(c);
        boolean now;
        if (list.contains(name)) {
            list.remove(name);
            now = false;
        } else {
            list.add(name);
            now = true;
        }
        JSONArray a = new JSONArray();
        for (String n : list) a.put(n);
        sp(c).edit().putString(KEY_LIVE_FAV, a.toString()).apply();
        return now;
    }

    public static void removeLiveFav(Context c, String name) {
        List<String> list = liveFavs(c);
        if (!list.remove(name)) return;
        setLiveFavs(c, list);
    }

    /** 管理后台整表覆盖收藏；自动去重、去空 */
    public static void setLiveFavs(Context c, List<String> list) {
        JSONArray a = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (String n : list) {
            if (n == null || n.trim().isEmpty() || seen.contains(n)) continue;
            seen.add(n);
            a.put(n);
        }
        sp(c).edit().putString(KEY_LIVE_FAV, a.toString()).apply();
    }

    // ---------- 点播收藏（按片名，带播放地址，可直接续播 / 删除） ----------

    public static List<Model.History> vodFavs(Context c) {
        List<Model.History> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_VOD_FAV, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Model.History h = Model.History.fromJson(o);
                if (!h.videoName.isEmpty() && !h.playUrl.isEmpty()) out.add(h);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static boolean isVodFav(Context c, String name) {
        if (name == null || name.isEmpty()) return false;
        for (Model.History h : vodFavs(c)) {
            if (h.videoName.equals(name)) return true;
        }
        return false;
    }

    /** 切换收藏；返回切换后是否已收藏 */
    public static boolean toggleVodFav(Context c, Model.History fav) {
        List<Model.History> list = vodFavs(c);
        boolean now = true;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).videoName.equals(fav.videoName)) {
                list.remove(i);
                now = false;
                break;
            }
        }
        if (now) list.add(0, fav);
        saveVodFavs(c, list);
        return now;
    }

    public static void removeVodFav(Context c, String name) {
        List<Model.History> list = vodFavs(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).videoName.equals(name)) {
                list.remove(i);
                saveVodFavs(c, list);
                return;
            }
        }
    }

    private static void saveVodFavs(Context c, List<Model.History> list) {
        JSONArray a = new JSONArray();
        for (Model.History h : list) {
            try {
                a.put(h.toJson());
            } catch (JSONException ignored) {
            }
        }
        sp(c).edit().putString(KEY_VOD_FAV, a.toString()).apply();
    }
}
