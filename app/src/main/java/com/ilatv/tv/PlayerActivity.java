package com.ilatv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.view.SurfaceView;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 内置 ExoPlayer 全屏播放器（3.1.0）。
 *
 * 由 WebView 通过 exo.play(json) 调起，播放地址统一走本机 TvServer 的 /hls 代理
 * （防盗链 + Range 转发已在服务端处理）。播放器自带选集、跳过片头片尾、
 * 自动下一集、遥控器方向键操作，退出时把集数/进度回传给 MainActivity。
 *
 * 只使用 exoplayer-core + exoplayer-hls 两个纯 Java 模块，无 native 库、无
 * AndroidX UI 依赖，兼容 Android 5.0 (API 21)。
 */
public class PlayerActivity extends Activity implements Player.Listener {

    private static final int CTL_HIDE_DELAY = 3500;
    private static final long SEEK_STEP_MS = 15000;

    private ExoPlayer player;
    private SurfaceView surface;
    private FrameLayout root;
    private TextView tvTitle, tvTime, tvHint, tvLoading;
    private LinearLayout ctlBar, topBar;
    private ProgressBar progress;
    private FrameLayout epOverlay;
    private GridView epGrid;
    private EpAdapter epAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };
    private final Runnable hideCtl = new Runnable() {
        @Override
        public void run() {
            setCtlVisible(false);
        }
    };
    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            tvHint.setVisibility(View.GONE);
        }
    };

    private String[] episodes = new String[0];
    private int currentIndex;
    private String title = "";
    private String sourceName = "";
    private int skipIntro, skipOutro;
    private int port = TvServer.PORT;
    private long pendingSeekMs;
    private boolean introDone;
    private boolean outroDone;
    private boolean liveMode;   // IPTV 直播：强制 HLS 引擎，不显示选集/跳过片段/续播
    private boolean errorOccurred;
    private boolean resultSent;
    /** 播放引擎档位：auto=自动选择 / exo=只用内置 / sysdec=一上来就用系统解码 */
    private String decodeMode = "auto";
    /** 系统解码：是否已用「解码器回退」策略重建过播放器（只重试一次，避免死循环） */
    private boolean decoderRetryDone = false;
    /** 模态对话框计数：菜单 / 解码方式期间把按键交还系统，否则遥控器选不了条目 */
    private int modalDialogs = 0;
    /** 已应用到播放器的点播解码档位 / 画面比例，用于检测手机后台的改动 */
    private String appliedVodDecodeMode = "auto";
    private String appliedVodAspect = Store.DEFAULT_ASPECT;
    /** 当前画面比例档位：auto / origin（原始比例，默认） / full */
    private String aspectMode = Store.DEFAULT_ASPECT;
    private String coverUrl = "";
    /**
     * 电视端菜单与手机后台「点播播放引擎 / 点播画面比例」共用同一份配置。
     * 后台保存时本页仍在前台、不会走 onResume，所以低频比对一次 Store，
     * 发现变了就立即应用；每秒一次 sp 读取，开销可忽略。
     */
    private final Runnable checkVodDecodeMode = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            String m = Store.vodDecodeMode(PlayerActivity.this);
            String a = Store.vodAspect(PlayerActivity.this);
            boolean changed = !m.equals(appliedVodDecodeMode) || !a.equals(appliedVodAspect);
            if (changed) {
                if (modalDialogs > 0) {              // 对话框开着，等下一轮再应用
                    handler.postDelayed(this, 1000);
                    return;
                }
                if (!a.equals(appliedVodAspect)) {
                    appliedVodAspect = a;
                    applyVodAspect(a, "点播画面比例（已同步）");
                }
                if (!m.equals(appliedVodDecodeMode)) {
                    appliedVodDecodeMode = m;
                    applyVodDecodeMode(m, "点播解码（已同步）");
                }
            }
            handler.postDelayed(this, 1000);
        }
    };
    private float pendingRatio = -1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        if (!parseIntent()) {
            finish();
            return;
        }
        buildUi();
        decodeMode = Store.vodDecodeMode(this);
        appliedVodDecodeMode = decodeMode;
        aspectMode = Store.vodAspect(this);
        appliedVodAspect = aspectMode;
        // 自动选择 / 内置 ExoPlayer 都先按内置起步；只有「系统解码」档一上来就开回退
        buildPlayer("sysdec".equals(decodeMode));
        playEpisode(currentIndex, pendingSeekMs);
        handler.post(tick);
        handler.postDelayed(checkVodDecodeMode, 1000);
    }

    // ---------------------------------------------------------------- 参数

    private boolean parseIntent() {
        try {
            Intent it = getIntent();
            port = it.getIntExtra("port", TvServer.PORT);
            String cfg = it.getStringExtra("cfg");
            if (cfg == null) return false;
            JSONObject o = new JSONObject(cfg);
            JSONArray arr = o.optJSONArray("episodes");
            if (arr == null || arr.length() == 0) return false;
            episodes = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) episodes[i] = arr.optString(i, "");
            currentIndex = o.optInt("index", 0);
            if (currentIndex < 0 || currentIndex >= episodes.length) currentIndex = 0;
            title = o.optString("title", "");
            sourceName = o.optString("sourceName", "");
            coverUrl = o.optString("cover", "");
            skipIntro = Math.max(0, o.optInt("skipIntro", 0));
            skipOutro = Math.max(0, o.optInt("skipOutro", 0));
            pendingSeekMs = o.optLong("resumeMs", 0);
            liveMode = o.optBoolean("live", false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 与 JS encodeURIComponent 等价，避免 Java URLEncoder 把空格编成 + 导致代理解析出错 */
    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private String proxyUrl(String raw) {
        // SMB 网盘文件已经由内置服务的 /smb 端点代理成 HTTP 流（自带 Range/206），
        // 再套一层 /hls 只会多一次本机往返，直接播即可。
        if (raw.startsWith("http://127.0.0.1:") && raw.contains("/smb?")) return raw;
        return "http://127.0.0.1:" + port + "/hls?url=" + enc(raw);
    }

    // ---------------------------------------------------------------- 界面

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        surface = new SurfaceView(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        root.addView(surface, slp);

        // 顶部标题栏
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(0xB0000000);
        topBar.setPadding(dp(24), dp(14), dp(24), dp(14));
        tvTitle = new TextView(this);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tvTitle.setSingleLine(true);
        topBar.addView(tvTitle);
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // 底部控制条
        ctlBar = new LinearLayout(this);
        ctlBar.setOrientation(LinearLayout.VERTICAL);
        ctlBar.setBackgroundColor(0xB0000000);
        ctlBar.setPadding(dp(24), dp(12), dp(24), dp(16));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        tvTime = new TextView(this);
        tvTime.setTextColor(Color.WHITE);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        row.addView(tvTime, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 右下角按键提示：末尾加「菜单键图标 + 菜单」，与前面几项同一套灰字风格。
        // 图标用自绘三横线（菜单栏图标），不依赖电视字体是否带 ☰ 字形，避免豆腐块。
        LinearLayout tipsRow = new LinearLayout(this);
        tipsRow.setOrientation(LinearLayout.HORIZONTAL);
        tipsRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tips = new TextView(this);
        tips.setText("← → 快退/快进   OK 暂停   ↓ 选集   ");
        tips.setTextColor(0xFF9AA6B8);
        tips.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tipsRow.addView(tips);

        View menuIcon = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                p.setColor(0xFFC7D0DE);
                p.setStrokeWidth(PlayerActivity.this.dp(2));
                p.setStrokeCap(Paint.Cap.ROUND);
            }
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                float w = getWidth(), h = getHeight();
                float pad = h * 0.22f;
                float gap = (h - 2f * pad) / 2f;
                for (int i = 0; i < 3; i++) {
                    float y = pad + gap * i;
                    c.drawLine(PlayerActivity.this.dp(1), y,
                            w - PlayerActivity.this.dp(1), y, p);
                }
            }
        };
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(15), dp(12));
        iconLp.rightMargin = dp(5);
        tipsRow.addView(menuIcon, iconLp);

        TextView menuLabel = new TextView(this);
        menuLabel.setText("菜单   ↩ 退出");
        menuLabel.setTextColor(0xFF9AA6B8);
        menuLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tipsRow.addView(menuLabel);

        row.addView(tipsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ctlBar.addView(row);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        plp.topMargin = dp(8);
        ctlBar.addView(progress, plp);
        root.addView(ctlBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // 居中提示（加载 / 短消息）
        tvLoading = new TextView(this);
        tvLoading.setTextColor(0xFFE6EDF7);
        tvLoading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tvLoading.setBackgroundColor(0x99000000);
        tvLoading.setPadding(dp(24), dp(12), dp(24), dp(12));
        tvLoading.setVisibility(View.GONE);
        root.addView(tvLoading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        tvHint = new TextView(this);
        tvHint.setTextColor(Color.WHITE);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvHint.setBackgroundColor(0xCC11151C);
        tvHint.setPadding(dp(18), dp(9), dp(18), dp(9));
        tvHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        hlp.bottomMargin = dp(96);
        root.addView(tvHint, hlp);

        buildEpisodeOverlay();
        updateTitle();
        setCtlVisible(true);
    }

    private void buildEpisodeOverlay() {
        epOverlay = new FrameLayout(this);
        epOverlay.setBackgroundColor(0xEE05070B);
        epOverlay.setVisibility(View.GONE);
        epOverlay.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(36), dp(24), dp(36), dp(24));
        TextView hd = new TextView(this);
        hd.setText("选集（共 " + episodes.length + " 集）   ·   按返回键关闭");
        hd.setTextColor(Color.WHITE);
        hd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        box.addView(hd);

        epGrid = new GridView(this);
        epGrid.setNumColumns(8);
        epGrid.setVerticalSpacing(dp(10));
        epGrid.setHorizontalSpacing(dp(10));
        epGrid.setSelector(new ColorDrawable(0x66FF5A3C));
        epGrid.setDrawSelectorOnTop(true);
        epGrid.setFocusable(true);
        epAdapter = new EpAdapter();
        epGrid.setAdapter(epAdapter);
        epGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                closeEpisodes();
                playEpisode(position, 0);
            }
        });
        box.addView(epGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        epOverlay.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(epOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private class EpAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return episodes.length;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView v = new TextView(PlayerActivity.this);
            v.setText("第 " + (position + 1) + " 集");
            v.setTextColor(position == currentIndex ? 0xFFFFC4B5 : 0xFFCFD8E6);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            v.setGravity(Gravity.CENTER);
            v.setPadding(dp(4), dp(13), dp(4), dp(13));
            v.setBackgroundColor(position == currentIndex ? 0xFF5A2318 : 0xFF1A2130);
            v.setFocusable(false); // 由 GridView 统一处理焦点
            return v;
        }
    }

    private void updateTitle() {
        String s = title;
        if (s == null) s = "";
        if (!s.isEmpty()) s += "  ·  ";
        s += "第 " + (currentIndex + 1) + " 集";
        if (sourceName != null && !sourceName.isEmpty()) s += "  ·  " + sourceName;
        tvTitle.setText(s);
        if (epAdapter != null) epAdapter.notifyDataSetChanged();
    }

    private void setCtlVisible(boolean yes) {
        int v = yes ? View.VISIBLE : View.GONE;
        ctlBar.setVisibility(v);
        topBar.setVisibility(v);
        if (yes) {
            handler.removeCallbacks(hideCtl);
            handler.postDelayed(hideCtl, CTL_HIDE_DELAY);
        }
    }

    private void hint(String msg) {
        tvHint.setText(msg);
        tvHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, 1600);
    }

    private void showLoading(boolean yes, String msg) {
        tvLoading.setText(msg);
        tvLoading.setVisibility(yes ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- ExoPlayer

    /**
     * 建播放器。
     * sysdec（网页后台「系统解码」）或解码失败重试时 decoderFallback=true：
     * 显式使用系统 MediaCodec 渲染器，硬解初始化失败自动回退到设备可用的其它解码器
     * （通常是软解），而不是直接报错退出。
     */
    private void buildPlayer(boolean decoderFallback) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("LibreTV-TV/3.1.0 (Android; ExoPlayer)")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory ds = new DefaultDataSource.Factory(this, http);

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(decoderFallback);

        player = new ExoPlayer.Builder(this)
                .setRenderersFactory(renderers)
                .build();
        player.setVideoSurfaceView(surface);
        // 「全屏显示」起步就裁剪铺满；自适应先按不裁剪兜底，拿到片源尺寸后立即重判
        player.setVideoScalingMode("full".equals(aspectMode)
                ? C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                : C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        player.addListener(this);
        dataSourceFactory = ds;
    }

    private DataSource.Factory dataSourceFactory;

    private void playEpisode(int index, long resumeMs) {
        if (player == null || episodes.length == 0) return;
        if (index < 0 || index >= episodes.length) return;
        currentIndex = index;
        pendingSeekMs = resumeMs;
        introDone = false;
        outroDone = false;
        showLoading(true, "加载中…");
        updateTitle();

        String raw = episodes[index];
        String url = proxyUrl(raw);
        MediaItem item = new MediaItem.Builder().setUri(url).build();
        MediaSource ms;
        if (liveMode || raw.toLowerCase().contains(".m3u8")) {
            // 直播流虽常不带 .m3u8 后缀（如 live.php?id=xxx），实际是 HLS，必须走 HLS 引擎
            ms = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item);
        } else {
            ms = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item);
        }
        player.setMediaSource(ms);
        player.prepare();
        player.setPlayWhenReady(true);
        setCtlVisible(true);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (player == null) return;
        if (state == Player.STATE_READY) {
            showLoading(false, "");
            if (!introDone) {
                introDone = true;
                long d = player.getDuration();
                long seek = 0;
                if (pendingSeekMs > 5000) {
                    seek = pendingSeekMs;
                } else if (skipIntro > 0 && d != C.TIME_UNSET && d > (skipIntro + 15) * 1000L) {
                    seek = skipIntro * 1000L;
                }
                if (seek > 0 && d != C.TIME_UNSET && seek < d - 1000) {
                    player.seekTo(seek);
                    hint(pendingSeekMs > 5000 ? "已续播上次进度" : "已跳过片头 " + skipIntro + " 秒");
                }
                pendingSeekMs = 0;
            }
        } else if (state == Player.STATE_BUFFERING) {
            showLoading(true, "缓冲中…");
        } else if (state == Player.STATE_ENDED) {
            if (currentIndex < episodes.length - 1) {
                hint("播放结束，自动下一集");
                playEpisode(currentIndex + 1, 0);
            } else {
                showLoading(true, "全剧播放完毕，按返回键退出");
            }
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        int code = error.errorCode;
        // 4001 解码器初始化失败 / 4003 解码中失败 / 4004 超出设备能力 / 4005 格式不支持
        boolean decoderIssue = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;

        // 「自动选择」的核心：解码类错误自动切到系统解码重建播放器重试一次，
        // 从刚才的位置续播；全程不跳出应用、不丢进度。
        // 「内置 ExoPlayer」档严格按用户选择，不自动切换。
        // 「系统解码」档本身已开回退，仍允许再兜底重建一次。
        if (decoderIssue && !decoderRetryDone && !"exo".equals(decodeMode)
                && !isFinishing()) {
            decoderRetryDone = true;
            long pos = 0;
            try {
                if (player != null) pos = player.getCurrentPosition();
            } catch (Exception ignored) {
            }
            final long resume = pos;
            boolean autoSwitch = !"sysdec".equals(decodeMode);
            showLoading(true, autoSwitch
                    ? "解码失败，自动选择正在切换系统解码…"
                    : "解码失败，正在用系统解码器重试…");
            hint(autoSwitch ? "自动选择：已切换到系统解码" : "系统解码重试中");
            handler.postDelayed(new Runnable() {
                @Override public void run() { rebuildWithSystemDecoder(resume); }
            }, 400);
            return;
        }

        errorOccurred = true;
        String name = error.getErrorCodeName();
        showLoading(true, "无法播放（" + name + "）\n即将返回选集");
        hint("播放失败，请换一个片源或在后台切换播放引擎");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finishWith(true, false);
            }
        }, 1800);
    }

    /** 释放旧播放器，按「系统解码 + 解码器回退」重建，并从指定进度续播 */
    private void rebuildWithSystemDecoder(long resumeMs) {
        if (isFinishing()) return;
        try {
            if (player != null) {
                try { player.removeListener(this); } catch (Exception ignored) {}
                player.release();
            }
        } catch (Exception ignored) {
        }
        buildPlayer(true);
        errorOccurred = false;          // 重试成功中，避免退出时误报失败
        hint("sysdec".equals(decodeMode) ? "系统解码已重建" : "自动选择：已切换到系统解码");
        playEpisode(currentIndex, resumeMs);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return;
        float ratio = (float) videoSize.width / videoSize.height * videoSize.pixelWidthHeightRatio;
        if (ratio <= 0) return;
        pendingRatio = ratio;
        applyVideoRatio(ratio);
    }

    /**
     * 按当前画面比例档位摆放 SurfaceView。
     *
     * 说明：SurfaceView 不支持非等比拉伸（MediaCodec 只有「适配」和「裁剪铺满」两种缩放）。
     * 三档：自适应（片源比例接近屏幕时铺满，差得多则完整显示留黑边）、
     * 原始比例（恒按片源比例完整显示）、全屏显示（恒保持比例裁剪铺满）。画面始终不变形。
     */
    private void applyVideoRatio(float ratio) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = dm.widthPixels, H = dm.heightPixels;
        if (W <= 0 || H <= 0) return;

        float screenRatio = (float) W / H;
        boolean cropping = Ui.aspectCrop(aspectMode, ratio, screenRatio);
        float frameRatio = cropping ? screenRatio : ratio;

        int vw, vh;
        if (screenRatio > frameRatio) {
            vh = H;
            vw = Math.round(H * frameRatio);
        } else {
            vw = W;
            vh = Math.round(W / frameRatio);
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = Math.max(1, vw);
        lp.height = Math.max(1, vh);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
        if (player != null) {
            player.setVideoScalingMode(cropping
                    ? C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    : C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        }
    }

    private static String aspectName(String mode) {
        if ("origin".equals(mode)) return "原始比例";
        if ("full".equals(mode)) return "全屏显示";
        return "自适应";
    }

    /** 切换画面比例：立即重排 SurfaceView，不重建播放器、不丢进度 */
    private void applyVodAspect(String mode, String notePrefix) {
        Store.setVodAspect(this, mode);
        aspectMode = mode;
        if (pendingRatio > 0) {
            applyVideoRatio(pendingRatio);
        } else if (player != null) {
            player.setVideoScalingMode("full".equals(mode)
                    ? C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    : C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        }
        hint(notePrefix == null ? "画面比例：" + aspectName(mode)
                : notePrefix + "：" + aspectName(mode));
    }

    // ---------------------------------------------------------------- 进度 / 片尾

    private void updateProgress() {
        if (player == null) return;
        long d = player.getDuration();
        long p = player.getCurrentPosition();
        if (d > 0 && d != C.TIME_UNSET && p >= 0) {
            progress.setProgress((int) (p * 1000L / d));
            tvTime.setText(fmt(p) + " / " + fmt(d));

            long outroMs = Math.min((long) skipOutro * 1000L, d / 2);
            if (outroMs > 0 && !outroDone && d - p <= outroMs && d - p > 300) {
                outroDone = true;
                if (currentIndex < episodes.length - 1) {
                    hint("已跳过片尾，继续下一集");
                    playEpisode(currentIndex + 1, 0);
                }
            }
        } else {
            tvTime.setText(fmt(Math.max(0, p)) + " / --:--");
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    // ---------------------------------------------------------------- 选集浮层

    private boolean episodesVisible() {
        return epOverlay != null && epOverlay.getVisibility() == View.VISIBLE;
    }

    private void openEpisodes() {
        epOverlay.setVisibility(View.VISIBLE);
        epGrid.setSelection(currentIndex);
        epAdapter.notifyDataSetChanged();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (episodesVisible()) epGrid.requestFocus();
            }
        }, 60);
    }

    private void closeEpisodes() {
        epOverlay.setVisibility(View.GONE);
        setCtlVisible(true);
    }

    // ---------------------------------------------------------------- 菜单 / 画面比例 / 解码方式

    /** 菜单键唤出播放菜单：画面比例、解码方式；按返回或「关闭」退出 */
    private void openMenu() {
        setCtlVisible(true);
        final String[] items = { "画面比例", "解码方式" };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("播放菜单")
                .setItems(items, (dlg, which) -> {
                    if (which == 0) showAspectDialog();
                    else if (which == 1) showDecodeDialog();
                })
                .setNegativeButton("关闭", null)
                .create();
        trackDialog(d);
        d.show();
    }

    /** 登记模态对话框：期间把方向键 / OK / 返回交还系统，让遥控器能操作列表 */
    private void trackDialog(AlertDialog d) {
        modalDialogs++;
        d.setOnDismissListener(dlg -> {
            if (modalDialogs > 0) modalDialogs--;
            setCtlVisible(true);
        });
    }

    private interface ChoicePick {
        void pick(String value);
    }

    /**
     * 通用自绘选择对话框（画面比例 / 解码方式共用）。
     * 不用 setSingleChoiceItems —— 部分电视 ROM 在同时设置 setMessage 时会吞掉单选列表，
     * 只剩一段提示和「关闭」；这里自绘可聚焦条目：● 标当前值、蓝底高亮，OK 即确认。
     */
    private void showChoiceDialog(String title, String tipText,
                                  final String[] labels, final String[] values,
                                  String current, final ChoicePick onPick) {
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(4));
        TextView tip = new TextView(this);
        tip.setText(tipText);
        tip.setTextColor(0xFFD2D2D2);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tip.setPadding(0, 0, 0, dp(10));
        box.addView(tip);

        View first = null, focus = null;
        for (int i = 0; i < labels.length; i++) {
            View item = choiceItem(labels[i], values[i].equals(current), values[i], ref, onPick);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            box.addView(item, lp);
            if (first == null) first = item;
            if (values[i].equals(current)) focus = item;
        }

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        ref[0] = d;
        trackDialog(d);
        d.show();
        // 焦点直接落在当前生效项，OK 即确认，↑↓ 可切到别的档
        (focus != null ? focus : first).requestFocus();
    }

    /** 一条可聚焦的设置项：● 表示当前生效，方向键移焦点时蓝底高亮 */
    private View choiceItem(String label, boolean selected, final String value,
                            final AlertDialog[] ref, final ChoicePick onPick) {
        TextView t = new TextView(this);
        t.setText((selected ? "●  " : "○  ") + label);
        t.setTextSize(16);
        t.setTextColor(0xFFECEFF5);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, choiceBg(0xFF2F7CF6));
        bg.addState(new int[]{}, choiceBg(0xFF2B2B2B));
        t.setBackground(bg);
        t.setOnClickListener(v -> {
            onPick.pick(value);
            if (ref[0] != null) ref[0].dismiss();
        });
        return t;
    }

    private GradientDrawable choiceBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(8));
        return g;
    }

    /** 画面比例：三档（自适应排第一），与手机后台「点播画面比例」共用同一份 Store 配置 */
    private void showAspectDialog() {
        final String[] labels = {
                "自适应", "原始比例", "全屏显示"
        };
        showChoiceDialog("画面比例",
                "只影响影视点播，与 IPTV 直播分开记忆。\n"
                        + "自适应：片源比例接近屏幕时自动铺满，差得多则完整显示留黑边；\n"
                        + "原始比例：恒按片源比例完整显示（默认）；全屏显示：恒保持比例裁剪铺满。\n"
                        + "画面始终不变形；手机后台「点播画面比例」是同一份设置，两边实时同步。",
                labels, Store.VOD_ASPECT_VALUES, Store.vodAspect(this),
                value -> {
                    appliedVodAspect = value;   // 菜单已即时应用，轮询别再重复提示
                    applyVodAspect(value, null);
                });
    }

    /** 点播解码方式：与手机后台「点播播放引擎」共用同一份 Store 配置 */
    private void showDecodeDialog() {
        final String[] labels = {
                "自动选择（推荐）", "内置 ExoPlayer", "系统解码（MediaCodec 兼容优先）"
        };
        final String[] values = { "auto", "exo", "sysdec" };
        showChoiceDialog("解码方式",
                "只影响影视点播，与 IPTV 直播解码分开记忆。\n"
                        + "手机后台「点播播放引擎」是同一份设置，两边实时同步。",
                labels, values, Store.vodDecodeMode(this),
                value -> {
                    appliedVodDecodeMode = value;   // 菜单已即时应用，轮询别再重复重建
                    applyVodDecodeMode(value, null);
                });
    }

    /** 切换点播解码档位：保存配置、重建播放器、从当前进度续播（不退出、不丢进度） */
    private void applyVodDecodeMode(String mode, String notePrefix) {
        Store.setVodDecodeMode(this, mode);
        decodeMode = mode;
        decoderRetryDone = false;
        long pos = 0;
        try {
            if (player != null) pos = player.getCurrentPosition();
        } catch (Exception ignored) {
        }
        try {
            if (player != null) {
                try { player.removeListener(this); } catch (Exception ignored) {}
                player.release();
            }
        } catch (Exception ignored) {
        }
        player = null;
        buildPlayer("sysdec".equals(mode));
        errorOccurred = false;
        String name = "sysdec".equals(mode) ? "系统解码"
                : ("exo".equals(mode) ? "内置 ExoPlayer" : "自动选择");
        hint(notePrefix == null ? "解码方式：" + name : notePrefix + "：" + name);
        playEpisode(currentIndex, pos);
    }

    // ---------------------------------------------------------------- 按键

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 菜单 / 解码方式对话框期间按键交还系统：否则方向键、OK、返回都被本页吞掉，
        // 遥控器根本选不了条目，也关不掉对话框。
        if (modalDialogs > 0) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (episodesVisible()) {
                    closeEpisodes();
                } else {
                    finishWith(false, false);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!episodesVisible()) {
                    openEpisodes();
                    return true;
                }
                return super.onKeyDown(keyCode, event); // 浮层内交给 GridView

            case KeyEvent.KEYCODE_DPAD_UP:
                if (episodesVisible()) return super.onKeyDown(keyCode, event);
                setCtlVisible(true);
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (episodesVisible()) return super.onKeyDown(keyCode, event);
                seekBy(-SEEK_STEP_MS);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (episodesVisible()) return super.onKeyDown(keyCode, event);
                seekBy(SEEK_STEP_MS);
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (episodesVisible()) return super.onKeyDown(keyCode, event);
                togglePlay();
                return true;

            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (currentIndex < episodes.length - 1) playEpisode(currentIndex + 1, 0);
                else hint("已经是最后一集");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (currentIndex > 0) playEpisode(currentIndex - 1, 0);
                else hint("已经是第一集");
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                openMenu();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void seekBy(long delta) {
        if (player == null) return;
        long d = player.getDuration();
        long p = Math.max(0, player.getCurrentPosition() + delta);
        if (d != C.TIME_UNSET && d > 0) p = Math.min(p, d - 500);
        player.seekTo(p);
        hint(delta > 0 ? "+15 秒" : "-15 秒");
        setCtlVisible(true);
    }

    private void togglePlay() {
        if (player == null) return;
        boolean now = !player.getPlayWhenReady();
        player.setPlayWhenReady(now);
        hint(now ? "播放" : "暂停");
        setCtlVisible(true);
    }

    // ---------------------------------------------------------------- 生命周期 / 回传

    private void finishWith(boolean failed, boolean ended) {
        if (resultSent) return;
        resultSent = true;
        long pos = 0;
        try {
            if (player != null) pos = player.getCurrentPosition();
        } catch (Exception ignored) {
        }
        Intent data = new Intent();
        data.putExtra("index", currentIndex);
        data.putExtra("positionMs", pos);
        data.putExtra("failed", failed || errorOccurred);
        data.putExtra("ended", ended);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingRatio > 0) applyVideoRatio(pendingRatio);
        if (player != null && !errorOccurred) player.setPlayWhenReady(true);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.setPlayWhenReady(false);
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!resultSent && player != null) {
            // 被系统回收时也要回传，避免前端一直等结果
            Intent data = new Intent();
            data.putExtra("index", currentIndex);
            data.putExtra("positionMs", 0L);
            data.putExtra("failed", errorOccurred);
            data.putExtra("ended", false);
            setResult(RESULT_OK, data);
            resultSent = true;
        }
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
