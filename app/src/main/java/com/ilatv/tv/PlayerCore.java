package com.ilatv.tv;

import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

/**
 * 内置 ExoPlayer（Media3）播放内核。
 *
 * 相比系统 VideoView：自己解封装 HLS / TS / fMP4 / MKV，走系统 MediaCodec 硬解，
 * 支持 AES-128 切片、多音轨、低延迟直播。
 *
 * 解码档位由「直播功能菜单 → 解码方式」单独控制（与点播后台配置分开记忆）：
 * auto   = 先内置解码，解码类错误自动切系统解码重建重试一次
 * exo    = 只用内置解码，不自动切换
 * sysdec = 起步就开启 MediaCodec 解码器回退（硬解失败转软解）
 * 策略：地址一律先按 HLS 试，容器解析失败再回退 Progressive（MP4 等直链）。
 */
public final class PlayerCore {

    public interface Callback {
        void onReady();
        void onError(String message);
        void onVideoSize(int width, int height, float pixelRatio);
        void onEnded();
        /** 自动选择档切到系统解码时给界面一个提示（不中断播放） */
        void onDecodeNotice(String message);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36 LibreTV-Java/1.0";

    private final Context ctx;
    private final Callback cb;
    private final DataSource.Factory dataSourceFactory;
    private ExoPlayer player;
    private SurfaceView surface;

    // Media3 / ExoPlayer 共用同一套 errorCode 规范；这里补齐 readableError 之外
    // 需要识别的解码类码值，避免不同版本常量名差异导致编译问题。
    private static final int CODE_DECODER_QUERY_FAILED = 4002;
    private static final int CODE_FORMAT_EXCEEDS_CAPABILITIES = 4004;
    private static final int CODE_FORMAT_UNSUPPORTED = 4005;

    private String currentUrl = "";
    private long currentStart = 0L;
    private boolean triedProgressive = false;
    private boolean released = false;
    private boolean ready = false;
    private float speed = 1.0f;

    /** 直播解码档位：auto / exo / sysdec */
    private String decodeMode = "auto";
    /** 是否已自动重建过播放器（每次换台/换线路重置，避免反复重建） */
    private boolean decoderRetryDone = false;
    private Player.Listener listener;

    /** 直播画面比例档位：auto / origin（原始比例，默认） / full */
    private String aspectMode = Store.DEFAULT_ASPECT;
    /** 最近一次视频尺寸，切换比例时用来立即重排 SurfaceView */
    private int lastVideoW = 0, lastVideoH = 0;
    private float lastPixelRatio = 1f;
    private FrameLayout lastParent = null;

    public PlayerCore(Context ctx, SurfaceView surface, Callback cb, String mode) {
        this.ctx = ctx;
        this.surface = surface;
        this.cb = cb;
        this.decodeMode = normalize(mode);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(12000)
                .setAllowCrossProtocolRedirects(true);
        this.dataSourceFactory = new DefaultDataSource.Factory(ctx, http);

        listener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    ready = true;
                    cb.onReady();
                } else if (state == Player.STATE_ENDED) {
                    cb.onEnded();
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                cb.onVideoSize(videoSize.width, videoSize.height,
                        videoSize.pixelWidthHeightRatio);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handleError(error);
            }
        };
        buildPlayer("sysdec".equals(decodeMode));
    }

    /**
     * 建 / 重建播放器。decoderFallback=true 时启用 MediaCodec 解码器回退：
     * 首选硬解初始化失败会自动尝试设备上其它解码器（通常是软解）。
     */
    private void buildPlayer(boolean decoderFallback) {
        if (released) return;
        if (player != null) {
            try { player.removeListener(listener); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
        }
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(ctx)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(decoderFallback);
        player = new ExoPlayer.Builder(ctx)
                .setRenderersFactory(renderers)
                .build();
        player.setVideoSurfaceView(surface);
        player.setVideoScalingMode(croppingAspect()
                ? C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                : C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        player.addListener(listener);
        ready = false;
        // 解码切换会重建播放器，重建后立刻按当前比例重排一次，避免等下一个视频尺寸回调
        if (lastVideoW > 0 && lastVideoH > 0 && lastParent != null) {
            fitSurface(lastParent, lastVideoW, lastVideoH, lastPixelRatio);
        }
    }

    private boolean croppingAspect() {
        // 还没拿到片源尺寸时无法判断「自适应」，先按不裁剪兜底；
        // onVideoSize 一到就会用 Ui.aspectCrop 重新判定并立即重排。
        return "full".equals(aspectMode);
    }

    /** 解码类错误：自动选择档切到系统解码，重建播放器并重播当前线路 */
    private void handleError(PlaybackException error) {
        if (released) return;
        int code = error.errorCode;
        boolean decoderIssue = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == CODE_DECODER_QUERY_FAILED
                || code == CODE_FORMAT_EXCEEDS_CAPABILITIES
                || code == CODE_FORMAT_UNSUPPORTED;

        if (decoderIssue) {
            // exo 档严格不切换；auto / sysdec 允许自动重建一次
            if (!decoderRetryDone && !"exo".equals(decodeMode)) {
                decoderRetryDone = true;
                boolean autoSwitch = !"sysdec".equals(decodeMode);
                cb.onDecodeNotice(autoSwitch
                        ? "解码失败，自动选择已切换系统解码"
                        : "解码失败，正在用系统解码器重试");
                buildPlayer(true);
                triedProgressive = false;
                applySource(true, 0L);     // 直播从当前线路头重播
                return;
            }
            cb.onError(readableError(error));
            return;
        }

        // 非解码错误：保留原有行为，HLS 解析失败多半是 MP4 直链，回退 Progressive 再试
        if (!triedProgressive) {
            triedProgressive = true;
            applySource(false, currentStart);
            return;
        }
        cb.onError(readableError(error));
    }

    /** 菜单切换档位时立即生效：重建播放器并按新档重播当前线路 */
    public void setDecodeMode(String mode) {
        String m = normalize(mode);
        if (m.equals(decodeMode) || released) return;
        decodeMode = m;
        decoderRetryDone = false;
        buildPlayer("sysdec".equals(m));
        if (!currentUrl.isEmpty()) {
            triedProgressive = false;
            applySource(true, 0L);
        }
    }

    public String decodeMode() {
        return decodeMode;
    }

    private static String normalize(String mode) {
        return "sysdec".equals(mode) || "exo".equals(mode) ? mode : "auto";
    }

    private static String readableError(PlaybackException e) {
        int code = e.errorCode;
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return "网络连接失败或超时，换线路 / 换源试试";
        }
        if (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return "不支持的媒体格式，换线路 / 换源试试";
        }
        if (code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            return "解码失败（设备不支持该编码），换线路试试";
        }
        return "播放失败（" + code + "）" + (msg.isEmpty() ? "" : "：" + msg);
    }

    /** 播放新地址；startMs 为续播位置，直播传 0 */
    public void play(String url, long startMs) {
        if (released) return;
        currentUrl = url == null ? "" : url;
        if (currentUrl.isEmpty()) {
            cb.onError("地址为空");
            return;
        }
        currentStart = Math.max(0L, startMs);
        triedProgressive = false;
        decoderRetryDone = false;   // 换台 / 换线路后重新允许一次自动切换
        ready = false;
        applySource(true, currentStart);          // 先按 HLS 解封装
    }

    private void applySource(boolean hls, long startMs) {
        if (released) return;
        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.parse(currentUrl))
                .build();
        MediaSource source;
        if (hls) {
            source = new HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        } else {
            source = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(item);
        }
        player.setMediaSource(source, startMs);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    // ---------- 播放控制 ----------

    public boolean isReady() {
        return ready && !released;
    }

    public boolean isPlaying() {
        return !released && player != null && player.isPlaying();
    }

    public long position() {
        return released || player == null ? 0L : player.getCurrentPosition();
    }

    public long duration() {
        if (released || player == null) return 0L;
        long d = player.getDuration();
        return d < 0 || d == androidx.media3.common.C.TIME_UNSET ? 0L : d;
    }

    public void seekTo(long ms) {
        if (!released && player != null) player.seekTo(Math.max(0L, ms));
    }

    public void setPlayWhenReady(boolean play) {
        if (!released && player != null) player.setPlayWhenReady(play);
    }

    public void togglePlay() {
        if (!released && player != null) player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    /** 倍速 0.5~3.0，切换媒体后仍保持 */
    public void setSpeed(float s) {
        speed = Math.max(0.25f, Math.min(s, 3.0f));
        if (!released && player != null) {
            player.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    public float speed() {
        return speed;
    }

    /** 如实列出媒体内嵌轨道数量；本版本不提供外挂字幕 / 弹幕 */
    public String tracksInfo() {
        if (released || player == null) return "播放内核未就绪";
        try {
            Tracks tracks = player.getCurrentTracks();
            int v = 0, a = 0, t = 0;
            for (Tracks.Group g : tracks.getGroups()) {
                int type = g.getType();
                if (type == C.TRACK_TYPE_VIDEO) v++;
                else if (type == C.TRACK_TYPE_AUDIO) a++;
                else if (type == C.TRACK_TYPE_TEXT) t++;
            }
            return "视频 " + v + " 轨 · 音频 " + a + " 轨 · 内嵌字幕 " + t + " 轨";
        } catch (Throwable e) {
            return "无法读取轨道信息";
        }
    }

    public void pause() {
        setPlayWhenReady(false);
    }

    public void resume() {
        setPlayWhenReady(true);
    }

    public void release() {
        released = true;
        ready = false;
        if (player != null) {
            try { player.removeListener(listener); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        surface = null;
    }

    // ---------- 画面比例 ----------

    /**
     * 按当前画面比例档位摆放 SurfaceView。
     *
     * SurfaceView 走 MediaCodec，只支持等比适配 / 等比裁剪铺满，不能非等比拉伸。
     * 三档：自适应（片源比例接近屏幕时铺满，差得多则完整显示留黑边）、
     * 原始比例（恒按片源比例完整显示）、全屏显示（恒保持比例裁剪铺满）。
     */
    public void fitSurface(FrameLayout parent, int videoW, int videoH, float pixelRatio) {
        if (parent == null || surface == null || videoW <= 0 || videoH <= 0) return;
        lastParent = parent;
        lastVideoW = videoW;
        lastVideoH = videoH;
        lastPixelRatio = pixelRatio;

        int cw = parent.getWidth();
        int ch = parent.getHeight();
        if (cw <= 0 || ch <= 0) {
            android.util.DisplayMetrics dm = parent.getResources().getDisplayMetrics();
            cw = Math.max(dm.widthPixels, dm.heightPixels);
            ch = Math.min(dm.widthPixels, dm.heightPixels);
        }
        float srcRatio = (float) videoW / videoH;
        if (pixelRatio > 0.01f && pixelRatio < 10f) srcRatio *= pixelRatio;
        if (srcRatio <= 0.01f) return;

        float screenRatio = (float) cw / ch;
        boolean cropping = Ui.aspectCrop(aspectMode, srcRatio, screenRatio);
        float frameRatio = cropping ? screenRatio : srcRatio;

        int vw, vh;
        if (screenRatio > frameRatio) {
            vh = ch;
            vw = Math.round(ch * frameRatio);
        } else {
            vw = cw;
            vh = Math.round(cw / frameRatio);
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER);
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

    /** 菜单 / 网页后台切换直播画面比例：立即重排，不重建播放器、不打断直播 */
    public void setAspectMode(String mode) {
        String m = "auto";
        for (String v : Store.VOD_ASPECT_VALUES) if (v.equals(mode)) { m = v; break; }
        if (m.equals(aspectMode)) return;
        aspectMode = m;
        if (lastVideoW > 0 && lastVideoH > 0 && lastParent != null) {
            fitSurface(lastParent, lastVideoW, lastVideoH, lastPixelRatio);
        } else if (player != null) {
            player.setVideoScalingMode(croppingAspect()
                    ? C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    : C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        }
    }

    public String aspectMode() {
        return aspectMode;
    }
}
