-- =====================================================================
-- db/13-channel-recon.sql（S7 WP-C）：渠道对账 T+1（幂等，可重复执行）
--   1) channel_bill  渠道账单明细（T+1 导入；唯一键=日期+渠道+流水号）
--   2) recon_diff    差异单（四类：渠道独有/平台独有/金额不符/状态不符；处置留痕）
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_s7_channel_recon;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s7_channel_recon()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'channel_bill') THEN
        CREATE TABLE channel_bill (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            bill_date   VARCHAR(10)  NOT NULL COMMENT 'yyyy-MM-dd（渠道账单日）',
            channel     VARCHAR(16)  NOT NULL DEFAULT 'MOCK',
            trade_no    VARCHAR(64)  NOT NULL,
            amount_fen  INT          NOT NULL,
            status      VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/CLOSED',
            imported_at BIGINT       NOT NULL,
            UNIQUE KEY uk_bill_day_channel_trade (bill_date, channel, trade_no),
            KEY idx_bill_day_status (bill_date, status)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recon_diff') THEN
        CREATE TABLE recon_diff (
            id           BIGINT AUTO_INCREMENT PRIMARY KEY,
            bill_date    VARCHAR(10)  NOT NULL,
            channel      VARCHAR(16)  NOT NULL DEFAULT 'MOCK',
            trade_no     VARCHAR(64)  NOT NULL,
            diff_type    VARCHAR(24)  NOT NULL COMMENT 'CHANNEL_ONLY/PLATFORM_ONLY/AMOUNT_MISMATCH/STATUS_MISMATCH',
            detail       VARCHAR(255) NULL,
            status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/HANDLED/IGNORED',
            handled_by   VARCHAR(32)  NULL,
            handled_time BIGINT       NULL,
            remark       VARCHAR(255) NULL,
            create_time  BIGINT       NOT NULL,
            UNIQUE KEY uk_diff_day_trade_type (bill_date, channel, trade_no, diff_type),
            KEY idx_diff_day_status (bill_date, status)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s7_channel_recon();
DROP PROCEDURE swap_migrate_s7_channel_recon;

SELECT 'db/13 channel-recon done' AS done;
