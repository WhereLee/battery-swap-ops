-- =====================================================================
-- db/15-resilience-patch.sql（S7 韧性补丁）：欠费来源标注（幂等，可重复执行）
--   背景：计费硬失败欠费化（G1）后，欠费单可能来自 基础费/押金/超时费 多种来源，
--   增加 reason 标注（"BALANCE_FEE" / "DEPOSIT" / "OVERDUE_FEE"，多个以 + 连接）。
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_s7_resilience;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s7_resilience()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'arrears_record'
                     AND COLUMN_NAME = 'reason') THEN
        ALTER TABLE arrears_record
            ADD COLUMN reason VARCHAR(64) NULL COMMENT '欠费来源：BALANCE_FEE/DEPOSIT/OVERDUE_FEE（+ 连接）';
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s7_resilience();
DROP PROCEDURE swap_migrate_s7_resilience;

SELECT 'db/15 resilience-patch done' AS done;
