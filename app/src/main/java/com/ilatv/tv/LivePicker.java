package com.ilatv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * TV 风格「选台抽屉」：平时 GONE，单击屏幕 / OK / 菜单键从左侧滑出。
 *
 * 参考常见 IPTV 播放器：左侧分组（不显示序号）、右侧频道列表（带台号徽章）、
 * 蓝色高亮选中、星标收藏、右缘「节目单」竖排入口、点右侧空白收起。
 * 频道列聚焦时按右键可切换当前高亮频道的收藏，再按一次即取消。
 * 顶部不放任何工具条：刷新 / 订阅 / 加频道 / 选台页 / 搜索 / 线路 / 播放暂停
 * 全部收进功能菜单（长按画面 / 长按抽屉空白处 / 遥控器菜单键），
 * 右缘不再放「更多」按钮。
 * 本类只负责「选台 UI 与数据」，不碰播放内核；通过 Listener 回调给播放页。
 */
public class LivePicker {

    public interface Listener {
        /** 选中某频道；globalIndex 是在完整频道列表中的位置 */
        void onPick(Model.Channel ch, int globalIndex);
        /** 用户主动收起面板 */
        void onDismiss();
        /** 任意交互：播放页据此重置自动隐藏计时 */
        void onInteraction();
        /** 右缘「节目单」入口 */
        void onEpg(Model.Channel ch);
        void onTogglePlay();
        /** 打开管理页：which = subs / add / list */
        void onOpenManage(String which);
        void onReload();
        /** 直播解码档位在菜单里改了，播放页据此立即重建播放内核 */
        void onDecodeModeChanged();
        /** 直播画面比例在菜单里改了，播放页据此立即重排画面 */
        void onAspectModeChanged();
        /** 菜单「换台反转」改了，播放页据此刷新缓存并给一次 OSD 提示 */
        void onChannelReverseChanged();
        /** 菜单「分组管理」改了显示 / 隐藏，播放页按新名单重过滤频道（不打断播放） */
        void onHiddenGroupsChanged();
        /** 菜单「影视点播」：回到首页点播 */
        void onBackToHome();
    }

    private static final int PANEL_BG = 0xF2000000;
    private static final int SEL_BLUE = 0xFF2F7CF6;
    private static final int SEL_EDGE = 0xFFD8E6FF;
    private static final int SEL_GROUP_BG = 0xFF16233A;
    private static final int SEL_GROUP_TEXT = 0xFF7FB0FF;
    private static final int STAR_ON = 0xFFFFD54F;
    private static final int STAR_OFF = 0xFFB7BCC6;
    private static final int TEXT = 0xFFECEFF5;

    private final Activity act;
    private final Listener L;
    private final FrameLayout overlay;
    private final LinearLayout panel;
    private final FrameLayout rightPane;   // 二级目录容器，吃掉一级目录收窄后剩下的宽度
    private final ListView groupList;
    private final ListView channelList;
    private int panelW;                    // 抽屉总宽（改设置后可重算），用于限制一级目录的最大宽度

    private final List<Model.Channel> all = new ArrayList<>();
    private final List<Model.Channel> shown = new ArrayList<>();
    private final List<Integer> shownGlobal = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final Set<String> favs = new HashSet<>();

    private GroupAdapter groupAdapter;
    private ChannelAdapter channelAdapter;
    private int rowH;               // 行高，按屏幕高度自适应；一级/二级目录共用
    // ---- v74 直播选台缩放：框宽/行高/内边距/徽章 + 框内字体一起按百分比 ----
    private float s = 1f;           // 100% = 1；由 Store.liveUiScale 读取
    private float e = 1f;           // 字体大小缩放，由 Store.epgFontScale 读取
    private TextView epgSide;       // 右缘「节目单」入口，换比例时即时刷新字号
    /** 按缩放系数换算 dp；最小 1 保证分隔线/徽章在小比例下不消失 */
    private int dpS(int v) { return Math.max(1, (int) (Ui.dp(act, v) * s + 0.5f)); }
    /** 缩放后的 sp 字号（setTextSize 用 float） */
    private float fs(int v) { return v * s; }
    /** 缩放后的整数 sp 字号（Ui.text 的 sizeSp 是 int） */
    private int sz(int v) { return Math.max(1, Math.round(v * s)); }
    private static final String GROUP_ALL = Model.LIVE_GROUP_ALL;
    private static final String GROUP_FAV = Model.LIVE_GROUP_FAV;

    private int selectedGroup = 0;
    private int selectedPos = -1;
    private int focusCol = 1;   // 选台抽屉焦点列：0=左侧分组列，1=右侧频道列
    private String currentName = "";
    private String query = "";
    /**
     * 当前弹出的模态对话框数量。用计数而非单个引用：订阅管理里会出现
     * 「主对话框仍在 + 上面再盖一个删除确认」的叠加，单个引用会在确认框
     * 关闭时被误清零，导致底下的主对话框重新被播放页抢走按键。
     */
    private int modalDialogs = 0;

    private static class Group {
        String name;
        int count;
        int kind;                 // 0 全部 / 1 收藏 / 2 普通分组
        Group(String n, int c, int k) { name = n; count = c; kind = k; }
    }

    public LivePicker(Activity act, Listener L) {
        this.act = act;
        this.L = L;
        this.s = Store.liveUiScale(act) / 100f;
        this.e = Store.epgFontScale(act) / 100f;

        overlay = new FrameLayout(act);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        // 抽屉内所有子控件都不接管遥控器焦点，方向键/确定键统一由播放页处理。
        // 否则右缘「节目单」入口会抢到焦点，按确定变成弹「频道信息」而不播放。
        overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        overlay.setVisibility(View.GONE);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右侧/空白点击区：点视频区域收起面板
        View dismiss = new View(act);
        dismiss.setBackgroundColor(Color.TRANSPARENT);
        dismiss.setClickable(true);
        dismiss.setOnClickListener(v -> {
            hide();
            L.onDismiss();
        });
        overlay.addView(dismiss, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        recomputeMetrics(dm);

        panel = Ui.column(act);
        panel.setBackgroundColor(PANEL_BG);
        // 面板自身吃掉空白点击，避免点到面板空白处误触发「收起」
        panel.setClickable(true);
        panel.setOnClickListener(v -> L.onInteraction());
        panel.setLongClickable(true);
        panel.setOnLongClickListener(v -> {
            L.onInteraction();
            openToolsMenu();
            return true;
        });
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
        overlay.addView(panel, panelLp);

        // ---- 顶部不再放任何工具条：分组 / 频道列表直接从顶部铺满 ----
        // 原来的 8 个按钮和快捷键提示行整体删除；功能菜单只由长按 / 菜单键唤出
        // ---- 主体：分组 / 频道 ----
        LinearLayout body = Ui.row(act);
        panel.addView(body, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        groupList = new ListView(act);
        groupList.setDivider(null);
        groupList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        groupList.setPadding(dpS(4), dpS(10), dpS(4), dpS(2));
        // 初始给个保守宽度；拿到分组数据后 adjustGroupWidth() 会按最长文字重算
        body.addView(groupList, Ui.lp(dpS(150),
                ViewGroup.LayoutParams.MATCH_PARENT, 0f));

        View div = new View(act);
        div.setBackgroundColor(0xFF242424);
        body.addView(div, new LinearLayout.LayoutParams(Ui.dp(act, 1),
                ViewGroup.LayoutParams.MATCH_PARENT));

        rightPane = new FrameLayout(act);
        body.addView(rightPane, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        channelList = new ListView(act);
        channelList.setDivider(null);
        channelList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        channelList.setPadding(dpS(6), dpS(10), dpS(26), dpS(2));
        rightPane.addView(channelList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 两列都不接管系统焦点：方向键统一交给播放页 dispatchKeyEvent 处理。
        // 否则 ListView 自带焦点会偷偷移动自己的选中项，而蓝色高亮由 selectedPos
        // 单独绘制，两套状态不同步，到列表首/尾继续按键时就会出现「高亮卡住、
        // 实际却能换台」的现象。
        groupList.setFocusable(false);
        groupList.setFocusableInTouchMode(false);
        channelList.setFocusable(false);
        channelList.setFocusableInTouchMode(false);

        // 右缘竖排：只保留「节目单」一个极窄入口；「更多」按钮已按需求去掉，
        // 功能菜单改由长按画面 / 长按抽屉空白处 / 遥控器菜单键唤出。
        LinearLayout side = Ui.column(act);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(
                dpS(22), ViewGroup.LayoutParams.WRAP_CONTENT);
        sideLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        sideLp.rightMargin = dpS(4);

        epgSide = sideText("节\n目\n单\n›");
        TextView epg = epgSide;
        epg.setOnClickListener(v -> {
            L.onInteraction();
            Model.Channel ch = selectedChannel();
            if (ch != null) L.onEpg(ch);
            else Toast.makeText(act, "先选一个频道", Toast.LENGTH_SHORT).show();
        });
        side.addView(epg);
        rightPane.addView(side, sideLp);

        groupList.setOnItemClickListener((p, v, pos, id) -> {
            L.onInteraction();
            selectedGroup = pos;
            selectedPos = 0;
            rebuildChannels();
            refreshHighlights();
        });
        channelList.setOnItemClickListener((p, v, pos, id) -> {
            L.onInteraction();
            selectedPos = pos;
            confirm();
        });
        channelList.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= shown.size()) return true;
            L.onInteraction();
            L.onOpenManage("list");
            return true;
        });
    }

    /** 抽屉宽 / 行高按当前缩放系数重算；构造与改设置后共用这一份逻辑。 */
    private void recomputeMetrics(android.util.DisplayMetrics dm) {
        int screenW = dm.widthPixels, screenH = dm.heightPixels;
        // 抽屉只占约 42% 宽（乘缩放系数），夹在 290~500dp；右侧始终留出视频画面
        panelW = Math.max(dpS(180), Math.min(
                clamp((int) (screenW * 0.42f * s), dpS(290), dpS(500)),
                screenW - dpS(64)));
        // 一级目录与二级目录共用同一行高，显示大小以二级目录（频道列）为准
        rowH = clamp((int) (screenH / 10 * s), dpS(38), dpS(54));
    }

    /**
     * 后台改了「边框大小 / 字体大小」后由播放页低频比对调用：
     * 不重建抽屉、不丢当前选中项，只重算尺寸并换掉两个 Adapter
     * （换 Adapter 会清空 ListView 回收池，行视图按新系数重新创建）。
     */
    public void applyScale() {
        s = Store.liveUiScale(act) / 100f;
        e = Store.epgFontScale(act) / 100f;
        recomputeMetrics(act.getResources().getDisplayMetrics());
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp != null) { lp.width = panelW; panel.setLayoutParams(lp); }
        if (epgSide != null) epgSide.setTextSize(10 * e);
        groupAdapter = new GroupAdapter();
        groupList.setAdapter(groupAdapter);
        channelAdapter = new ChannelAdapter();
        channelList.setAdapter(channelAdapter);
        adjustGroupWidth();
        refreshHighlights();
    }

    // ---------- 对外 API ----------

    public void attach(FrameLayout parent) {
        parent.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public boolean isShowing() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    /**
     * 记录当前弹出的模态对话框。播放页默认接管方向键与确定键，
     * 若不临时放行，AlertDialog 的选项用遥控器根本选不了（功能菜单会形同虚设）。
     * 计数可正确处理连续弹框、以及主对话框上再叠一层确认框的情况。
     */
    public void trackDialog(AlertDialog d) {
        modalDialogs++;
        d.setOnDismissListener(x -> {
            if (modalDialogs > 0) modalDialogs--;
            L.onInteraction();
        });
    }

    public boolean hasModalDialog() {
        return modalDialogs > 0;
    }

    public void show() {
        ensureCurrentVisible();
        focusCol = 1;   // 默认聚焦右侧频道列
        overlay.setVisibility(View.VISIBLE);
        refreshHighlights();   // 可见后再重画/滚动，选中框一定看得见
        L.onInteraction();
    }

    public void hide() {
        overlay.setVisibility(View.GONE);
    }

    public void toggle() {
        if (isShowing()) {
            hide();
            L.onDismiss();
        } else {
            show();
        }
    }

    /** 由播放页把已拉取的频道列表塞进来，避免重复联网 */
    public void setData(List<Model.Channel> list) {
        all.clear();
        if (list != null) all.addAll(list);
        favs.clear();
        favs.addAll(Store.liveFavs(act));
        rebuildGroups();
        rebuildChannels();
    }

    public List<Model.Channel> allChannels() {
        return new ArrayList<>(all);
    }

    public int indexOfName(String name) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    /** 同步「正在播放」高亮；刷新后仍按名字命中 */
    public void setCurrent(String name) {
        currentName = name == null ? "" : name;
        rebuildChannels();
        refreshHighlights();
    }

    /** 上下移动频道列选中项：到首/尾就停住，每次都强制重画并滚动到可见 */
    public void move(int delta) {
        if (shown.isEmpty()) return;
        selectedPos = selectedPos < 0 ? 0 : clamp(selectedPos + delta, 0, shown.size() - 1);
        refreshHighlights();      // 边界处也执行，避免「选中已变、高亮卡在原地」
        L.onInteraction();
    }

    /** 上下移动分组列选中项：与频道列一致，到首/尾停住，不再循环回绕 */
    public void switchGroup(int delta) {
        if (groups.isEmpty()) return;
        int next = clamp(selectedGroup + delta, 0, groups.size() - 1);
        if (next != selectedGroup) {
            selectedGroup = next;
            selectedPos = 0;
            rebuildChannels();
        }
        refreshHighlights();      // 分组列也滚动，否则切组/到边界时高亮会跑出屏幕
        L.onInteraction();
    }

    /** 左右键：在分组列(0)/频道列(1)之间移动焦点，到边界也强制重画 */
    public void moveColumn(int delta) {
        focusCol = clamp(focusCol + delta, 0, 1);
        refreshHighlights();
        L.onInteraction();
    }

    /** 上下键：在焦点所在列里上下移动（分组列切分组，频道列切频道） */
    public void moveFocus(int delta) {
        if (focusCol == 0) switchGroup(delta);
        else move(delta);
    }

    /** 当前焦点列：0=分组列，1=频道列 */
    public int focusColumn() { return focusCol; }

    /**
     * 右键：焦点已在二级目录（频道列）时，切换当前高亮频道的收藏；
     * 焦点在一级目录时，仍按原逻辑把焦点移到频道列。
     * 频道列已是最右列，右键原本到边界无操作，正好复用为收藏开关。
     */
    public void onRight() {
        if (focusCol == 1) toggleFavSelected();
        else moveColumn(1);
    }

    /** 收藏 / 取消收藏当前高亮频道（再按一次右键即取消） */
    public void toggleFavSelected() {
        if (shown.isEmpty() || selectedPos < 0 || selectedPos >= shown.size()) return;
        toggleFav(shown.get(selectedPos));
    }

    /** OK / 确认：播放当前高亮项 */
    public void confirm() {
        if (shown.isEmpty()) return;
        if (selectedPos < 0 || selectedPos >= shown.size()) selectedPos = 0;
        L.onPick(shown.get(selectedPos), shownGlobal.get(selectedPos));
    }

    /** 数字键直选台号（1 起） */
    public boolean pickByNumber(int no) {
        int idx = no - 1;
        if (idx < 0 || idx >= all.size()) return false;
        L.onPick(all.get(idx), idx);
        return true;
    }

    public Model.Channel selectedChannel() {
        if (selectedPos >= 0 && selectedPos < shown.size()) return shown.get(selectedPos);
        return null;
    }

    public void clearQuery() {
        query = "";
    }

    // ---------- 内部构建 ----------

    private void ensureCurrentVisible() {
        if (!currentName.isEmpty()) {
            boolean found = false;
            for (Model.Channel ch : shown) {
                if (ch.name.equals(currentName)) { found = true; break; }
            }
            if (!found && !groups.isEmpty()) {
                selectedGroup = indexOfAll();   // 回到「全部频道」保证能看到正在播的台
                rebuildChannels();
            }
        }
    }

    private void rebuildGroups() {
        // 重建前记住当前选中的分组（按 kind + 名字），顺序调整 / 刷新 / 收藏增删后
        // 仍落回同一组，不会因为「收藏挪到第一排」而串到别的分组。
        int keepKind = -1;
        String keepName = null;
        if (selectedGroup >= 0 && selectedGroup < groups.size()) {
            Group g = groups.get(selectedGroup);
            keepKind = g.kind;
            keepName = g.name;
        }

        groups.clear();
        // 第一排固定「★ 我的收藏」，其余按原顺序后移
        int favCount = 0;
        for (Model.Channel ch : all) {
            if (favs.contains(ch.name)) favCount++;
        }
        groups.add(new Group(GROUP_FAV, favCount, Model.LIVE_KIND_FAV));
        groups.add(new Group(GROUP_ALL, all.size(), Model.LIVE_KIND_ALL));

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Model.Channel ch : all) {
            names.add(groupOf(ch));
        }
        for (String n : names) {
            int c = 0;
            for (Model.Channel ch : all) {
                if (groupOf(ch).equals(n)) c++;
            }
            groups.add(new Group(n, c, Model.LIVE_KIND_NORMAL));
        }

        // 与全屏选台页共用 Model.liveGroupPick()：收藏为空时默认落「全部频道」，
        // 刷新 / 收藏增删后按名字找回原来那一组，不会因为顺序调整串组。
        selectedGroup = Model.liveGroupPick(groupRefs(), keepKind, keepName);
        groupAdapter = new GroupAdapter();
        groupList.setAdapter(groupAdapter);
        adjustGroupWidth();
    }

    /**
     * 一级目录宽度按文字自适应：字号保持不变，只把列收窄到刚好放得下
     * 「最长分组名 + 数量徽章」，剩余空间全部让给二级目录。
     * 设上下限，避免分组名太短显得局促、或太长把频道列挤没（超长时省略号）。
     */
    private void adjustGroupWidth() {
        if (groups.isEmpty() || panelW <= 0) return;
        TextView nameProbe = new TextView(act);
        nameProbe.setTextSize(fs(14));   // 与 GroupAdapter 分组名字号一致（同步缩放）
        android.graphics.Paint np = nameProbe.getPaint();
        TextView countProbe = new TextView(act);
        countProbe.setTextSize(fs(10));  // 与数量徽章字号一致
        android.graphics.Paint cp = countProbe.getPaint();

        float maxName = 0, maxCount = 0;
        for (Group g : groups) {
            maxName = Math.max(maxName, np.measureText(g.name));
            maxCount = Math.max(maxCount, cp.measureText(String.valueOf(g.count)));
        }
        float d = act.getResources().getDisplayMetrics().density;
        // 行左右各 6 + 名称左 12 右 4 + 数量徽章左右各 5 + 列表左右各 4 + 呼吸余量 6
        float content = (6 + 12 + 4 + 5 + 5 + 6 + 4 + 4 + 6) * d * s + maxName + maxCount;
        int minW = dpS(132);
        int maxW = Math.max(minW, (int) (panelW * 0.40f));
        int w = (int) Math.max(minW, Math.min(maxW, content));

        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) groupList.getLayoutParams();
        if (lp == null || (lp.width == w && lp.weight == 0f)) return;
        lp.width = w;
        lp.weight = 0f;
        groupList.setLayoutParams(lp);   // 右侧 weight=1 自动吃下省出来的宽度
    }

    /** 把当前分组列表转成纯数据引用，交给 Model 做顺序 / 选中判定。 */
    private List<Model.LiveGroupRef> groupRefs() {
        List<Model.LiveGroupRef> r = new ArrayList<>();
        for (Group g : groups) r.add(new Model.LiveGroupRef(g.name, g.kind));
        return r;
    }

    /** 「全部频道」所在位置（ensureCurrentVisible 的兜底） */
    private int indexOfAll() {
        return Model.liveGroupAll(groupRefs());
    }

    private void rebuildChannels() {
        shown.clear();
        shownGlobal.clear();
        if (groups.isEmpty()) return;
        Group g = groups.get(selectedGroup);
        String q = query.trim().toLowerCase();
        int cur = -1;

        for (int i = 0; i < all.size(); i++) {
            Model.Channel ch = all.get(i);
            boolean in;
            if (g.kind == 0) in = true;
            else if (g.kind == 1) in = favs.contains(ch.name);
            else in = g.name.equals(groupOf(ch));
            if (!in) continue;
            if (!q.isEmpty() && !ch.name.toLowerCase().contains(q)) continue;
            shown.add(ch);
            shownGlobal.add(i);
            if (ch.name.equals(currentName)) cur = shown.size() - 1;
        }
        selectedPos = cur >= 0 ? cur : (shown.isEmpty() ? -1 : 0);
        channelAdapter = new ChannelAdapter();
        channelList.setAdapter(channelAdapter);
        if (cur >= 0) channelList.setSelection(Math.max(0, cur - 2));
    }

    private void notifyAllAdapters() {
        if (groupAdapter != null) groupAdapter.notifyDataSetChanged();
        if (channelAdapter != null) channelAdapter.notifyDataSetChanged();
    }

    /**
     * 每次按键后统一重画两列高亮，并把选中行滚动到可见区域。
     * 列表首/尾继续按键时同样执行，保证始终「看得到当前选中的是哪一项」。
     */
    private void refreshHighlights() {
        notifyAllAdapters();
        if (selectedGroup >= 0 && selectedGroup < groups.size()) {
            groupList.setSelection(Math.max(0, selectedGroup - 2));
        }
        if (selectedPos >= 0 && selectedPos < shown.size()) {
            channelList.setSelection(Math.max(0, selectedPos - 2));
        }
    }

    private static String groupOf(Model.Channel ch) {
        return ch.group == null || ch.group.isEmpty() ? "未分组" : ch.group;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void toggleFav(Model.Channel ch) {
        // 记住当前高亮项：rebuildChannels() 会把高亮重置到「正在播放」那一项，
        // 收藏后必须找回，否则连按右键时会觉得「收藏错了台」。
        Model.Channel cur = selectedChannel();
        String keepName = cur == null ? "" : cur.name;
        int keepPos = selectedPos;
        // 以存储层返回的「切换后是否已收藏」为准，保证内存集合与磁盘一致
        boolean added = Store.toggleLiveFav(act, ch.name);
        if (added) favs.add(ch.name);
        else favs.remove(ch.name);
        rebuildGroups();
        rebuildChannels();
        int p = indexOfShown(keepName);
        if (p >= 0) selectedPos = p;                       // 还在当前分组里：原地保持
        else if (!shown.isEmpty()) selectedPos = clamp(keepPos, 0, shown.size() - 1);
        else selectedPos = -1;                               // 在「我的收藏」里取消后本项消失
        refreshHighlights();
        Toast.makeText(act, (added ? "★ 已收藏 " : "☆ 已取消收藏 ") + ch.name,
                Toast.LENGTH_SHORT).show();
        L.onInteraction();
    }

    private int indexOfShown(String name) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    // ---------- 小控件 ----------

    /** 右缘极窄的竖排文字按钮 */
    private TextView sideText(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(0xFFD8D8D8);
        t.setTextSize(10 * e);      // 节目单入口按「字体大小」百分比独立缩放
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(0xFF1C1C1C, 10, 1, 0xFF3A3A3A));
        t.setPadding(dpS(2), dpS(7), dpS(2), dpS(7));
        t.setClickable(true);      // 仍可用鼠标/触摸点开「频道信息」
        t.setFocusable(false);     // 但不抢遥控器焦点，确定键必须留给播放
        return t;
    }

    /**
     * 对话框列表可视高度（v76）：按屏幕高度自适应。
     * AlertDialog.setView 自定义视图时，部分 ROM / 横屏会把超出高度的内容直接裁掉
     * 且不提供滚动（「边框大小」8 个档位只显示到一半就是这么来的）。
     * 统一给列表一个上限，超出的部分由内部 ScrollView 滚动。
     */
    private int dialogListHeight() {
        int h = act.getResources().getDisplayMetrics().heightPixels;
        int byScreen = (int) (h * 0.55f);
        return Math.max(Ui.dp(act, 180), Math.min(byScreen, Ui.dp(act, 400)));
    }

    /** 把一列可聚焦条目装进 ScrollView；方向键移动焦点时会自动滚到可见区。 */
    private android.widget.ScrollView dialogScroll(LinearLayout list) {
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.setFocusable(false);
        sv.setFocusableInTouchMode(false);
        sv.setVerticalScrollBarEnabled(true);
        sv.addView(list, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** 普通菜单条目：无 ●/○ 状态前缀，点击后关框再执行动作 */
    private View menuItem(String label, final Runnable action, final AlertDialog[] ref) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(0xFFECEFF5);
        t.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusableBg());
        t.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            L.onInteraction();
            action.run();
        });
        return t;
    }

    /**
     * 功能菜单：「影视点播」置顶，一键回首页点播。
     * 由长按画面、长按抽屉空白处或遥控器菜单键唤出，平时完全不占界面。
     * 收起选台改由返回键 / 点右侧空白完成；播放暂停由空格 / 媒体键完成；
     * 切换线路改由全屏播放时 ←→ 键完成，不再占菜单项。
     */
    public void openToolsMenu() {
        L.onInteraction();
        // 换台反转是开关类，菜单项直接带当前状态，电视上一眼能看清
        final String[] items = {
                "影视点播",
                "边框大小（当前：" + Store.liveUiScale(act) + "%）",
                "字体大小（当前：" + Store.epgFontScale(act) + "%）",
                "分组管理（隐藏 " + Store.hiddenLiveGroups(act).size() + " 个）",
                "订阅管理", "刷新频道", "画面比例", "解码方式",
                "换台反转（当前：" + (Store.channelReverse(act) ? "已反转" : "正常") + "）",
                "启动设置（当前：" + startupName(Store.startupPage(act)) + "）"
        };
        final Runnable[] actions = {
                () -> L.onBackToHome(),
                () -> showLiveUiScaleDialog(),
                () -> showEpgFontScaleDialog(),
                () -> showGroupManageDialog(),
                () -> L.onOpenManage("subs"),
                () -> L.onReload(),
                () -> showAspectDialog(),
                () -> showDecodeDialog(),
                () -> showChannelReverseDialog(),
                () -> showStartupDialog()
        };
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        LinearLayout list = Ui.column(act);
        View first = null;
        for (int i = 0; i < items.length; i++) {
            View v = menuItem(items[i], actions[i], ref);
            list.addView(v, choiceItemLp());
            if (first == null) first = v;
        }
        box.addView(dialogScroll(list), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight()));
        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("功能菜单")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        ref[0] = d;
        trackDialog(d);
        d.show();
        if (first != null) first.requestFocus();
    }

    /**
     * 通用自绘选择对话框。
     * 不用 AlertDialog 的 setSingleChoiceItems —— 部分电视 ROM 在同时设置了
     * setMessage 时不渲染单选列表，只剩一段提示文字和「关闭」，导致根本没法选。
     * 这里改成自绘的可聚焦条目：● 标当前值、蓝底高亮焦点，OK 即确认。
     * 启动设置与直播解码方式共用这套，避免两处各踩一次 ROM 兼容坑。
     */
    private interface ChoicePick {
        void pick(String value);
    }

    private void showChoiceDialog(String title, String tipText,
                                  final String[] labels, final String[] values,
                                  String current, final ChoicePick onPick) {
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 6), Ui.dp(act, 20), Ui.dp(act, 4));
        TextView tip = Ui.text(act, tipText, 13, 0xFFD2D2D2);
        tip.setPadding(0, 0, 0, Ui.dp(act, 10));
        box.addView(tip);

        LinearLayout list = Ui.column(act);
        View first = null, focus = null;
        for (int i = 0; i < labels.length; i++) {
            View item = choiceItem(labels[i], values[i].equals(current),
                    values[i], ref, onPick);
            list.addView(item, choiceItemLp());
            if (first == null) first = item;
            if (values[i].equals(current)) focus = item;
        }
        box.addView(dialogScroll(list), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight()));

        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        ref[0] = d;
        trackDialog(d);
        d.show();
        // 焦点直接落在当前生效项，按 OK 即确认，按 ↓ 可切到别的项
        (focus != null ? focus : first).requestFocus();
    }

    private LinearLayout.LayoutParams choiceItemLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 6);
        return lp;
    }

    /** 一条可聚焦的设置项：● 表示当前生效，方向键移焦点时蓝底高亮 */
    private View choiceItem(String label, boolean selected, final String value,
                            final AlertDialog[] ref, final ChoicePick onPick) {
        TextView t = new TextView(act);
        t.setText((selected ? "●  " : "○  ") + label);
        t.setTextSize(16);
        t.setTextColor(0xFFECEFF5);
        t.setPadding(Ui.dp(act, 14), Ui.dp(act, 12), Ui.dp(act, 14), Ui.dp(act, 12));
        t.setFocusable(true);
        t.setClickable(true);
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(SEL_BLUE, 8, 0, 0));
        bg.addState(new int[]{}, rounded(0xFF2B2B2B, 8, 0, 0));
        t.setBackground(bg);
        t.setOnClickListener(v -> {
            onPick.pick(value);
            if (ref[0] != null) ref[0].dismiss();
        });
        return t;
    }

    /**
     * 换台反转：与网页后台「换台反转」下拉共用同一份 Store 配置。
     * 播放页每次按键都直接读 Store，所以电视菜单 / 手机后台任一边改完都立即生效，
     * 不需要额外回调；用自绘可聚焦条目，避开部分 ROM 吞系统单选列表的坑。
     */
    private void showChannelReverseDialog() {
        showChoiceDialog("换台反转",
                "只反转全屏播放时 ↑↓ / 频道± / 媒体前后键的换台方向。\n"
                        + "选台界面的 ↑↓←→ 一律按原始方向，不反转。\n"
                        + "←→ 切线路、数字直选、OK 播放不受影响。\n"
                        + "手机后台「换台反转」是同一份设置，互不影响。",
                new String[]{ "正常（↑ 上一个台 · ↓ 下一个台）",
                        "反转（↑ 下一个台 · ↓ 上一个台）" },
                new String[]{ "off", "on" },
                Store.channelReverse(act) ? "on" : "off",
                value -> {
                    Store.setChannelReverse(act, "on".equals(value));
                    L.onChannelReverseChanged();
                });
    }

    /** 启动设置：与网页后台「启动页」下拉共用同一份 Store 配置 */
    /** 启动页名称：菜单当前值、弹框提示、Toast 共用一份，和弹框选项标签完全一致 */
    private static String startupName(String page) {
        if ("category".equals(page)) return "影视分类";
        if ("iptv".equals(page)) return "IPTV 直播";
        return "首页";
    }

    /** 边框大小：档位选择，遥控器 OK 即确认；与网页后台滑块共用同一份配置 */
    private void showLiveUiScaleDialog() {
        showScaleDialog("边框大小", true);
    }

    /** 字体大小：与边框分开的独立百分比 */
    private void showEpgFontScaleDialog() {
        showScaleDialog("字体大小", false);
    }

    /**
     * 百分比档位对话框（v75）。网页后台 / 手机版设置页是 5% 步长的滑块，
     * 电视上用 7 个常用档位，方向键选中、OK 即确认；写入 Store + config.json，
     * 服务端再即时推给首页 WebView，两端读数永远一致。
     */
    private void showScaleDialog(final String title, final boolean isUi) {
        final int[] vals = { 50, 75, 100, 125, 150, 175, 200 };
        int cur = isUi ? Store.liveUiScale(act) : Store.epgFontScale(act);
        // 网页后台是 5% 步长滑块，当前值可能不在这 7 档里。
        // 把当前值补成第一项并设为焦点，避免遥控器一按 OK 就把细调值误改成 50%。
        boolean exact = false;
        for (int v : vals) if (v == cur) exact = true;
        ArrayList<String> ls = new ArrayList<String>();
        ArrayList<String> vs = new ArrayList<String>();
        if (!exact) {
            ls.add("保持当前 " + cur + "%（后台细调值）");
            vs.add(String.valueOf(cur));
        }
        for (int i = 0; i < vals.length; i++) {
            ls.add(vals[i] + "%" + (vals[i] == 100 ? "（默认 · 历版原样）" : ""));
            vs.add(String.valueOf(vals[i]));
        }
        String[] labels = ls.toArray(new String[0]);
        String[] sv = vs.toArray(new String[0]);
        showChoiceDialog(title,
                isUi ? "框宽 / 行高 / 台号徽章 + 框内字体一起按百分比缩放。\n"
                       + "作用于选台抽屉、全屏选台页与首页网页直播页两栏。\n"
                       + "网页后台与设置页可 5% 步长细调。"
                     : "节目单 / 频道信息面板文字单独缩放，不受边框影响。\n"
                       + "网页后台与设置页可 5% 步长细调。",
                labels, sv, String.valueOf(cur),
                value -> {
                    int v;
                    try { v = Store.clampScale(Integer.parseInt(value)); }
                    catch (Exception ex) { return; }
                    if (isUi) TvServer.syncLiveScales(act, v, Store.epgFontScale(act));
                    else TvServer.syncLiveScales(act, Store.liveUiScale(act), v);
                    applyScale();     // 抽屉立刻按新系数重算，不等 1 秒轮询
                    L.onInteraction();
                    Toast.makeText(act, title + "已设为 " + v + "%",
                            Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 分组管理（v75）：把网页后台的「显示 / 隐藏直播分组」搬进直播功能菜单，
     * 看电视时直接按菜单键就能勾，不用掏手机开后台。
     * 数据源是全量频道缓存（未过滤），隐藏的分组也列得出来，随时能勾回来。
     */
    private void showGroupManageDialog() {
        List<Model.Channel> src = Store.liveCache(act);
        if (src == null || src.isEmpty()) src = new ArrayList<Model.Channel>(all);
        final java.util.LinkedHashMap<String, Integer> counts = Model.countLiveGroups(src);
        final java.util.LinkedHashSet<String> hidden = Store.hiddenLiveGroups(act);
        // 隐藏名单里存在、但缓存暂时没有的分组也要列出来，否则永远勾不回来
        for (String h : hidden) if (!counts.containsKey(h)) counts.put(h, 0);
        if (counts.isEmpty()) {
            Toast.makeText(act, "还没有频道分组 · 先刷新频道或添加订阅", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<String> names = new ArrayList<String>(counts.keySet());
        final ArrayList<TextView> rows = new ArrayList<TextView>();

        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        TextView tip = Ui.text(act,
                "● 已显示  ○ 已隐藏　·　方向键选中、OK 切换，即时生效", 12, 0xFFD2D2D2);
        tip.setPadding(0, 0, 0, Ui.dp(act, 8));
        box.addView(tip);

        // 批量操作：可聚焦条目，点了不关对话框，方便连续操作
        LinearLayout batch = Ui.row(act);
        final TextView allOn = Ui.text(act, "全部显示", 14, 0xFFECEFF5);
        final TextView allOff = Ui.text(act, "全部隐藏", 14, 0xFFECEFF5);
        for (TextView b : new TextView[]{ allOn, allOff }) {
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));
            b.setFocusable(true); b.setClickable(true);
            b.setBackground(focusableBg());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, Ui.dp(act, 6), Ui.dp(act, 8));
            batch.addView(b, lp);
        }
        box.addView(batch);
        Runnable repaint = new Runnable() {
            @Override public void run() {
                for (int i = 0; i < rows.size(); i++) {
                    paintGroupRow(rows.get(i), names.get(i), counts.get(names.get(i)), hidden);
                }
            }
        };
        final Runnable saveAndNotify = new Runnable() {
            @Override public void run() {
                Store.saveHiddenLiveGroups(act, hidden);
                WebAdmin.markDirty();          // 前台直播页下次 onResume 重载
                L.onHiddenGroupsChanged();     // 正在播放时按新名单立即重过滤，不打断流
            }
        };
        allOn.setOnClickListener(v -> {
            hidden.clear(); repaint.run(); saveAndNotify.run();
            Toast.makeText(act, "已全部显示", Toast.LENGTH_SHORT).show();
        });
        allOff.setOnClickListener(v -> {
            hidden.clear(); hidden.addAll(names); repaint.run(); saveAndNotify.run();
            Toast.makeText(act, "已全部隐藏", Toast.LENGTH_SHORT).show();
        });

        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.setFocusable(false);
        sv.setFocusableInTouchMode(false);
        sv.setVerticalScrollBarEnabled(true);
        LinearLayout list = Ui.column(act);
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            TextView t = new TextView(act);
            t.setTextSize(15);
            t.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
            t.setFocusable(true); t.setClickable(true);
            t.setBackground(focusableBg());
            t.setOnClickListener(v -> {
                String n = names.get(idx);
                if (hidden.contains(n)) hidden.remove(n); else hidden.add(n);
                paintGroupRow((TextView) v, n, counts.get(n), hidden);
                saveAndNotify.run();
            });
            rows.add(t);
            list.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        sv.addView(list);
        // 高度随屏幕自适应，分组多时列表内部滚动，不会把按钮挤出屏幕
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight()));

        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("分组管理 · 显示 / 隐藏")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        trackDialog(d);
        d.show();
        repaint.run();
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    private void paintGroupRow(TextView t, String name, Integer count,
                               java.util.Set<String> hidden) {
        boolean h = hidden.contains(name);
        t.setText((h ? "○ 已隐藏　" : "● 已显示　") + name
                + "（" + (count == null ? 0 : count) + " 个频道）");
        t.setTextColor(h ? 0xFF8A8F98 : 0xFFECEFF5);
    }

    /** 可聚焦条目的蓝底高亮背景，分组管理行与批量按钮共用 */
    private android.graphics.drawable.StateListDrawable focusableBg() {
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{ android.R.attr.state_focused }, rounded(SEL_BLUE, 8, 0, 0));
        bg.addState(new int[]{}, rounded(0xFF2B2B2B, 8, 0, 0));
        return bg;
    }

    private void showStartupDialog() {
        showChoiceDialog("启动设置",
                "打开软件后默认进入哪个页面？\n也可用手机在网页后台「播放设置」里修改。",
                new String[]{ "首页", "影视分类", "IPTV 直播" },
                new String[]{ "home", "category", "iptv" },
                Store.startupPage(act),
                value -> {
                    Store.setStartupPage(act, value);
                    Toast.makeText(act,
                            "已设为启动进入「" + startupName(value) + "」，下次打开软件生效",
                            Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 直播画面比例：三档（自适应排第一、默认原始比例），写入独立的 Store key，
     * 与点播画面比例分开记忆。改完通知播放页立即重排 SurfaceView，不重建内核、不打断直播。
     */
    private void showAspectDialog() {
        final String[] labels = {
                "自适应", "原始比例", "全屏显示"
        };
        final String[] values = Store.VOD_ASPECT_VALUES;
        showChoiceDialog("画面比例",
                "只影响 IPTV 直播，与点播画面比例分开记忆。\n"
                        + "自适应：片源比例接近屏幕时自动铺满，差得多则完整显示留黑边；\n"
                        + "原始比例：恒按片源比例完整显示（默认）；全屏显示：恒保持比例裁剪铺满。\n"
                        + "画面始终不变形；手机后台「直播画面比例」是同一份设置，互不影响。",
                labels, values, Store.liveAspect(act),
                value -> {
                    Store.setLiveAspect(act, value);
                    int idx = 0;
                    for (int i = 0; i < values.length; i++) {
                        if (values[i].equals(value)) idx = i;
                    }
                    Toast.makeText(act, "直播画面比例已设为「" + labels[idx] + "」",
                            Toast.LENGTH_SHORT).show();
                    L.onAspectModeChanged();
                });
    }

    /**
     * 直播解码方式：写入独立的 Store key，与网页后台的点播播放引擎互不干扰。
     * 改完通知播放页立即重建内核，当前频道接着按新档播。
     */
    private void showDecodeDialog() {
        final String[] labels = {
                "自动选择（推荐）", "内置解码", "系统解码（MediaCodec 兼容优先）"
        };
        final String[] values = { "auto", "exo", "sysdec" };
        showChoiceDialog("解码方式",
                "只影响 IPTV 直播，与点播分开记忆。\n"
                        + "自动选择：解码失败自动切系统解码重试；\n"
                        + "内置解码：不自动切换；系统解码：起步就允许软解兜底。",
                labels, values, Store.liveDecodeMode(act),
                value -> {
                    Store.setLiveDecodeMode(act, value);
                    int idx = 0;
                    for (int i = 0; i < values.length; i++) {
                        if (values[i].equals(value)) idx = i;
                    }
                    Toast.makeText(act, "直播解码已设为「" + labels[idx] + "」",
                            Toast.LENGTH_SHORT).show();
                    L.onDecodeModeChanged();
                });
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dp(act, radiusDp));
        if (strokeDp > 0) g.setStroke(Ui.dp(act, strokeDp), strokeColor);
        return g;
    }

    private GradientDrawable badge() {
        return rounded(0xFF121212, 6, 1, 0xFF7C7C7C);
    }

    private GradientDrawable pill() {
        return rounded(0xFF2B2B2B, 999, 0, 0);
    }

    private GradientDrawable blue() {
        return rounded(SEL_BLUE, 10, 2, SEL_EDGE);
    }

    private GradientDrawable groupSel() {
        return rounded(SEL_GROUP_BG, 8, 0, 0);
    }

    private GradientDrawable plain() {
        return rounded(0x00000000, 10, 0, 0);
    }

    private void showSearchDialog() {
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 12), Ui.dp(act, 20), 0);
        final EditText e = Ui.input(act, "频道名关键词");
        e.setText(query);
        e.setSelection(e.getText().length());
        box.addView(e);
        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("搜索频道（当前分组内）")
                .setView(box)
                .setPositiveButton("搜索", (dlg, w) -> {
                    query = e.getText().toString().trim();
                    rebuildChannels();
                    refreshHighlights();
                })
                .setNeutralButton("清空", (dlg, w) -> {
                    query = "";
                    rebuildChannels();
                    refreshHighlights();
                })
                .setNegativeButton("取消", null)
                .create();
        trackDialog(d);   // 输入框要能用方向键移光标，不能被播放页吞掉
        d.show();
    }

    // ---------- Adapters ----------

    private class GroupAdapter extends BaseAdapter {
        // 一级目录不显示序号，只保留分组名 + 该组频道数
        private class H { TextView name, count; }

        @Override public int getCount() { return groups.size(); }
        @Override public Object getItem(int p) { return groups.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            H h;
            LinearLayout row;
            if (cv == null) {
                row = Ui.row(act);
                row.setGravity(Gravity.CENTER_VERTICAL);
                // 行高 / 字号对齐二级目录（频道列）；一级目录不画序号徽章
                row.setMinimumHeight(rowH);
                row.setPadding(dpS(6), dpS(4), dpS(6), dpS(4));
                h = new H();
                h.name = Ui.text(act, "", sz(14), TEXT);
                h.name.setSingleLine(true);
                h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                h.name.setPadding(dpS(12), 0, dpS(4), 0);
                row.addView(h.name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.count = Ui.text(act, "", sz(10), 0xFFD2D2D2);
                h.count.setGravity(Gravity.CENTER);
                h.count.setBackground(pill());
                h.count.setPadding(dpS(5), dpS(2), dpS(5), dpS(2));
                row.addView(h.count);
                row.setTag(h);
            } else {
                row = (LinearLayout) cv;
                h = (H) row.getTag();
            }
            Group g = groups.get(pos);
            boolean sel = pos == selectedGroup;
            boolean foc = focusCol == 0;   // 分组列是否聚焦
            h.name.setText(g.name);
            h.name.setTextColor(sel ? (foc ? Color.WHITE : SEL_GROUP_TEXT) : TEXT);
            h.count.setText(String.valueOf(g.count));
            // 焦点在分组列：选中项亮蓝底；焦点在频道列：分组选中项暗底（仍可见）
            row.setBackground(sel ? (foc ? blue() : groupSel()) : plain());
            return row;
        }
    }

    private class ChannelAdapter extends BaseAdapter {
        private class H { TextView no, name, star; }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(final int pos, View cv, ViewGroup parent) {
            H h;
            LinearLayout row;
            if (cv == null) {
                row = Ui.row(act);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(rowH);
                row.setPadding(dpS(6), dpS(4), dpS(6), dpS(4));
                h = new H();
                h.no = Ui.text(act, "", sz(10), TEXT);
                h.no.setGravity(Gravity.CENTER);
                h.no.setBackground(badge());
                h.no.setLayoutParams(new LinearLayout.LayoutParams(
                        dpS(38), dpS(24)));
                row.addView(h.no);
                h.name = Ui.text(act, "", sz(14), TEXT);
                h.name.setSingleLine(true);
                h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                h.name.setPadding(dpS(9), 0, dpS(4), 0);
                row.addView(h.name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.star = Ui.text(act, "", sz(18), STAR_OFF);
                h.star.setGravity(Gravity.CENTER);
                h.star.setFocusable(false);
                h.star.setLayoutParams(new LinearLayout.LayoutParams(
                        dpS(30), dpS(30)));
                row.addView(h.star);
                row.setTag(h);
            } else {
                row = (LinearLayout) cv;
                h = (H) row.getTag();
            }

            final Model.Channel ch = shown.get(pos);
            h.no.setText(String.valueOf(shownGlobal.get(pos) + 1));
            h.name.setText(ch.name);
            boolean fav = favs.contains(ch.name);
            h.star.setText(fav ? "★" : "☆");
            h.star.setTextColor(fav ? STAR_ON : STAR_OFF);
            h.star.setOnClickListener(v -> toggleFav(ch));

            boolean sel = pos == selectedPos;
            boolean foc = focusCol == 1;   // 频道列是否聚焦
            // 焦点在频道列：选中项亮蓝底；焦点在分组列：频道选中项暗底（仍可见）
            row.setBackground(sel ? (foc ? blue() : groupSel()) : plain());
            h.name.setTextColor(sel ? (foc ? Color.WHITE : SEL_GROUP_TEXT) : TEXT);
            h.no.setTextColor(sel ? (foc ? Color.WHITE : SEL_GROUP_TEXT) : TEXT);
            return row;
        }
    }
}
