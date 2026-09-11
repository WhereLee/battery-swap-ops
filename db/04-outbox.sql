-- S3.8 WP6 增量迁移：本地消息表 outbox_event（幂等：information_schema 检查；可重复运行）
-- 语义：业务事务内落库（事实源）→ 中继任务补投 MQ（Redis/网络故障不丢事件）

DROP PROCEDURE IF EXISTS swap_migrate_s38_outbox;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s38_outbox()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'outbox_event') THEN
        CREATE TABLE outbox_event (
            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
            event_key       VARCHAR(96)  NOT NULL COMMENT '幂等键（业务唯一）',
            event_type      VARCHAR(32)  NOT NULL COMMENT '事件类型（路由发布器）',
            payload         TEXT         NOT NULL COMMENT '事件体 JSON',
            status          VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/SENT/DEAD',
            attempts        INT          NOT NULL DEFAULT 0,
            next_retry_time BIGINT       NOT NULL COMMENT '下次尝试时间(ms)',
            trace_id        VARCHAR(64),
            last_error      VARCHAR(255),
            create_time     BIGINT       NOT NULL,
            update_time     BIGINT       NOT NULL,
            sent_time       BIGINT,
            UNIQUE KEY uk_outbox_event_key (event_key),
            KEY idx_outbox_status_retry (status, next_retry_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s38_outbox();
DROP PROCEDURE swap_migrate_s38_outbox;
