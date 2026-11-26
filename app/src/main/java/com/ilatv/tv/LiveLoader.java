package com.ilatv.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播频道列表的统一加载器（v1.13.7）。
 *
 * 行为：
 *  1) 进入直播 / 选台页时，先把**上次成功拉取并缓存的列表**同步交给界面，
 *     立刻能选台、能起播，不出现空白或转圈等网络；
 *  2) 同时后台并发拉取全部启用订阅（M3U / TXT）+ 合并本机频道；
 *  3) 下载成功 → 用新列表替换并更新缓存（界面平滑刷新，不打断当前播放）；
 *  4) 下载失败（全部订阅超时 / 返回空 / 解析不出频道）→ **沿用上次缓存**，
 *     不清空、不报错刷白，只提示「沿用上次缓存」；
 *  5) 一份订阅都没启用时，合法结果就是「只有本机频道」，此时照常替换缓存；
 *     连本机频道都没有则写空缓存，避免删光订阅后还残留旧台。
 *
 * 缓存独立于订阅配置存本机 SharedPreferences，按片名/台名去重后的完整合并列表，
 * 含每条线路地址、分组、台标与 manual 标记。
 */
public final class LiveLoader {

    private LiveLoader() {}

    public static class Result {
        public List<Model.Channel> channels = new ArrayList<>();
        public boolean fromNetwork;      // 本次下载成功并替换了缓存
        public boolean usedCache;        // 下载失败，结果就是上次缓存
        public boolean noSubs;           // 当前没有启用任何订阅
        public boolean subsChanged;      // 订阅配置与缓存生成时不同
        public int okSubs;
        public int failSubs;
        public long cacheTime;
    }

    public interface Callback {
        /** 主线程同步回调：有缓存就先给界面用 */
        void onCached(List<Model.Channel> cached, long ts);
        /** 主线程回调：后台下载结束后的最终结果 */
        void onResult(Result r);
    }

    /**
     * 缓存优先的异步加载。请在主线程调用；onCached 同步执行，onResult 回主线程。
     */
    public static void load(final Context ctx, final Callback cb) {
        // 缓存里始终保存全量频道（隐藏名单只影响「界面可见性」），
        // 因此过滤必须放在交付给 UI 的最后一刻，取消隐藏即可立刻恢复全部频道。
        final List<Model.Channel> allCached = Store.liveCache(ctx);
        final List<Model.Channel> cached = Store.filterHiddenChannels(ctx, allCached);
        final long ts = Store.liveCacheTime(ctx);
        if (cb != null && !cached.isEmpty()) cb.onCached(cached, ts);

        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                // fetch 内部写缓存用的是「未过滤」的全量结果，这里只替换回调里
                // 交付给界面的 r.channels，不影响缓存内容。
                final Result r = fetch(ctx, allCached);
                r.channels = Store.filterHiddenChannels(ctx, r.channels);
                if (cb == null) return;
                main.post(new Runnable() {
                    @Override public void run() { cb.onResult(r); }
                });
            }
        }, "libretv-livecache").start();
    }

    /**
     * 纯决策逻辑（不依赖 Android / 网络，可单独跑 JVM 单测）：
     * 给定上次缓存与本次下载结果，决定界面最终用哪份列表。
     *
     * 成功条件 = 没有启用订阅（结果就是本机频道，合法）或至少一份订阅拉到了频道。
     * 特别注意：订阅全失败但本机频道非空时，不能把「只有 1 个本机频道」当成成功，
     * 否则会把 335 个台的缓存砍成 1 个 —— 这正是「下载失败沿用缓存」要防的情况。
     */
    public static Result decide(List<Model.Channel> cached, List<Model.Channel> fresh,
                                int okSubs, int failSubs, boolean noSubs) {
        Result r = new Result();
        r.okSubs = okSubs;
        r.failSubs = failSubs;
        r.noSubs = noSubs;
        boolean success = noSubs || okSubs > 0;
        if (success) {
            r.channels = fresh == null ? new ArrayList<>() : fresh;
            r.fromNetwork = true;
        } else if (cached != null && !cached.isEmpty()) {
            r.channels = cached;                   // 下载失败 → 沿用上次缓存
            r.usedCache = true;
        } else {
            // 从未成功下载过、且这次也失败：至少把本机频道给用户
            r.channels = fresh == null ? new ArrayList<>() : fresh;
        }
        return r;
    }

    /**
     * 同步执行一次「下载 → 成功替换 / 失败沿用缓存」。必须在子线程调用。
     * 供网页后台、推送后的全量合并等非 UI 场景复用。
     */
    public static Result fetch(Context ctx, List<Model.Channel> cached) {
        if (cached == null) cached = new ArrayList<>();

        final List<Model.LiveSub> subs = Store.enabledLiveSubs(ctx);
        final List<Model.Channel> manual = Store.manualChannels(ctx);
        final boolean noSubs = subs.isEmpty();
        final String sig = Store.liveSubsSignature(ctx);

        Api.LiveLoadResult d = Api.loadAllLiveStatus(subs, manual);
        Result r = decide(cached, d.channels, d.okSubs, d.failSubs, noSubs);
        r.subsChanged = !sig.equals(Store.liveCacheSubs(ctx));

        if (r.fromNetwork) {
            Store.saveLiveCache(ctx, r.channels);   // 空列表也会写，等于清缓存
            Store.saveLiveCacheSubs(ctx, sig);
            r.cacheTime = System.currentTimeMillis();
        } else {
            r.cacheTime = Store.liveCacheTime(ctx);
        }
        return r;
    }

    /** 供 UI 显示缓存时间的短文本 */
    public static String ageText(long ts) {
        if (ts <= 0) return "无缓存";
        long min = (System.currentTimeMillis() - ts) / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60;
        if (h < 24) return h + " 小时前";
        return (h / 24) + " 天前";
    }
}
