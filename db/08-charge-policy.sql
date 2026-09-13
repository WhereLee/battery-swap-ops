-- S4.3 增量迁移：充电策略（幂等：information_schema 检查；可重复运行）
-- 语义：版本化策略历史（每次下发一行）；ACTIVE 表示柜侧已确认应用

DROP PROCEDURE IF EXISTS swap_migrate_s43_charge;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s43_charge()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'charge_policy') THEN
        CREATE TABLE charge_policy (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            cabinet_no  VARCHAR(32)  NOT NULL,
            version     INT          NOT NULL,
            policy_json TEXT         NOT NULL COMMENT '窗口数组(JSON)：startHour/endHour/powerLimitW/feeFenPerKwh',
            priority    INT          NOT NULL DEFAULT 2 COMMENT '1 高 / 2 中 / 3 低',
            status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 ACTIVE / 2 FAILED',
            command_seq BIGINT       NULL,
            remark      VARCHAR(255) NULL,
            create_time BIGINT       NOT NULL,
            update_time BIGINT       NOT NULL,
            UNIQUE KEY uk_policy_cabinet_version (cabinet_no, version),
            KEY idx_policy_cabinet (cabinet_no, id)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s43_charge();
DROP PROCEDURE swap_migrate_s43_charge;
