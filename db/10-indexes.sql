-- =====================================================================
-- db/10-indexes.sql（S5 运维审查）：慢查询面索引补强（幂等，可重复执行）
-- 依据：S5 业务审查 + 慢 SQL 审计（批次8/9，见 document/knowledge/ops-troubleshooting.md）
--  1) swap_order.open_command_seq：设备事件推进订单（OrderEventService.findBySeq）
--     与对账（findByOpenCommand）热路径，此前无索引=每事件全表扫；
--  2) swap_order.status：OrderSweepTask 每 5s 按状态扫超时、对账 checkStaleActive；
--  3) swap_order.create_time：管理端分页 ORDER BY create_time DESC 此前 filesort；
--  4) alarm(handled, create_time)：管理端告警列表（默认未处理倒序）。
-- =====================================================================

SET NAMES utf8mb4;

SELECT 'add idx_open_command_seq' AS step;
SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order'
             AND INDEX_NAME = 'idx_open_command_seq');
SET @ddl := IF(@i = 0,
    'ALTER TABLE swap_order ADD KEY idx_open_command_seq (cabinet_id, open_command_seq)',
    'SELECT ''skip idx_open_command_seq''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'add idx_order_status' AS step;
SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order'
             AND INDEX_NAME = 'idx_order_status');
SET @ddl := IF(@i = 0,
    'ALTER TABLE swap_order ADD KEY idx_order_status (status)',
    'SELECT ''skip idx_order_status''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'add idx_order_create_time' AS step;
SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order'
             AND INDEX_NAME = 'idx_order_create_time');
SET @ddl := IF(@i = 0,
    'ALTER TABLE swap_order ADD KEY idx_order_create_time (create_time)',
    'SELECT ''skip idx_order_create_time''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'add idx_alarm_handled_time' AS step;
SET @i := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alarm'
             AND INDEX_NAME = 'idx_alarm_handled_time');
SET @ddl := IF(@i = 0,
    'ALTER TABLE alarm ADD KEY idx_alarm_handled_time (handled, create_time)',
    'SELECT ''skip idx_alarm_handled_time''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'db/10 indexes done' AS done;
