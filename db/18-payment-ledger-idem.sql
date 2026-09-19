-- =====================================================================
-- db/18-payment-ledger-idem.sql（批次35 / AUD-6）：payment_record 幂等键按资金动作语义分型
--
-- 问题：旧唯一键 uk_order_type(order_id, payment_type) 本意是"资金动作幂等闸"
--       （重复投递/重放不二次扣款），但它对 REFUND 一并生效，于是**同一订单的第二笔
--       合法部分退款**（refund_record 的唯一键是 (order_id, reason)，不同原因允许多笔）
--       在写 payment_record 时撞唯一键、被 PaymentRecordService 当作"幂等命中"吞掉
--       ⇒ 钱包金额正确，但资金台账漏记第二笔退款。
--
-- 修法：把"幂等闸"收窄成真正需要它的形状——用生成列做唯一键：
--       * REFUND            → NULL（不受约束；每笔退款由 refund_record 的唯一键与
--                              payment_record.uk_trade_no 各自保证幂等）
--       * order_id IS NULL  → NULL（与旧键行为一致：MySQL 唯一索引不约束 NULL，
--                              这类行是充值/购套餐等无订单归属的流水）
--       * 其余              → "orderId:paymentType"（保持"每单每类资金动作只记一次"）
--   唯一索引允许多个 NULL，所以上面两类自然不受约束。
--
-- 幂等：重复执行安全（先探列与索引是否存在）。旧键若已不存在则跳过。
-- 回滚：DROP INDEX uk_payment_idem; ALTER TABLE payment_record DROP COLUMN idem_key;
--       ALTER TABLE payment_record ADD UNIQUE KEY uk_order_type (order_id, payment_type);
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_p18_payment_ledger;
DELIMITER $$
CREATE PROCEDURE swap_migrate_p18_payment_ledger()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_record'
                     AND COLUMN_NAME = 'idem_key') THEN
        ALTER TABLE payment_record
            ADD COLUMN idem_key VARCHAR(80)
                GENERATED ALWAYS AS (
                    CASE
                        WHEN payment_type = 'REFUND' THEN NULL
                        WHEN order_id IS NULL THEN NULL
                        ELSE CONCAT(order_id, ':', payment_type)
                    END
                ) STORED COMMENT '资金动作幂等键（REFUND 与无订单行不约束）';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_record'
                 AND INDEX_NAME = 'uk_order_type') THEN
        ALTER TABLE payment_record DROP INDEX uk_order_type;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_record'
                     AND INDEX_NAME = 'uk_payment_idem') THEN
        ALTER TABLE payment_record ADD UNIQUE KEY uk_payment_idem (idem_key);
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_p18_payment_ledger();
DROP PROCEDURE swap_migrate_p18_payment_ledger;

SELECT 'db/18 payment-ledger-idem done' AS done;
