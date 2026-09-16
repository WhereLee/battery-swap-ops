-- =====================================================================
-- db/16-data-scope.sql（P1-8）：管理端数据范围（幂等，可重复执行）
--   admin_user 加两列：
--     data_scope         ALL / STATION（默认 ALL=不限，STATION=按站点隔离）
--     scope_station_nos  逗号分隔的 station_no 列表（STATION 时应用层校验非空且存在）
--   演示账号由剧本经 /admin/account 创建，不在此落数据。
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_p18_data_scope;
DELIMITER $$
CREATE PROCEDURE swap_migrate_p18_data_scope()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_user'
                     AND COLUMN_NAME = 'data_scope') THEN
        ALTER TABLE admin_user
            ADD COLUMN data_scope        VARCHAR(16)  NOT NULL DEFAULT 'ALL'
                COMMENT 'ALL=全部 / STATION=按站点范围',
            ADD COLUMN scope_station_nos VARCHAR(255) NULL
                COMMENT '逗号分隔 station_no（STATION 范围）';
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_p18_data_scope();
DROP PROCEDURE swap_migrate_p18_data_scope;

SELECT 'db/16 data-scope done' AS done;
