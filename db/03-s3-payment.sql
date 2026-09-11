-- S3.4 增量迁移：充值支付单 + 退款单（幂等：information_schema 检查后创建；可重复运行）
-- 语义：pay_order = 外部资金入口（充值）；refund_record = 退款/补偿出口，唯一键即幂等闸。

DROP PROCEDURE IF EXISTS swap_migrate_s34;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s34()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pay_order') THEN
        CREATE TABLE pay_order (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            trade_no      VARCHAR(40)  NOT NULL,
            user_id       BIGINT       NOT NULL,
            amount_fen    INT          NOT NULL,
            purpose       VARCHAR(24)  NOT NULL DEFAULT 'RECHARGE',
            status        VARCHAR(16)  NOT NULL DEFAULT 'WAIT',
            channel       VARCHAR(16)  NOT NULL DEFAULT 'MOCK',
            callback_time BIGINT,
            create_time   BIGINT       NOT NULL,
            update_time   BIGINT       NOT NULL,
            UNIQUE KEY uk_pay_trade_no (trade_no),
            KEY idx_pay_user_time (user_id, create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_record') THEN
        CREATE TABLE refund_record (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            refund_no   VARCHAR(40)  NOT NULL,
            order_id    BIGINT,
            user_id     BIGINT       NOT NULL,
            amount_fen  INT          NOT NULL,
            reason      VARCHAR(64)  NOT NULL,
            status      VARCHAR(16)  NOT NULL DEFAULT 'WAIT',
            fail_reason VARCHAR(128),
            create_time BIGINT       NOT NULL,
            update_time BIGINT       NOT NULL,
            UNIQUE KEY uk_refund_no (refund_no),
            UNIQUE KEY uk_refund_order_reason (order_id, reason),
            KEY idx_refund_user_time (user_id, create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s34();
DROP PROCEDURE swap_migrate_s34;
