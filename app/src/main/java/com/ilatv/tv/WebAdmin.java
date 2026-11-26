package com.ilatv.tv;

/**
 * 精简桩：直播模块只需要这两个静态方法作为「后台配置已变更」的脏标记。
 * 当前工程用 TvServer(m.html) 管理配置，不走精简工程那套 WebAdmin 服务，
 * 因此这里只保留标记位，避免引入整个后台体系。
 */
public final class WebAdmin {
    private static volatile boolean dirty = false;

    private WebAdmin() {}

    public static boolean isDirty() {
        return dirty;
    }

    public static void clearDirty() {
        dirty = false;
    }

    /** 供配置页在保存后调用，通知直播播放页刷新频道列表 */
    public static void markDirty() {
        dirty = true;
    }
}
