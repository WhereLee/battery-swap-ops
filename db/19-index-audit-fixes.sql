-- =====================================================================
-- db/19-index-audit-fixes.sql（批次40）：索引审计实测发现的两处访问路径缺口
--
-- 来源：performance_schema.events_statements_summary_by_digest —— **真实流量**的服务器侧统计，
--       不是读代码推断出来的"可能慢"。审计口径见 scripts/verify/batch40/_c39_index_audit.ps1。
--
-- 缺口一：payment_record 的 order_id 访问路径消失了
--   实测：同一 digest（`WHERE ( order_id = ? )`）执行 54,647 次，其中
--         SUM_NO_INDEX_USED = 5,068 次、SUM_ROWS_EXAMINED = 40,641,477 行、SUM_ROWS_SENT = 6,629 行
--         ⇒ 每次全表扫描约 8,090 行，只为换回 1 行（表仅 7,875 行）。
--   EXPLAIN 复核：type=ALL、possible_keys=NULL、key=NULL、rows=7875。
--   机制：db/18（批次35 / AUD-6）为修"第二笔部分退款台账漏记"，删掉了
--         uk_order_type(order_id, payment_type)。那个唯一键**同时承担两个职责**：
--         ① 资金动作幂等闸（唯一性）② `WHERE order_id = ?` 的唯一访问路径（可访问性）。
--         删它只为解决 ① 的语义问题，② 随之消失——**一个索引担两个职责时，
--         按其中一个职责删掉它，另一个职责会被静默破坏**。
--   旁证：同形状但走索引的 digest（SUM_NO_INDEX_USED = 0，14,240 次）LAST_SEEN = 2026-09-17 10:26，
--         此后只有全表扫描的那一支在跑。
--   修法：补**非唯一**索引 idx_order_id(order_id)。
--         刻意不用唯一键：退款的多笔语义已由 refund_record 唯一键与 uk_trade_no 保证，
--         幂等闸已收窄到 uk_payment_idem；重建唯一键等于退回 AUD-6。
--
-- 缺口二：swap_order 的对账计数只能全表扫
--   实测：`SELECT COUNT(*) FROM swap_order WHERE ( status = ? AND complete_time >= ? )`
--         执行 32 次、其中 30 次 SUM_NO_INDEX_USED、每次扫 5,296 行（表 5,177 行）。
--   EXPLAIN 复核：type=ALL、possible_keys=idx_order_status、key=NULL
--         （status 选择性太低，优化器判断"走索引再回表"比全表扫更贵——这个判断是对的）。
--   修法：补 idx_status_complete(status, complete_time)，把"状态 + 完成时间窗口"变成一个区间扫描。
--
-- 缺口三：告警"全部"页签只能全表排序
--   实测：`SELECT ... FROM alarm ORDER BY create_time DESC LIMIT ?`
--         SUM_NO_INDEX_USED = 8、每次扫 4,032 行；另有 2 次无 LIMIT 的形态每次扫 7,708 行。
--   EXPLAIN 复核：type=ALL、possible_keys=NULL、Extra=Using filesort。
--   机制：alarm 上有 idx_alarm_handled_time(handled, create_time) 与
--         idx_device_type_time(device_no, alarm_type, handled, create_time)，
--         **两个索引都不以 create_time 打头**。带 handled 过滤时
--         （`WHERE handled = ? ORDER BY create_time DESC LIMIT ?`）走 idx_alarm_handled_time
--         且是 `Backward index scan; Using index`（覆盖索引，很好）；
--         但管理台告警页的"全部"页签传 handled=undefined ⇒ 条件消失 ⇒
--         `AdminObservationViewService.alarmPage` 生成的 SQL 只剩 ORDER BY ⇒ 全表 + filesort。
--   修法：补 idx_alarm_create_time(create_time)，让"全部"页签退化成反向索引扫描 + LIMIT 下推。
--   代价如实记录：alarm 是持续写入表（设备事件/模拟器），多维护一个索引；换来的是
--   一个**页面上点一下就到**的路径不再每次排全表。不改成"总是带 handled 过滤"是因为
--   那会改变语义（用户要的就是全部）。
--
-- 幂等：先探 information_schema.STATISTICS，索引已存在则跳过。
-- 回滚：ALTER TABLE payment_record DROP INDEX idx_order_id;
--       ALTER TABLE swap_order DROP INDEX idx_status_complete;
--       ALTER TABLE alarm DROP INDEX idx_alarm_create_time;
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_p19_index_audit;
DELIMITER $$
CREATE PROCEDURE swap_migrate_p19_index_audit()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_record'
                     AND INDEX_NAME = 'idx_order_id') THEN
        ALTER TABLE payment_record ADD KEY idx_order_id (order_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order'
                     AND INDEX_NAME = 'idx_status_complete') THEN
        ALTER TABLE swap_order ADD KEY idx_status_complete (status, complete_time);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alarm'
                     AND INDEX_NAME = 'idx_alarm_create_time') THEN
        ALTER TABLE alarm ADD KEY idx_alarm_create_time (create_time);
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_p19_index_audit();
DROP PROCEDURE swap_migrate_p19_index_audit;

SELECT 'db/19 index-audit-fixes done' AS done;
