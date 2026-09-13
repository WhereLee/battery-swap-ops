-- S4.1 增量迁移：电池健康（swaps 计数 + 循环流水）（幂等：information_schema 检查；可重复运行）
-- 口径：swaps=取出次数（BATTERY_OUT）；cycle_count=归仓次数（BATTERY_IN，服务循环口径）
-- 幂等：battery_cycle_log 唯一键 (boot_id, event_seq)——重复事件不双计

DROP PROCEDURE IF EXISTS swap_migrate_s41_battery;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s41_battery()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'battery' AND COLUMN_NAME = 'swaps') THEN
        ALTER TABLE battery ADD COLUMN swaps INT NOT NULL DEFAULT 0 COMMENT '取出次数（换电服务次数）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'battery_cycle_log') THEN
        CREATE TABLE battery_cycle_log (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            battery_no  VARCHAR(32) NOT NULL,
            action      VARCHAR(8)  NOT NULL COMMENT 'OUT 取出 / IN 归仓',
            soc         INT         NULL COMMENT '事件时电量',
            cabinet_no  VARCHAR(32) NULL,
            command_seq BIGINT      NULL,
            boot_id     VARCHAR(64) NOT NULL,
            event_seq   BIGINT      NOT NULL,
            create_time BIGINT      NOT NULL,
            UNIQUE KEY uk_cycle_event (boot_id, event_seq),
            KEY idx_cycle_battery (battery_no, id)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s41_battery();
DROP PROCEDURE swap_migrate_s41_battery;
