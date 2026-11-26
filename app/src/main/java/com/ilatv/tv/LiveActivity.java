package com.ilatv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 直播选台：TV 风格左右分栏。
 * 左侧分组（编号徽章 + 分组名 + 频道数），右侧频道（编号徽章 + 名称 + 收藏星标），
 * 选中项蓝色高亮圆角；最右为「节目单 / 频道信息」竖排入口。
 */
public class LiveActivity extends Activity {

    private static final int BLACK = 0xFF000000;
    private static final int SEL_BLUE = 0xFF2F7CF6;
    private static final int SEL_BLUE_EDGE = 0xFFD8E6FF;
    private static final int SEL_GROUP_BG = 0xFF16233A;
    private static final String GROUP_ALL = Model.LIVE_GROUP_ALL;
    private static final String GROUP_FAV = Model.LIVE_GROUP_FAV;
    private static final int SEL_GROUP_TEXT = 0xFF7FB0FF;
    private static final int STAR_ON = 0xFFFFD54F;
    private static final int STAR_OFF = 0xFFB7BCC6;
    private static final int TEXT = 0xFFECEFF5;
    private static final int SUB = 0xFF9AA3B2;

    private ListView groupList;
    private ListView channelList;
    private ProgressBar progress;
    private TextView tip;
    private TextView epgBtn;

    private final List<Model.Channel> all = new ArrayList<>();
    private final List<Model.Channel> shown = new ArrayList<>();
    private final List<Integer> shownNo = new ArrayList<>();   // 全局频道编号
    private final List<Group> groups = new ArrayList<>();

    private GroupAdapter groupAdapter;
    private ChannelAdapter channelAdapter;
    private int selectedGroup = 0;
    private int selectedChannel = -1;
    private int rowH = 0;            // 按屏幕高度自适应；一级/二级目录共用
    // ---- v74 直播选台缩放：框宽/行高/内边距/徽章 + 框内字体一起按百分比 ----
    private float s = 1f;           // 100% = 1；由 Store.liveUiScale 读取
    private float e = 1f;           // 字体大小缩放，由 Store.epgFontScale 读取
    private int dpS(int v) { return Math.max(1, (int) (Ui.dp(this, v) * s + 0.5f)); }
    private float fs(int v) { return v * s; }
    private int sz(int v) { return Math.max(1, Math.round(v * s)); }
    private String searchQuery = "";

    /** 分组类型：0 全部 / 1 收藏 / 2 普通分组 */
    private static class Group {
        String name;
        int count;
        int kind;
        Group(String name, int count, int kind) {
            this.name = name;
            this.count = count;
            this.kind = kind;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 边框 / 字体大小百分比：网页后台与手机版设置页改完写进 Store，这里进入页面即读
        s = Store.liveUiScale(this) / 100f;
        e = Store.epgFontScale(this) / 100f;

        int h = getResources().getDisplayMetrics().heightPixels;
        // 一级目录与二级目录显示大小一致，以二级目录（频道列）为准
        rowH = Math.max(dpS(40), Math.min((int) (h / 10 * s), dpS(64)));

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(BLACK);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // ---------- 顶部工具条（低调，不抢选台视觉） ----------
        LinearLayout tools = Ui.row(this);
        tools.setBackgroundColor(0xFF0A0A0A);
        tools.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        tools.setGravity(Gravity.CENTER_VERTICAL);
        View btnBack = topBtn("返回");
        View btnRefresh = topBtn("刷新");
        View btnSubs = topBtn("订阅管理");
        View btnAdd = topBtn("加频道");
        View btnSearch = topBtn("搜索");
        tools.addView(btnBack);
        tools.addView(space(8));
        tools.addView(btnRefresh);
        tools.addView(space(8));
        tools.addView(btnSubs);
        tools.addView(space(8));
        tools.addView(btnAdd);
        tools.addView(space(8));
        tools.addView(btnSearch);
        tools.addView(space(8));
        tip = Ui.text(this, "", 12, SUB);
        tip.setSingleLine(true);
        tip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tools.addView(tip, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(tools);
        hsv.setBackgroundColor(0xFF0A0A0A);
        root.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 主体：左分组 / 右频道 ----------
        LinearLayout body = Ui.row(this);
        body.setBackgroundColor(BLACK);
        root.addView(body, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        groupList = new ListView(this);
        groupList.setDivider(null);
        groupList.setDividerHeight(0);
        groupList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        groupList.setPadding(dpS(8), dpS(8), dpS(8), dpS(8));
        groupList.setBackgroundColor(BLACK);
        // 初始给个保守宽度；拿到分组数据后 adjustGroupWidth() 按最长文字重算
        body.addView(groupList, Ui.lp(dpS(190),
                ViewGroup.LayoutParams.MATCH_PARENT, 0f));

        View divider = new View(this);
        divider.setBackgroundColor(0xFF242424);
        body.addView(divider, new LinearLayout.LayoutParams(Ui.dp(this, 1),
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout right = new FrameLayout(this);
        right.setBackgroundColor(BLACK);
        body.addView(right, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        channelList = new ListView(this);
        channelList.setDivider(null);
        channelList.setDividerHeight(0);
        channelList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        channelList.setPadding(dpS(12), dpS(8), dpS(46), dpS(8));   // 右侧留给节目单入口
        channelList.setBackgroundColor(BLACK);
        right.addView(channelList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        epgBtn = new TextView(this);
        epgBtn.setText("节\n目\n单\n›");
        epgBtn.setTextColor(0xFFD8D8D8);
        epgBtn.setTextSize(13 * e);   // 节目单入口按独立百分比缩放
        epgBtn.setGravity(Gravity.CENTER);
        epgBtn.setBackgroundColor(0xFF1C1C1C);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(0xFF1C1C1C);
        eg.setCornerRadius(Ui.dp(this, 10));
        eg.setStroke(Ui.dp(this, 1), 0xFF3A3A3A);
        epgBtn.setBackground(eg);
        epgBtn.setPadding(dpS(4), dpS(14), dpS(4), dpS(14));
        FrameLayout.LayoutParams epgLp = new FrameLayout.LayoutParams(
                dpS(30), ViewGroup.LayoutParams.WRAP_CONTENT);
        epgLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        epgLp.rightMargin = dpS(6);
        right.addView(epgBtn, epgLp);

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams pgLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pgLp.gravity = Gravity.CENTER;
        right.addView(progress, pgLp);
        progress.setVisibility(View.GONE);

        // ---------- 交互 ----------
        btnBack.setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> load());
        btnSubs.setOnClickListener(v -> showSubsDialog());
        btnAdd.setOnClickListener(v -> showAddChannelDialog());
        btnSearch.setOnClickListener(v -> showSearchDialog());
        epgBtn.setFocusable(false);   // 确定键留给频道列表播放，别被「频道信息」抢走
        epgBtn.setOnClickListener(v -> showEpgDialog());

        groupList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= groups.size()) return;
                selectedGroup = pos;
                selectedChannel = 0;
                rebuildChannels();
                if (groupAdapter != null) groupAdapter.notifyDataSetChanged();
            }
        });

        channelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= shown.size()) return;
                selectedChannel = pos;
                if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
                openPlayer(pos);
            }
        });

        channelList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           final int pos, long id) {
                if (pos >= shown.size()) return true;
                final Model.Channel ch = shown.get(pos);
                if (ch.manual) {
                    new AlertDialog.Builder(LiveActivity.this)
                            .setTitle(ch.name)
                            .setMessage("本机频道：可改名、换地址或删除。")
                            .setPositiveButton("编辑", (d, w) -> showChannelDialog(ch.name))
                            .setNeutralButton("删除", (d, w) -> {
                                Store.removeManualChannel(LiveActivity.this, ch.name);
                                Store.removeLiveFav(LiveActivity.this, ch.name);
                                load();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } else {
                    new AlertDialog.Builder(LiveActivity.this)
                            .setTitle(ch.name)
                            .setMessage("分组：" + ch.group + "\n线路数：" + ch.urls.size()
                                    + "\n\n首条地址：" + (ch.urls.isEmpty() ? "无" : ch.urls.get(0)))
                            .setPositiveButton("知道了", null)
                            .show();
                }
                return true;
            }
        });

        load();

        String open = getIntent().getStringExtra("open");
        if ("subs".equals(open)) showSubsDialog();
        else if ("add".equals(open)) showAddChannelDialog();
    }

    // ---------- 小工具 ----------

    private View topBtn(String s) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(s);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1C1C1C);
        g.setCornerRadius(Ui.dp(this, 7));
        g.setStroke(Ui.dp(this, 1), 0xFF333333);
        b.setBackground(g);
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        return b;
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, dp), 1));
        return v;
    }

    private View gap(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, dp)));
        return v;
    }

    private static GradientDrawable rounded(int color, int radiusDp, int strokeDp, int strokeColor,
                                             Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(Ui.dp(c, strokeDp), strokeColor);
        return g;
    }

    private GradientDrawable badgeBg() {
        return rounded(0xFF121212, 8, 1, 0xFF7C7C7C, this);
    }

    private GradientDrawable pillBg() {
        return rounded(0xFF2B2B2B, 999, 0, 0, this);
    }

    private GradientDrawable selectedChannelBg() {
        return rounded(SEL_BLUE, 12, 2, SEL_BLUE_EDGE, this);
    }

    private GradientDrawable selectedGroupBg() {
        return rounded(SEL_GROUP_BG, 10, 0, 0, this);
    }

    private GradientDrawable plainBg() {
        return rounded(0x00000000, 12, 0, 0, this);
    }

    // ---------- 数据加载 ----------

    private void openPlayer(int pos) {
        Intent i = new Intent(this, LivePlayerActivity.class);
        i.putExtra("channels", new ArrayList<>(shown));
        i.putExtra("index", pos);
        startActivity(i);
    }

    /** 缓存优先：先把上次缓存铺到列表，后台下载成功再替换，失败沿用缓存。 */
    private void load() {
        progress.setVisibility(View.VISIBLE);
        tip.setText("正在加载频道…");
        final int allSubCount = Store.liveSubs(this).size();
        final int subOn = Store.enabledLiveSubs(this).size();
        final int manualCount = Store.manualChannels(this).size();

        LiveLoader.load(this, new LiveLoader.Callback() {
            @Override
            public void onCached(List<Model.Channel> cached, long ts) {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                all.clear();
                all.addAll(cached);
                tip.setText("上次缓存 " + cached.size() + " 个频道（"
                        + LiveLoader.ageText(ts) + "）· 正在更新…");
                rebuildGroups();
                rebuildChannels();
            }

            @Override
            public void onResult(LiveLoader.Result r) {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                all.clear();
                all.addAll(r.channels);
                String base = all.size() + " 个频道 · 订阅 "
                        + subOn + "/" + allSubCount + " 启用 · " + manualCount + " 个本机";
                if (r.usedCache) {
                    base = "下载失败，沿用上次缓存（" + LiveLoader.ageText(r.cacheTime)
                            + "）· " + base;
                } else if (r.failSubs > 0) {
                    base += " · " + r.failSubs + " 份订阅失败";
                }
                String last = Store.lastLive(LiveActivity.this);
                tip.setText(!last.isEmpty() ? base + " · 上次 " + last : base);
                rebuildGroups();
                rebuildChannels();
            }
        });
    }

    private void rebuildGroups() {
        // 重建前记住当前分组（kind + 名字），与播放页抽屉保持同一套顺序与选中语义
        int keepKind = -1;
        String keepName = null;
        if (selectedGroup >= 0 && selectedGroup < groups.size()) {
            Group g = groups.get(selectedGroup);
            keepKind = g.kind;
            keepName = g.name;
        }

        groups.clear();
        // 第一排固定「★ 我的收藏」，与播放页抽屉一致
        int favCount = 0;
        for (Model.Channel ch : all) {
            if (Store.isLiveFav(this, ch.name)) favCount++;
        }
        groups.add(new Group(GROUP_FAV, favCount, Model.LIVE_KIND_FAV));
        groups.add(new Group(GROUP_ALL, all.size(), Model.LIVE_KIND_ALL));

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Model.Channel ch : all) {
            String g = ch.group == null || ch.group.isEmpty() ? "未分组" : ch.group;
            names.add(g);
        }
        for (String name : names) {
            int n = 0;
            for (Model.Channel ch : all) {
                String g = ch.group == null || ch.group.isEmpty() ? "未分组" : ch.group;
                if (g.equals(name)) n++;
            }
            groups.add(new Group(name, n, Model.LIVE_KIND_NORMAL));
        }

        // 与播放页抽屉共用 Model.liveGroupPick()，两页顺序与选中语义一致
        selectedGroup = Model.liveGroupPick(groupRefs(), keepKind, keepName);
        groupAdapter = new GroupAdapter();
        groupList.setAdapter(groupAdapter);
        adjustGroupWidth();
    }

    /**
     * 与播放页抽屉同一套规则：一级目录按文字自适应收窄，字号保持 16sp 不变，
     * 省下的宽度全部给二级目录；超长分组名用省略号，不挤占频道列表。
     */
    private void adjustGroupWidth() {
        if (groups.isEmpty()) return;
        TextView nameProbe = new TextView(this);
        nameProbe.setTextSize(fs(16));
        android.graphics.Paint np = nameProbe.getPaint();
        TextView countProbe = new TextView(this);
        countProbe.setTextSize(fs(11));
        android.graphics.Paint cp = countProbe.getPaint();

        float maxName = 0, maxCount = 0;
        for (Group g : groups) {
            maxName = Math.max(maxName, np.measureText(g.name));
            maxCount = Math.max(maxCount, cp.measureText(String.valueOf(g.count)));
        }
        float d = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        // 行左右各 10 + 名称左 16 右 10 + 数量徽章左右各 10 + 列表左右各 8 + 余量 6
        float content = (10 + 16 + 10 + 10 + 10 + 10 + 8 + 8 + 6) * d * s
                + maxName + maxCount;
        int minW = dpS(200);
        int maxW = Math.max(minW, (int) (screenW * 0.26f * s));
        int w = (int) Math.max(minW, Math.min(maxW, content));

        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) groupList.getLayoutParams();
        if (lp == null || (lp.width == w && lp.weight == 0f)) return;
        lp.width = w;
        lp.weight = 0f;
        groupList.setLayoutParams(lp);
    }

    private List<Model.LiveGroupRef> groupRefs() {
        List<Model.LiveGroupRef> r = new ArrayList<>();
        for (Group g : groups) r.add(new Model.LiveGroupRef(g.name, g.kind));
        return r;
    }

    private void rebuildChannels() {
        shown.clear();
        shownNo.clear();
        if (groups.isEmpty()) return;
        Group g = groups.get(selectedGroup);
        String q = searchQuery.trim().toLowerCase();

        for (int i = 0; i < all.size(); i++) {
            Model.Channel ch = all.get(i);
            String cg = ch.group == null || ch.group.isEmpty() ? "未分组" : ch.group;
            boolean inGroup;
            if (g.kind == 0) inGroup = true;
            else if (g.kind == 1) inGroup = Store.isLiveFav(this, ch.name);
            else inGroup = g.name.equals(cg);
            if (!inGroup) continue;
            if (!q.isEmpty() && !ch.name.toLowerCase().contains(q)) continue;
            shown.add(ch);
            shownNo.add(i + 1);          // 全局编号，贴近 TV 列表的大编号观感
        }
        if (selectedChannel >= shown.size()) {
            selectedChannel = shown.isEmpty() ? -1 : 0;
        }
        channelAdapter = new ChannelAdapter();
        channelList.setAdapter(channelAdapter);
        if (shown.isEmpty()) {
            Toast.makeText(this, all.isEmpty()
                    ? "还没有频道，点「订阅管理」添加 M3U / TXT，或用「加频道」填一个地址"
                    : "当前分组没有匹配的频道", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 左侧分组 Adapter ----------

    private class GroupAdapter extends BaseAdapter {
        // 一级目录不显示序号，只保留分组名 + 该组频道数
        private class H {
            TextView name, count;
        }

        @Override
        public int getCount() { return groups.size(); }
        @Override
        public Object getItem(int p) { return groups.get(p); }
        @Override
        public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            H h;
            LinearLayout row;
            if (cv == null) {
                row = Ui.row(LiveActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                // 行高 / 字号对齐二级目录（频道列）；一级目录不画序号徽章
                row.setMinimumHeight(rowH);
                row.setPadding(dpS(10), dpS(8), dpS(10), dpS(8));
                h = new H();

                h.name = Ui.text(LiveActivity.this, "", sz(16), TEXT);
                h.name.setSingleLine(true);
                h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                h.name.setPadding(dpS(16), 0, dpS(10), 0);
                row.addView(h.name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                h.count = Ui.text(LiveActivity.this, "", sz(11), 0xFFD2D2D2);
                h.count.setGravity(Gravity.CENTER);
                h.count.setBackground(pillBg());
                h.count.setPadding(dpS(10), dpS(4), dpS(10), dpS(4));
                h.count.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dpS(28)));
                row.addView(h.count);

                row.setTag(h);
            } else {
                row = (LinearLayout) cv;
                h = (H) row.getTag();
            }

            Group g = groups.get(pos);
            boolean sel = pos == selectedGroup;
            h.name.setText(g.name);
            h.name.setTextColor(sel ? SEL_GROUP_TEXT : TEXT);
            h.count.setText(String.valueOf(g.count));
            row.setBackground(sel ? selectedGroupBg() : plainBg());
            return row;
        }
    }

    // ---------- 右侧频道 Adapter ----------

    private class ChannelAdapter extends BaseAdapter {
        private class H {
            TextView no, name, star;
        }

        @Override
        public int getCount() { return shown.size(); }
        @Override
        public Object getItem(int p) { return shown.get(p); }
        @Override
        public long getItemId(int p) { return p; }

        @Override
        public View getView(final int pos, View cv, ViewGroup parent) {
            H h;
            LinearLayout row;
            if (cv == null) {
                row = Ui.row(LiveActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(rowH);
                row.setPadding(dpS(10), dpS(8), dpS(10), dpS(8));
                h = new H();

                h.no = Ui.text(LiveActivity.this, "", sz(11), TEXT);
                h.no.setGravity(Gravity.CENTER);
                h.no.setBackground(badgeBg());
                h.no.setLayoutParams(new LinearLayout.LayoutParams(
                        dpS(46), dpS(28)));
                row.addView(h.no);

                h.name = Ui.text(LiveActivity.this, "", sz(16), TEXT);
                h.name.setSingleLine(true);
                h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                h.name.setPadding(dpS(16), 0, dpS(10), 0);
                row.addView(h.name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                h.star = Ui.text(LiveActivity.this, "", sz(20), STAR_OFF);
                h.star.setGravity(Gravity.CENTER);
                h.star.setFocusable(false);
                h.star.setLayoutParams(new LinearLayout.LayoutParams(
                        dpS(38), dpS(36)));
                row.addView(h.star);

                row.setTag(h);
            } else {
                row = (LinearLayout) cv;
                h = (H) row.getTag();
            }

            final Model.Channel ch = shown.get(pos);
            h.no.setText(String.valueOf(shownNo.get(pos)));
            h.name.setText(ch.name);

            final boolean fav = Store.isLiveFav(LiveActivity.this, ch.name);
            h.star.setText(fav ? "★" : "☆");
            h.star.setTextColor(fav ? STAR_ON : STAR_OFF);
            h.star.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean now = Store.toggleLiveFav(LiveActivity.this, ch.name);
                    Toast.makeText(LiveActivity.this,
                            now ? "已收藏 " + ch.name : "已取消收藏 " + ch.name,
                            Toast.LENGTH_SHORT).show();
                    rebuildGroups();     // 收藏数量会变
                    rebuildChannels();   // 收藏分组内容会变
                }
            });

            boolean sel = pos == selectedChannel;
            row.setBackground(sel ? selectedChannelBg() : plainBg());
            h.name.setTextColor(sel ? Color.WHITE : TEXT);
            h.no.setTextColor(sel ? Color.WHITE : TEXT);
            return row;
        }
    }

    // ---------- 搜索 / 节目单 ----------

    private void showSearchDialog() {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), 0);
        final EditText e = Ui.input(this, "频道名关键词");
        e.setText(searchQuery);
        e.setSelection(e.getText().length());
        box.addView(e);

        new AlertDialog.Builder(this)
                .setTitle("搜索频道")
                .setMessage("在当前分组内按频道名过滤；点「清空」恢复全部。")
                .setView(box)
                .setPositiveButton("搜索", (d, w) -> {
                    searchQuery = e.getText().toString().trim();
                    selectedChannel = shown.isEmpty() ? -1 : 0;
                    rebuildChannels();
                })
                .setNeutralButton("清空", (d, w) -> {
                    searchQuery = "";
                    selectedChannel = 0;
                    rebuildChannels();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEpgDialog() {
        int pos = selectedChannel;
        if (pos < 0 || pos >= shown.size()) pos = shown.isEmpty() ? -1 : 0;
        if (pos < 0) {
            Toast.makeText(this, "还没有可查看的频道", Toast.LENGTH_SHORT).show();
            return;
        }
        Model.Channel ch = shown.get(pos);
        StringBuilder sb = new StringBuilder();
        sb.append("分组：").append(ch.group).append('\n');
        sb.append("线路：").append(ch.urls.size()).append(" 条\n");
        sb.append("收藏：").append(Store.isLiveFav(this, ch.name) ? "是" : "否").append("\n\n");
        for (int i = 0; i < ch.urls.size(); i++) {
            sb.append(i + 1).append(". ").append(ch.urls.get(i)).append('\n');
        }
        sb.append("\n本版本未接入 EPG / XMLTV 节目单，这里展示当前频道的可用线路信息。");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(ch.name + " · 频道信息")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .create();
        d.show();
        // 节目单 / 频道信息文字按「字体大小」百分比独立缩放
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) msg.setTextSize(14 * e);
    }

    // ---------- 订阅管理（可编辑 / 启停 / 删除） ----------

    /** 订阅管理对话框与直播播放页共用 LiveSubsDialog，改完重载本页频道列表 */
    private void showSubsDialog() {
        LiveSubsDialog.show(this, new Runnable() {
            @Override public void run() { load(); }
        }, null);
    }

    // ---------- 本机频道（新增 / 编辑 / 删除） ----------

    private void showAddChannelDialog() {
        showChannelDialog(null);
    }

    /** oldName == null 表示新增，否则编辑该本机频道 */
    private void showChannelDialog(final String oldName) {
        final boolean isNew = oldName == null;
        Model.Channel old = null;
        if (!isNew) {
            for (Model.Channel ch : Store.manualChannels(this)) {
                if (ch.name.equals(oldName)) { old = ch; break; }
            }
            if (old == null) return;
        }
        final Model.Channel oldCh = old;

        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);
        final EditText name = Ui.input(this, "频道名，如「CCTV-1 综合」");
        final EditText url = Ui.input(this, "https://域名/live.m3u8");
        if (!isNew) {
            name.setText(oldCh.name);
            name.setSelection(name.getText().length());
            if (!oldCh.urls.isEmpty()) {
                url.setText(oldCh.urls.get(0));
                url.setSelection(url.getText().length());
            }
        }
        box.addView(name);
        box.addView(gap(8));
        box.addView(url);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isNew ? "添加单个频道" : "编辑本机频道")
                .setMessage(isNew
                        ? "直接填一个 HTTP(S) m3u8 / MP4 地址，存到本机。"
                        : "改名或换首条地址；其余线路会保留。长按列表项也能管理。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        Toast.makeText(this, "只支持 http(s) 地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Model.Channel ch = new Model.Channel(
                            n.isEmpty() ? "未命名频道" : n,
                            isNew ? "本机频道" : oldCh.group,
                            isNew ? "" : oldCh.logo);
                    ch.urls.add(u);
                    if (!isNew) {
                        for (int i = 1; i < oldCh.urls.size(); i++) {
                            String extra = oldCh.urls.get(i);
                            if (!ch.urls.contains(extra)) ch.urls.add(extra);
                        }
                    }
                    ch.manual = true;
                    if (isNew) {
                        Store.addManualChannel(this, ch);
                    } else {
                        // 改名时同步迁移收藏，收藏按频道名记录
                        if (!oldName.equals(ch.name)) {
                            boolean wasFav = Store.isLiveFav(this, oldName);
                            Store.removeLiveFav(this, oldName);
                            if (wasFav) Store.toggleLiveFav(this, ch.name);
                        }
                        Store.updateManualChannel(this, oldName, ch);
                    }
                    load();
                    Toast.makeText(this, isNew ? "已添加" : "已保存",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null);
        if (!isNew) {
            b.setNeutralButton("删除", (d, w) -> {
                Store.removeManualChannel(this, oldName);
                Store.removeLiveFav(this, oldName);
                load();
            });
        }
        b.show();
    }
}
