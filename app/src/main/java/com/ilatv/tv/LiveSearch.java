package com.ilatv.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 手机后台「直播推送」用的频道搜索：纯 Java，不依赖 Android，
 * 输入电视端已合并的频道列表，按「频道名 + 分组名」模糊匹配。
 *
 * <p>多关键词按空格拆分后要求全部命中（AND）；同名频道的多条线路合并成一个结果。
 * 关键词为空时按名称排序列出前若干频道，方便不输入也能直接挑台。
 */
public final class LiveSearch {

    private static final int MAX_RESULTS = 60;

    private LiveSearch() {}

    public static JSONObject filter(List<Model.Channel> all, String wd) {
        JSONObject out = new JSONObject();
        try {
            String q = wd == null ? "" : wd.trim();
            String[] keys = splitKeys(q);

            // 同名频道合并线路（订阅 + 手动频道合并后可能重复）
            LinkedHashMap<String, Hit> map = new LinkedHashMap<>();
            if (all != null) {
                for (Model.Channel ch : all) {
                    if (ch == null || ch.name == null || ch.name.isEmpty()) continue;
                    Hit h = map.get(ch.name);
                    if (h == null) {
                        map.put(ch.name, new Hit(ch));
                    } else {
                        if (ch.urls != null) {
                            for (String u : ch.urls) if (u != null && !h.urls.contains(u)) h.urls.add(u);
                        }
                        if (h.logo.isEmpty() && ch.logo != null) h.logo = ch.logo;
                        if (h.group.isEmpty() && ch.group != null) h.group = ch.group;
                    }
                }
            }

            List<Hit> hits = new ArrayList<>();
            for (Hit h : map.values()) {
                int rank = rank(h, keys);
                if (rank >= 0) { h.rank = rank; hits.add(h); }
            }
            Collections.sort(hits, new Comparator<Hit>() {
                @Override public int compare(Hit a, Hit b) {
                    if (a.rank != b.rank) return a.rank - b.rank;
                    return a.name.compareToIgnoreCase(b.name);
                }
            });

            JSONArray arr = new JSONArray();
            int n = Math.min(hits.size(), MAX_RESULTS);
            for (int i = 0; i < n; i++) arr.put(hits.get(i).toJson());

            out.put("ok", true);
            out.put("wd", q);
            out.put("total", map.size());
            out.put("count", arr.length());
            out.put("truncated", hits.size() > n);
            out.put("items", arr);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", String.valueOf(e.getMessage()));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private static String[] splitKeys(String q) {
        String low = q.toLowerCase(Locale.US);
        List<String> ks = new ArrayList<>();
        for (String s : low.split("[\\s,，、/]+")) {
            if (!s.isEmpty() && !ks.contains(s)) ks.add(s);
        }
        return ks.toArray(new String[0]);
    }

    /** -1 不匹配；0 名称完全等于 / 1 名称前缀 / 2 名称包含 / 3 仅分组命中 */
    private static int rank(Hit h, String[] keys) {
        if (keys.length == 0) return 0;
        String name = h.name.toLowerCase(Locale.US);
        String both = (name + " " + h.group).toLowerCase(Locale.US);
        for (String k : keys) if (!both.contains(k)) return -1;
        String first = keys[0];
        if (name.equals(first)) return 0;
        if (name.startsWith(first)) return 1;
        if (name.contains(first)) return 2;
        return 3;
    }

    private static final class Hit {
        final String name;
        String group, logo;
        final List<String> urls = new ArrayList<>();
        int rank;

        Hit(Model.Channel ch) {
            this.name = ch.name;
            this.group = ch.group == null ? "" : ch.group;
            this.logo = ch.logo == null ? "" : ch.logo;
            if (ch.urls != null) {
                for (String u : ch.urls) if (u != null && !u.isEmpty()) urls.add(u);
            }
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("group", group.isEmpty() ? "其他" : group);
            o.put("logo", logo);
            JSONArray a = new JSONArray();
            for (String u : urls) a.put(u);
            o.put("urls", a);
            o.put("lines", a.length());
            return o;
        }
    }
}
