package com.swapops.server.device.config;

/**
 * 换电域 Redis 键族（统一前缀 swap:，S0.5 §4）。
 */
public final class SwapRedisKeys {

    /** 在线 key（value=最近心跳时间戳；EXISTS 即在线，TTL=心跳超时） */
    public static final String ONLINE_PREFIX = "swap:online:";

    /** 指令 seq（INCR，柜内单调） */
    public static final String CMD_SEQ_PREFIX = "swap:cmd-seq:";

    /** 可分配集合：满电电池仓（成员=cellId） */
    public static final String ALLOC_FULL_PREFIX = "swap:alloc:full:";

    /** 可分配集合：空闲仓（RETURN 归还用；成员=cellId） */
    public static final String ALLOC_EMPTY_PREFIX = "swap:alloc:empty:";

    /** 仓预占锁（SET NX EX，与 DB lock_order_id 双保险；防弹仓后事件重入） */
    public static final String CELL_LOCK_PREFIX = "swap:cell-lock:";

    /** 订单预占快照（value=cellId，TTL=预占超时；对账/排障用） */
    public static final String PREEMPT_PREFIX = "swap:preempt:";

    /** 用户端 token（value=userId，TTL=会话时长） */
    public static final String USER_TOKEN_PREFIX = "swap:user-token:";

    /** 柜当前代际（value=bootId；与 DB last_boot_id 同语义的读优化） */
    public static final String BOOT_CURRENT_PREFIX = "swap:boot-current:";

    /** 柜已见代际集合（SET，成员=bootId，TTL 30 天；命中=旧代际重放） */
    public static final String BOOT_HISTORY_PREFIX = "swap:boot-history:";

    /** 延迟任务 ZSET（score=执行时刻，成员=taskId） */
    public static final String DELAY_Z_PREFIX = "swap:delay:z:";

    /** 延迟任务载荷 HASH（taskId → payloadJSON） */
    public static final String DELAY_PAYLOAD_PREFIX = "swap:delay:p:";

    /** 延迟任务重试计数 HASH（taskId → attempts） */
    public static final String DELAY_ATTEMPT_PREFIX = "swap:delay:a:";

    /** 延迟任务死信 HASH（taskId → payloadJSON；超限移入，S3.6 告警） */
    public static final String DELAY_DEAD_PREFIX = "swap:delay:dead:";

    /** 定时任务租约锁（SET NX PX，多实例互斥） */
    public static final String JOB_LOCK_PREFIX = "swap:job-lock:";

    private SwapRedisKeys() {
    }
}
