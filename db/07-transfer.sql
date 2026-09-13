-- S4.2 增量迁移：站间调拨（幂等：information_schema 检查；可重复运行）
-- 语义：调拨任务 + 逐电池明细（PENDING→OUT→IN），状态由明细聚合；站点补经纬度（就近配对用）

DROP PROCEDURE IF EXISTS swap_migrate_s42_transfer;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s42_transfer()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'station' AND COLUMN_NAME = 'latitude') THEN
        ALTER TABLE station ADD COLUMN latitude DOUBLE NULL COMMENT '纬度（调拨距离用）';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'station' AND COLUMN_NAME = 'longitude') THEN
        ALTER TABLE station ADD COLUMN longitude DOUBLE NULL COMMENT '经度（调拨距离用）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transfer_task') THEN
        CREATE TABLE transfer_task (
            id           BIGINT AUTO_INCREMENT PRIMARY KEY,
            task_no      VARCHAR(40)  NOT NULL,
            from_station BIGINT       NOT NULL,
            to_station   BIGINT       NOT NULL,
            plan_count   INT          NOT NULL,
            status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1DRAFT 2APPROVED 3EXECUTING 4DONE 5CANCELLED',
            created_by   VARCHAR(32)  NULL,
            approved_by  VARCHAR(32)  NULL,
            remark       VARCHAR(255) NULL,
            create_time  BIGINT       NOT NULL,
            update_time  BIGINT       NOT NULL,
            UNIQUE KEY uk_task_no (task_no),
            KEY idx_task_status_time (status, create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transfer_task_item') THEN
        CREATE TABLE transfer_task_item (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            task_no     VARCHAR(40) NOT NULL,
            battery_no  VARCHAR(32) NOT NULL,
            status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1PENDING 2OUT 3IN',
            out_cell_id BIGINT      NULL,
            in_cell_id  BIGINT      NULL,
            out_time    BIGINT      NULL,
            in_time     BIGINT      NULL,
            create_time BIGINT      NOT NULL,
            update_time BIGINT      NOT NULL,
            UNIQUE KEY uk_item_task_battery (task_no, battery_no),
            KEY idx_item_task (task_no, status)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s42_transfer();
DROP PROCEDURE swap_migrate_s42_transfer;
