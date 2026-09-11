package com.swapops.server.device.config;

/**
 * 换电域 Redis 键族（统一前缀 swap:，S0.5 §4）。
 */
public final class SwapRedisKeys {

    /** 在线 key（value=最近心跳时间戳；EXISTS 即在线，TTL=心跳超时） */
    public static final String ONLINE_PREFIX = "swap:online:";

    /** 指令 seq（INCR，柜内单调） */
    public static final String CMD_SEQ_PREFIX = "swap:cmd-seq:";

    private SwapRedisKeys() {
    }
}
