-- S2 增量迁移（幂等：information_schema 检查后执行；可重复运行）
-- 内容：订单幂等键/预占过期/关闭原因、套餐购买幂等键与更新时间、支付备注

DROP PROCEDURE IF EXISTS swap_migrate_s2;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s2()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order' AND COLUMN_NAME = 'idem_key') THEN
        ALTER TABLE swap_order ADD COLUMN idem_key VARCHAR(64) NULL COMMENT '下单幂等键';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order' AND INDEX_NAME = 'uk_idem_key') THEN
        ALTER TABLE swap_order ADD UNIQUE KEY uk_idem_key (idem_key);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order' AND COLUMN_NAME = 'preempt_expire_time') THEN
        ALTER TABLE swap_order ADD COLUMN preempt_expire_time BIGINT NULL COMMENT '预占过期时间(ms)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order' AND COLUMN_NAME = 'cancel_time') THEN
        ALTER TABLE swap_order ADD COLUMN cancel_time BIGINT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order' AND COLUMN_NAME = 'close_reason') THEN
        ALTER TABLE swap_order ADD COLUMN close_reason VARCHAR(64) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_plan' AND COLUMN_NAME = 'idem_key') THEN
        ALTER TABLE user_plan ADD COLUMN idem_key VARCHAR(64) NULL COMMENT '购买幂等键';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_plan' AND INDEX_NAME = 'uk_user_plan_idem') THEN
        ALTER TABLE user_plan ADD UNIQUE KEY uk_user_plan_idem (idem_key);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_plan' AND COLUMN_NAME = 'update_time') THEN
        ALTER TABLE user_plan ADD COLUMN update_time BIGINT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_record' AND COLUMN_NAME = 'remark') THEN
        ALTER TABLE payment_record ADD COLUMN remark VARCHAR(128) NULL;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s2();
DROP PROCEDURE swap_migrate_s2;
