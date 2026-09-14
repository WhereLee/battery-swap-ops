package com.swapops.server.alarm;

/**
 * 告警类型（S3.6）：与 alarm.alarm_type、Agent 信封 alarmType 同值。
 */
public enum AlarmType {
    /** 单柜离线（心跳 TTL 过期） */
    OFFLINE,
    /** 批量离线（>= 阈值的柜同时离线，合并一条） */
    BATCH_OFFLINE,
    /** 柜级故障（设备事件） */
    CABINET_FAULT,
    /** 仓位故障（设备事件） */
    CELL_FAULT,
    /** 指令重试超限转人工（S3.2 对账收敛） */
    RETRY_EXCEEDED,
    /** 订单归还超期（S3.3 迁移） */
    ORDER_OVERDUE,
    /** 超时费未足额收取（用户欠费，S5 审查补） */
    ORDER_ARREARS,
    /** 日终对账差异（S3.5） */
    RECONCILE_ERROR,
    /** 定时任务停摆（看护） */
    JOB_STALLED,
    /** 延迟任务超限死信（S3.3） */
    DELAY_DEAD,
    /** outbox 投递失败超限/无发布器（S3.8 WP6） */
    OUTBOX_DEAD,
    /** 工单 SLA 超时（S4.4） */
    WORK_ORDER_SLA_BREACH,
    /** 电池健康度低于阈值（S4.1） */
    BATTERY_HEALTH_LOW
}
