package com.swapops.server.common.cache;

/**
 * 缓存键目录（一 key 一常量，避免手拼字符串漂移；命名空间即失效粒度）。
 */
public final class CacheKeys {

    /** 生效套餐目录（全量，低频变更） */
    public static final String PLAN_ACTIVE_LIST = "plan:active:list";

    /** 运营中站点元数据列表（不含实时可换/可还计数） */
    public static final String STATION_ACTIVE_LIST = "station:active:list";

    private CacheKeys() {
    }

    public static String l2Key(String key) {
        return "swap:cache:data:" + key;
    }

    public static String rebuildLockKey(String key) {
        return "swap:cache:lock:" + key;
    }
}
