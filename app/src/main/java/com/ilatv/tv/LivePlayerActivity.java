package com.ilatv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播播放页（IPTV 交互）：
 *
 * - 平时全屏播放，界面完全隐藏；单击画面 / OK / 菜单键唤出左侧「选台抽屉」
 * - 抽屉 6 秒无操作自动收起；点右侧视频区域、返回键、「✕ 收起」也能收起
 * - 换台后顶部短暂 OSD 显示「台号 · 频道名 · 线路 x/y」，3 秒淡出
 * - 遥控器：↑↓ 换台、←→ 切换线路（抽屉内切一级/二级目录，频道列再按右键
 *   收藏/取消收藏）、OK 确认或唤出、数字键直选台号、返回先收面板、
 *   媒体键播放暂停 / 上下台
 * - 选台抽屉里可收藏、切线路；顶部无工具条、右缘也不再放「更多」按钮，
 *   其余功能由长按画面 / 长按抽屉空白处 / 遥控器菜单键唤出功能菜单
 * - 频道列表缓存优先：先用上次缓存立即起播，再后台下载，失败沿用缓存
 */
public class LivePlayerActivity extends Activity
        implements PlayerCore.Callback, LivePicker.Listener {

    private static final long PANEL_HIDE_MS = 6000L;
    private static final long OSD_HIDE_MS = 3000L;
    private static final long DIGIT_COMMIT_MS = 1600L;

    private FrameLayout root;
    private SurfaceView surface;
    private PlayerCore core;
    private LivePicker picker;
    private LinearLayout osd;
    private TextView osdText;
    private TextView loadingText;

    private List<Model.Channel> channels = new ArrayList<>();
    private int index = -1;
    private int lineIndex = 0;
    private int errorTries = 0;
    private boolean released = false;
    private boolean needReload = false;
    /** 本次 ACTION_DOWN 是否已被 Activity 接管，用于吞掉配对的 ACTION_UP */
    private boolean keyHandledByActivity = false;

    private final StringBuilder digits = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hidePanel = new Runnable() {
        @Override public void run() { if (picker != null) picker.hide(); }
    };
    /** 淡出结束后真正隐藏；单独一个 Runnable，方便再次弹提示时取消，避免把新提示误关掉 */
    private final Runnable hideOsdNow = new Runnable() {
        @Override public void run() { if (osd != null) osd.setVisibility(View.GONE); }
    };
    private final Runnable hideOsd = new Runnable() {
        @Override public void run() {
            if (osd == null) return;
            osd.animate().cancel();
            osd.animate().alpha(0f).setDuration(300).start();
            handler.removeCallbacks(hideOsdNow);
            handler.postDelayed(hideOsdNow, 320);
        }
    };
    private final Runnable commitDigit = new Runnable() {
        @Override public void run() { commitNumber(); }
    };
    /** 已应用的两项设置，用于检测手机后台 / 电视菜单里的改动 */
    private String appliedLiveDecodeMode = "auto";
    private String appliedLiveAspect = Store.DEFAULT_ASPECT;
    /** 换台反转的已应用值，仅用于后台改动时给一次 OSD 提示；按键本身直接读 Store */
    private boolean appliedChannelReverse = false;
    /** 边框 / 字体大小的已应用百分比（v74）：后台在手机上改了这里即时重算抽屉 */
    private int appliedLiveUiScale = Store.SCALE_DEF;
    private int appliedEpgFontScale = Store.SCALE_DEF;
    /** 当前直播画面比例档位：auto / origin（原始比例，默认） / full */
    private String liveAspect = Store.DEFAULT_ASPECT;
    /**
     * 网页后台与电视端功能菜单共用同一份「直播解码 / 直播画面比例」配置。
     * 后台在手机上保存时本页仍在前台、不会走 onResume，所以低频比对一次 Store，
     * 发现变了就立即应用；每秒一次 sp 读取，开销可忽略。
     */
    private final Runnable checkLiveSettings = new Runnable() {
        @Override public void run() {
            if (released) return;
            String m = Store.liveDecodeMode(LivePlayerActivity.this);
            String a = Store.liveAspect(LivePlayerActivity.this);
            boolean rev = Store.channelReverse(LivePlayerActivity.this);
            // 边框大小 / 字体大小：后台保存后写进本机 Store，
            // 本页仍在前台不走 onResume，靠这里 1 秒一次的比对即时重算抽屉。
            // 按用户要求：改完不再弹任何双端同步提示，静默生效即可。
            int ui = Store.liveUiScale(LivePlayerActivity.this);
            int ef = Store.epgFontScale(LivePlayerActivity.this);
            if (ui != appliedLiveUiScale || ef != appliedEpgFontScale) {
                appliedLiveUiScale = ui;
                appliedEpgFontScale = ef;
                if (picker != null) picker.applyScale();
            }
            if (rev != appliedChannelReverse) {
                appliedChannelReverse = rev;
                showOsd(currentChannel(), rev ? "换台反转（已同步）：↑ 下一个台"
                        : "换台反转（已同步）：已恢复正常");
            }
            if (!a.equals(appliedLiveAspect)) {
                appliedLiveAspect = a;
                applyLiveAspect(a, "直播画面比例（已同步）");
            }
            if (!m.equals(appliedLiveDecodeMode)) {
                appliedLiveDecodeMode = m;
                if (core != null) core.setDecodeMode(m);
                String name = "sysdec".equals(m) ? "系统解码"
                        : ("exo".equals(m) ? "内置解码" : "自动选择");
                showOsd(currentChannel(), "直播解码（已同步）：" + name);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. 视频层
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // 直播解码 / 画面比例都单独记忆（网页后台下拉 / IPTV 功能菜单共用），与点播互不干扰
        appliedLiveDecodeMode = Store.liveDecodeMode(this);
        core = new PlayerCore(this, surface, this, appliedLiveDecodeMode);
        liveAspect = Store.liveAspect(this);
        appliedLiveAspect = liveAspect;
        appliedChannelReverse = Store.channelReverse(this);
        core.setAspectMode(liveAspect);

        // 2. 透明触摸层：单击 = 唤出/收起选台抽屉
        View touch = new View(this);
        touch.setBackgroundColor(Color.TRANSPARENT);
        touch.setClickable(true);
        touch.setOnClickListener(v -> {
            resetPanelTimer();
            picker.toggle();
            if (!picker.isShowing()) showOsd(currentChannel(), "已收起选台");
        });
        touch.setLongClickable(true);
        touch.setOnLongClickListener(v -> {
            resetPanelTimer();
            picker.openToolsMenu();
            return true;
        });
        root.addView(touch, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. 加载提示
        loadingText = Ui.text(this, "正在加载频道…", 16, Ui.SUB);
        // 默认隐藏：推送起播走 applyChannels() 而不走 loadChannels()，否则这句会一直挂在画面上
        loadingText.setVisibility(View.GONE);
        loadingText.setBackgroundColor(0xAA000000);
        loadingText.setPadding(Ui.dp(this, 18), Ui.dp(this, 10),
                Ui.dp(this, 18), Ui.dp(this, 10));
        FrameLayout.LayoutParams loadLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadLp.gravity = Gravity.CENTER;
        root.addView(loadingText, loadLp);

        // 4. OSD：底部居中的胶囊提示（短暂显示，平时隐藏）
        // 放在画面下方中间，避开上方台标 / 水印；40dp 底边距兼顾电视 overscan 安全区。
        osd = Ui.row(this);
        osd.setGravity(Gravity.CENTER);
        GradientDrawable og = new GradientDrawable();
        og.setColor(0xE6121620);                       // 比原来更实，亮画面上也读得清
        og.setCornerRadius(Ui.dp(this, 999));          // 胶囊圆角
        og.setStroke(Ui.dp(this, 1), 0x665A6678);      // 淡描边，不抢画面
        osd.setBackground(og);
        osd.setPadding(Ui.dp(this, 24), Ui.dp(this, 11),
                Ui.dp(this, 24), Ui.dp(this, 11));
        osdText = Ui.text(this, "", 18, 0xFFF5F8FF);   // 14 → 18sp，电视远距离更清楚
        osdText.setGravity(Gravity.CENTER);
        osdText.setSingleLine(true);
        osdText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        osd.addView(osdText);
        osd.setVisibility(View.GONE);
        FrameLayout.LayoutParams osdLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        osdLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        osdLp.bottomMargin = Ui.dp(this, 40);
        osdLp.leftMargin = Ui.dp(this, 24);            // 长提示也不贴边
        osdLp.rightMargin = Ui.dp(this, 24);
        root.addView(osd, osdLp);

        // 5. 选台抽屉（平时 GONE，浮在最上）
        picker = new LivePicker(this, this);
        picker.attach(root);
        appliedLiveUiScale = Store.liveUiScale(this);
        appliedEpgFontScale = Store.epgFontScale(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        // 后台改直播解码 / 画面比例时本页仍在前台，靠这个低频轮询即时同步；
        // 必须放在下面所有早退分支之前，否则推送起播那条路径会漏掉。
        handler.postDelayed(checkLiveSettings, 1000);

        // 入口优先级：完整频道列表 > 管理后台推送 > 自己加载
        ArrayList<Model.Channel> got = (ArrayList<Model.Channel>)
                getIntent().getSerializableExtra("channels");
        final String pushName = getIntent().getStringExtra("pushName");
        ArrayList<String> pushUrls = getIntent().getStringArrayListExtra("pushUrls");

        if (got != null && !got.isEmpty()) {
            applyChannels(got, getIntent().getIntExtra("index", 0));
        } else if (pushName != null && !pushName.isEmpty()) {
            startPushedChannel(pushName, pushUrls);
        } else {
            loadChannels(true);
        }
    }

    /** 手机后台推送直播频道：先用该频道立即起播，再后台拉全量列表补齐上下台 */
    private void startPushedChannel(String name, ArrayList<String> urls) {
        if (name == null || name.isEmpty()) return;
        Model.Channel ch = new Model.Channel(name, "推送频道", "");
        if (urls != null) ch.urls.addAll(urls);
        if (ch.urls.isEmpty()) { loadChannels(true); return; }
        loadingText.setVisibility(View.GONE);
        applyChannels(java.util.Collections.singletonList(ch), 0);
        final String keep = name;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 与直播页同一套缓存策略：拉到新的就更新缓存，全失败就并入上次缓存
                // 推送合并走的是 fetch（不经过 LiveLoader.load），这里同样按隐藏名单过滤，
                // 但强制保留当前正在播的推送频道，避免它被隐藏分组踢出列表后无法换台。
                List<Model.Channel> rawFull = LiveLoader.fetch(
                        LivePlayerActivity.this,
                        Store.liveCache(LivePlayerActivity.this)).channels;
                // 拷一份独立列表：hidden 为空时 filterHiddenGroups 会原样返回 in，
                // 这里不能让下面的 keep 补回逻辑写进 fetch 的返回值。
                final List<Model.Channel> full = new ArrayList<>(
                        Store.filterHiddenChannels(LivePlayerActivity.this, rawFull));
                boolean keepFound = false;
                for (Model.Channel ch : full) if (ch.name.equals(keep)) { keepFound = true; break; }
                if (!keepFound) {
                    for (Model.Channel ch : rawFull) {
                        if (ch.name.equals(keep)) { full.add(ch); break; }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (released) return;
                        loadingText.setVisibility(View.GONE);
                        mergeChannels(full, keep);
                    }
                });
            }
        }, "libretv-push-merge").start();
    }

    /** 播放中再次收到推送（原生直达，不依赖后台被冻结的 WebView 定时器） */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String name = intent == null ? null : intent.getStringExtra("pushName");
        if (name != null && !name.isEmpty()) {
            ArrayList<String> urls = intent.getStringArrayListExtra("pushUrls");
            startPushedChannel(name, urls);
            showOsdText("已切换推送频道：" + name);
        }
    }

    /** 只替换频道列表并定位到当前台，不打断正在播放的流 */
    private void mergeChannels(List<Model.Channel> got, String keepName) {
        if (got == null || got.isEmpty()) return;
        channels = new ArrayList<>(got);
        int i = indexOf(channels, keepName);
        if (i >= 0) index = i;
        picker.setData(channels);
        picker.setCurrent(keepName);
    }

    // ---------- 数据 ----------

    /**
     * 缓存优先加载：有缓存就同步起播、后台再下载；下载失败沿用缓存，
     * 下载成功则平滑替换列表（不打断正在播放的流）。
     */
    private void loadChannels(final boolean autoPlay) {
        final String lastName = Store.lastLive(this);
        final boolean firstLoad = channels.isEmpty();
        if (firstLoad) loadingText.setVisibility(View.VISIBLE);

        LiveLoader.load(this, new LiveLoader.Callback() {
            @Override
            public void onCached(List<Model.Channel> cached, long ts) {
                if (!firstLoad || released) return;
                loadingText.setVisibility(View.GONE);
                applyChannels(cached, autoPlay ? indexOf(cached, lastName) : 0);
                showOsdText("已载入上次缓存 · " + cached.size() + " 个频道（"
                        + LiveLoader.ageText(ts) + "）· 正在更新…");
            }

            @Override
            public void onResult(LiveLoader.Result r) {
                if (released || isFinishing()) return;
                loadingText.setVisibility(View.GONE);
                if (r.channels.isEmpty()) {
                    showOsdText(r.noSubs
                            ? "还没有频道 · 长按屏幕 → 订阅管理 / 加频道"
                            : "订阅下载失败且无缓存 · 长按屏幕 → 订阅管理 / 刷新");
                    if (picker != null) picker.show();
                    return;
                }
                if (channels.isEmpty()) {
                    applyChannels(r.channels, autoPlay ? indexOf(r.channels, lastName) : 0);
                } else {
                    mergeChannels(r.channels,
                            currentChannel() == null ? "" : currentChannel().name);
                }
                if (r.usedCache) {
                    showOsdText("订阅下载失败，沿用上次缓存 · " + r.channels.size() + " 个频道"
                            + (r.cacheTime > 0 ? "（" + LiveLoader.ageText(r.cacheTime) + "）" : ""));
                } else {
                    showOsdText("频道已更新 · " + r.channels.size() + " 个"
                            + (r.failSubs > 0 ? " · " + r.failSubs + " 份订阅失败" : ""));
                }
            }
        });
    }

    private static int indexOf(List<Model.Channel> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    private void applyChannels(List<Model.Channel> got, int start) {
        channels = new ArrayList<>(got);
        picker.setData(channels);
        if (channels.isEmpty()) {
            showOsdText("还没有频道 · 点屏幕 →「订阅管理」或「加频道」");
            picker.show();
            return;
        }
        if (start < 0 || start >= channels.size()) start = 0;
        errorTries = 0;
        playAt(start, 0, "");
    }

    // ---------- 播放 ----------

    private Model.Channel currentChannel() {
        return index >= 0 && index < channels.size() ? channels.get(index) : null;
    }

    private void playAt(int pos, int line, String note) {
        if (pos < 0 || pos >= channels.size()) return;
        index = pos;
        Model.Channel ch = channels.get(pos);
        if (ch.urls.isEmpty()) {
            Toast.makeText(this, "该频道没有可用地址", Toast.LENGTH_SHORT).show();
            return;
        }
        lineIndex = line < 0 || line >= ch.urls.size() ? 0 : line;
        // 注意：这里不重置 errorTries —— 自动切线路时要保留失败计数，
        // 否则每次 playAt 归零会导致失败后无限循环切线路。
        core.play(ch.urls.get(lineIndex), 0L);
        Store.saveLastLive(this, ch.name);
        picker.setCurrent(ch.name);
        showOsd(ch, note);
    }

    /**
     * 换台方向：按「换台反转」设置把 ±1 整体对调。
     * 直接读 Store（遥控器按键频率极低，一次 SharedPreferences 读取开销可忽略），
     * 因此电视菜单 / 手机后台任一边改完都立即生效，不需要额外回调或轮询。
     * 作用范围仅限「全屏播放、选台界面未显示」时的 ↑↓ / 频道± / 媒体前后键换台。
     * 选台抽屉 / 全屏选台页里的 ↑↓ 高亮移动、←→ 切列与切线路、
     * 数字直选、OK 确认一律按原始方向，不受本设置影响。
     */
    private int channelStep(int raw) {
        return Store.channelReverse(this) ? -raw : raw;
    }

    private void switchChannel(int delta) {
        if (channels.isEmpty()) return;
        errorTries = 0;
        int target = (index + delta + channels.size()) % channels.size();
        playAt(target, 0, "");
    }

    /** 切换当前频道的播放线路：delta=-1 上一条，+1 下一条，到头循环 */
    private void switchLine(int delta) {
        Model.Channel ch = currentChannel();
        if (ch == null) return;
        if (ch.urls.size() <= 1) {
            showOsd(ch, "该频道只有一条线路");
            return;
        }
        errorTries = 0;
        int n = ch.urls.size();
        int next = (lineIndex + delta + n) % n;
        playAt(index, next, "切换线路");
    }

    // ---------- OSD / 自动隐藏 ----------

    private void showOsd(Model.Channel ch, String note) {
        if (ch == null) return;
        String s = (index + 1) + "  " + ch.name
                + "   ·   线路 " + (lineIndex + 1) + "/" + ch.urls.size()
                + (note.isEmpty() ? "" : "   ·   " + note);
        showOsdText(s);
    }

    private void showOsdText(String s) {
        osdText.setText(s);
        handler.removeCallbacks(hideOsd);
        handler.removeCallbacks(hideOsdNow);
        osd.animate().cancel();
        osd.setAlpha(0f);
        osd.setVisibility(View.VISIBLE);
        osd.animate().alpha(1f).setDuration(200).start();
        handler.postDelayed(hideOsd, OSD_HIDE_MS);
    }

    private void resetPanelTimer() {
        handler.removeCallbacks(hidePanel);
        if (picker != null && picker.isShowing()) {
            handler.postDelayed(hidePanel, PANEL_HIDE_MS);
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------- 数字键直选 ----------

    private void onDigit(int d) {
        digits.append(d);
        handler.removeCallbacks(commitDigit);
        showOsdText("台号  " + digits + "_");
        handler.postDelayed(commitDigit, DIGIT_COMMIT_MS);
        resetPanelTimer();
    }

    private void commitNumber() {
        if (digits.length() == 0) return;
        String s = digits.toString();
        digits.setLength(0);
        handler.removeCallbacks(commitDigit);
        int no;
        try {
            no = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return;
        }
        if (picker.pickByNumber(no)) {
            picker.hide();
        } else {
            showOsdText("没有第 " + no + " 个频道（共 " + channels.size() + " 个）");
        }
    }

    // ---------- 按键 ----------

    /**
     * 选台抽屉显示时，方向键与确定键在 Activity 层统一接管，不下发给 View 树。
     * 否则可聚焦控件会自己处理按键：ListView 移动焦点却不更新蓝色高亮（首尾
     * 卡住），右缘「节目单」按钮被焦点命中后确定键会弹出「频道信息」对话框，
     * 导致按确定无法播放。
     * 抽屉内右键：一级目录 → 移到频道列；已在频道列 → 切换当前高亮频道收藏。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        // 功能菜单 / 启动设置 / 搜索 / 频道信息等模态对话框弹出时，按键全部交还系统。
        // 否则方向键只会移动抽屉里的高亮，遥控器根本选不了菜单项、也关不掉对话框。
        if (picker != null && picker.hasModalDialog()) {
            keyHandledByActivity = false;
            return super.dispatchKeyEvent(event);
        }
        // 只要本次按下被接管，抬键一律吞掉：此时抽屉可能已被 confirm() 收起，
        // 若放给 View 树会再次触发点击。
        if (action == KeyEvent.ACTION_UP && keyHandledByActivity) {
            keyHandledByActivity = false;
            return true;
        }
        boolean nav = code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_CHANNEL_UP
                || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || code == KeyEvent.KEYCODE_MEDIA_NEXT;
        boolean confirm = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (picker != null && picker.isShowing() && (nav || confirm)
                && action == KeyEvent.ACTION_DOWN) {
            keyHandledByActivity = true;
            resetPanelTimer();
            if (confirm) {
                handleConfirmKey();
            } else if (code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_CHANNEL_UP
                    || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                // 选台界面（抽屉 / 选台页）方向键永远按原始方向移动高亮，不受换台反转影响
                picker.moveFocus(-1);
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                    || code == KeyEvent.KEYCODE_MEDIA_NEXT) {
                picker.moveFocus(1);
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                picker.moveColumn(-1);
            } else {   // KEYCODE_DPAD_RIGHT：频道列切换收藏 / 分组列移到频道列
                picker.onRight();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 确定键：数字选台优先，其次播放抽屉里的高亮频道，否则唤出抽屉 */
    private void handleConfirmKey() {
        if (digits.length() > 0) {
            commitNumber();
        } else if (picker.isShowing()) {
            picker.confirm();
            picker.hide();
        } else {
            picker.show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        resetPanelTimer();
        // 模态对话框期间不响应播放页快捷键：否则返回键会把抽屉收掉却关不掉对话框，
        // 菜单键会叠着再弹一个功能菜单，数字键也会跳出「台号」提示。
        if (picker != null && picker.hasModalDialog()) {
            return super.onKeyDown(keyCode, event);
        }

        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            onDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (picker.isShowing()) {
                    picker.hide();
                    return true;
                }
                finish();
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                // 抽屉内高亮永远按原始方向；只有全屏（抽屉收起）换台才应用反转
                if (picker.isShowing()) picker.moveFocus(-1);
                else switchChannel(channelStep(-1));
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (picker.isShowing()) picker.moveFocus(1);
                else switchChannel(channelStep(1));
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (picker.isShowing()) picker.moveColumn(-1);  // 抽屉内：焦点移到一级目录
                else switchLine(-1);                             // 平时：上一条线路
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (picker.isShowing()) picker.onRight();        // 抽屉内：频道列切收藏 / 分组列移到频道列
                else switchLine(1);                              // 平时：下一条线路
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                handleConfirmKey();
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_SETTINGS:
                picker.openToolsMenu();   // 菜单键 / 设置键：直接弹出功能菜单
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                core.togglePlay();
                showOsd(currentChannel(), core.isPlaying() ? "播放" : "暂停");
                return true;

            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---------- PlayerCore.Callback ----------

    @Override
    public void onReady() {
        errorTries = 0;
    }

    @Override
    public void onError(String message) {
        Model.Channel ch = currentChannel();
        if (ch == null) return;
        if (ch.urls.size() > 1 && errorTries < ch.urls.size()) {
            errorTries++;
            playAt(index, (lineIndex + 1) % ch.urls.size(), "自动切线路（" + message + "）");
        } else {
            showOsdText(message + " · 点屏幕选台或切线路");
        }
    }

    @Override
    public void onVideoSize(int width, int height, float pixelRatio) {
        core.fitSurface(root, width, height, pixelRatio);
    }

    @Override
    public void onEnded() {
        showOsd(currentChannel(), "直播流结束，切线路或换台");
    }

    // ---------- LivePicker.Listener ----------

    @Override
    public void onPick(Model.Channel ch, int globalIndex) {
        errorTries = 0;
        playAt(globalIndex, 0, "");
        picker.hide();
        handler.removeCallbacks(hidePanel);
    }

    @Override
    public void onDismiss() {
        handler.removeCallbacks(hidePanel);
    }

    @Override
    public void onInteraction() {
        resetPanelTimer();
    }

    @Override
    public void onEpg(Model.Channel ch) {
        StringBuilder sb = new StringBuilder();
        sb.append("分组：").append(ch.group).append('\n');
        sb.append("线路：").append(ch.urls.size()).append(" 条\n");
        sb.append("收藏：").append(Store.isLiveFav(this, ch.name) ? "是" : "否").append("\n\n");
        for (int i = 0; i < ch.urls.size(); i++) {
            sb.append(i + 1).append(". ").append(ch.urls.get(i)).append('\n');
        }
        sb.append("\n本版本未接入 EPG / XMLTV 节目单，这里展示当前频道的线路信息。");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(ch.name + " · 频道信息")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .create();
        picker.trackDialog(d);   // 确定键要能关掉这个对话框
        d.show();
        // 节目单 / 频道信息文字按「字体大小」百分比独立缩放
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) msg.setTextSize(14 * Store.epgFontScale(this) / 100f);
    }

    @Override
    public void onTogglePlay() {
        core.togglePlay();
        showOsd(currentChannel(), core.isPlaying() ? "播放" : "暂停");
    }

    @Override
    public void onOpenManage(String which) {
        if ("subs".equals(which)) {
            // 订阅管理直接在播放页弹框：不再启动全屏选台页，
            // 背后只留直播画面，且播放不会被压到后台暂停。
            // 每个弹出的对话框都登记给抽屉，模态期间把按键交还系统，
            // 否则遥控器操作不了列表、输入框和按钮。
            picker.hide();
            LiveSubsDialog.show(this, new Runnable() {
                @Override public void run() { loadChannels(false); }
            }, new LiveSubsDialog.Tracker() {
                @Override public void track(AlertDialog d) { picker.trackDialog(d); }
            });
            return;
        }
        needReload = true;
        picker.hide();
        Intent i = new Intent(this, LiveActivity.class);
        if (!"list".equals(which)) i.putExtra("open", which);
        startActivity(i);
    }

    /**
     * 直播功能菜单「分组管理」改了显示 / 隐藏（v75）：
     * 从全量缓存按新名单重过滤，再交给 mergeChannels 替换列表，
     * 不重新联网、不重建播放内核、不打断正在播的流。
     * 当前正在看的台若被隐藏，仍强制保留，避免出现「播着但列表里找不到」。
     */
    @Override
    public void onHiddenGroupsChanged() {
        List<Model.Channel> raw = Store.liveCache(this);
        if (raw == null || raw.isEmpty()) {
            showOsd(currentChannel(), "分组设置已保存 · 下次刷新频道后生效");
            return;
        }
        List<Model.Channel> full = new ArrayList<Model.Channel>(
                Store.filterHiddenChannels(this, raw));
        String keep = currentChannel() == null ? "" : currentChannel().name;
        boolean found = false;
        for (Model.Channel ch : full) if (ch.name.equals(keep)) { found = true; break; }
        if (!found && !keep.isEmpty()) {
            for (Model.Channel ch : raw) {
                if (ch.name.equals(keep)) { full.add(ch); break; }
            }
        }
        mergeChannels(full, keep);
        showOsd(currentChannel(), "分组显示已更新 · 隐藏 "
                + Store.hiddenLiveGroups(this).size() + " 个分组");
    }

    @Override
    public void onReload() {
        loadChannels(false);
    }

    /**
     * 菜单「影视点播」：回到首页点播。
     * CLEAR_TOP 会把直播页、全屏选台页都清掉，复用栈底那个 MainActivity；
     * 带 skipAutoIptv 是为了万一 MainActivity 需要重建（例如被系统回收过）时，
     * 不会因「启动页 = IPTV 直播」又被自动拽回直播页。
     */
    @Override
    public void onBackToHome() {
        picker.hide();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("skipAutoIptv", true);
        startActivity(i);
        finish();
    }

    /** 菜单里改了直播解码档位：立即重建播放内核，当前频道接着按新档播 */
    @Override
    public void onDecodeModeChanged() {
        String m = Store.liveDecodeMode(this);
        appliedLiveDecodeMode = m;   // 菜单已即时应用，轮询别再重复弹一次提示
        core.setDecodeMode(m);
        String name = "sysdec".equals(m) ? "系统解码"
                : ("exo".equals(m) ? "内置解码" : "自动选择");
        showOsd(currentChannel(), "直播解码：" + name);
    }

    /** 自动选择档在解码失败时切到系统解码，给个短暂提示，不打断播放 */
    @Override
    public void onDecodeNotice(String message) {
        showOsdText(message);
    }

    // ---------- 直播画面比例 ----------

    /** 菜单「画面比例」改档：立即重排 SurfaceView，不重建内核、不打断直播 */
    @Override
    public void onAspectModeChanged() {
        String a = Store.liveAspect(this);
        appliedLiveAspect = a;   // 菜单已即时应用，轮询别再重复弹一次提示
        applyLiveAspect(a, null);
    }

    /** 菜单「换台反转」改完：刷新缓存并给一次 OSD 提示。按键方向由 Store 实时决定 */
    @Override
    public void onChannelReverseChanged() {
        appliedChannelReverse = Store.channelReverse(this);
        showOsd(currentChannel(), appliedChannelReverse
                ? "换台已反转：↑ 下一个台 / ↓ 上一个台"
                : "换台已恢复：↑ 上一个台 / ↓ 下一个台");
    }

    private static String liveAspectName(String mode) {
        if ("auto".equals(mode)) return "自适应";
        if ("full".equals(mode)) return "全屏显示";
        return "原始比例";
    }

    private void applyLiveAspect(String mode, String notePrefix) {
        Store.setLiveAspect(this, mode);
        liveAspect = mode;
        core.setAspectMode(mode);
        showOsdText(notePrefix == null ? "画面比例：" + liveAspectName(mode)
                : notePrefix + "：" + liveAspectName(mode));
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (core != null) core.resume();
        if (needReload || WebAdmin.isDirty()) {
            needReload = false;
            WebAdmin.clearDirty();
            loadChannels(false);
        }
    }

    @Override
    protected void onPause() {
        if (core != null) core.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        released = true;
        handler.removeCallbacks(hidePanel);
        handler.removeCallbacks(hideOsd);
        handler.removeCallbacks(hideOsdNow);
        handler.removeCallbacks(commitDigit);
        handler.removeCallbacks(checkLiveSettings);
        if (osd != null) osd.animate().cancel();
        if (core != null) {
            core.release();
            core = null;
        }
        super.onDestroy();
    }
}
