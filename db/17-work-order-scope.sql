-- =====================================================================
-- db/17-work-order-scope.sql（S8 批次29）：工单站点归属列（幂等，可重复执行）
--   背景：work_order 仅有 device_no（可能是柜号 / 柜号-仓号 / 电池号 / woNo / SITE-x），
--         数据权限按站点隔离需要可过滤列；沿用 swap_order.station_id 的既有做法（冗余归属列，
--         避免每次查询跨三表解析）。
--   回填：① 柜号维度（SUBSTRING_INDEX 取柜号段）② 电池维度（battery→cell→cabinet）。
--         解不出归属的行保持 NULL（系统级/跨站级工单），受限身份按 fail-closed 看不到它们。
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_s8_wo_scope;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s8_wo_scope()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order'
                     AND COLUMN_NAME = 'station_id') THEN
        ALTER TABLE work_order
            ADD COLUMN station_id BIGINT NULL COMMENT '归属站点（创建时按设备解析；NULL=无站点归属）'
                AFTER device_no,
            ADD KEY idx_station_create (station_id, create_time);
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s8_wo_scope();
DROP PROCEDURE swap_migrate_s8_wo_scope;

-- 回填①：设备号形如 SWAP-C-005 或 SWAP-C-005-3（仓级）→ 取前三段即柜号
UPDATE work_order w
    JOIN cabinet c ON c.cabinet_no = SUBSTRING_INDEX(w.device_no, '-', 3)
SET w.station_id = c.station_id
WHERE w.station_id IS NULL AND w.device_no IS NOT NULL;

-- 回填②：设备号是电池号（BATTERY_HEALTH_LOW 等）→ 经 电池→仓→柜 解析
UPDATE work_order w
    JOIN battery b   ON b.battery_no = w.device_no
    JOIN cell    ce  ON ce.id = b.cell_id
    JOIN cabinet c   ON c.id = ce.cabinet_id
SET w.station_id = c.station_id
WHERE w.station_id IS NULL AND w.device_no IS NOT NULL;

SELECT 'db/17 work-order scope done' AS done,
       (SELECT COUNT(*) FROM work_order WHERE station_id IS NULL)  AS unowned,
       (SELECT COUNT(*) FROM work_order WHERE station_id IS NOT NULL) AS owned;
