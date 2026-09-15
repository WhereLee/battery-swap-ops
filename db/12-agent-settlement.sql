-- =====================================================================
-- db/12-agent-settlement.sql（S7 WP-B）：代理与站点分润结算（幂等，可重复执行）
--   1) agent               代理商（分成比例 share_bp 万分比；0=全平台 / 10000=全代理）
--   2) station 加列 agent_id（NULL=直营）
--   3) order_settlement    分账流水 append-only（ORDER / REFUND_REVERSAL / ARREARS_SETTLE；
--                          负向冲正=退款冲正；event_key 唯一=幂等闸）
--   4) settlement_statement 结算单（顺序批；GENERATED→CONFIRMED→PAID；PAID 不可变）
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_s7_agent_settlement;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s7_agent_settlement()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent') THEN
        CREATE TABLE agent (
            id                BIGINT AUTO_INCREMENT PRIMARY KEY,
            agent_no          VARCHAR(32)  NOT NULL,
            name              VARCHAR(64)  NOT NULL,
            contact           VARCHAR(64)  NULL,
            status            TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 2 停用',
            share_bp          INT          NOT NULL DEFAULT 0 COMMENT '代理分成比例（万分比 0~10000）',
            settlement_cycle  VARCHAR(16)  NOT NULL DEFAULT 'MONTHLY' COMMENT '记录口径 DAILY/WEEKLY/MONTHLY',
            create_time       BIGINT       NOT NULL,
            update_time       BIGINT       NOT NULL,
            UNIQUE KEY uk_agent_no (agent_no)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'station'
                     AND COLUMN_NAME = 'agent_id') THEN
        ALTER TABLE station
            ADD COLUMN agent_id BIGINT NULL COMMENT '归属代理（NULL=直营）',
            ADD KEY idx_station_agent (agent_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_settlement') THEN
        CREATE TABLE order_settlement (
            id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
            event_key          VARCHAR(96) NOT NULL COMMENT '幂等键：orderNo:ORDER / orderNo:REV:refundNo / orderNo:ARR:arrearsId',
            order_id           BIGINT      NOT NULL,
            order_no           VARCHAR(40) NOT NULL,
            station_id         BIGINT      NULL,
            agent_id           BIGINT      NULL COMMENT 'NULL=直营（流水仍落，不参与结算单）',
            event_type         VARCHAR(24) NOT NULL COMMENT 'ORDER/REFUND_REVERSAL/ARREARS_SETTLE',
            base_type          VARCHAR(16) NOT NULL COMMENT 'CASH/PLAN_TIMES/PLAN_MONTHLY/REVERSAL/ARREARS',
            base_amount_fen    INT         NOT NULL COMMENT '分账基数（冲正为负）',
            agent_share_fen    INT         NOT NULL,
            platform_share_fen INT         NOT NULL,
            subsidy_fen        INT         NOT NULL DEFAULT 0 COMMENT '券补贴（平台承担）',
            statement_id       BIGINT      NULL COMMENT '挂结算单（NULL=未结）',
            create_time        BIGINT      NOT NULL,
            UNIQUE KEY uk_settle_event (event_key),
            KEY idx_settle_agent_stmt (agent_id, statement_id, create_time),
            KEY idx_settle_order (order_id),
            KEY idx_settle_time (create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_statement') THEN
        CREATE TABLE settlement_statement (
            id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
            statement_no       VARCHAR(40) NOT NULL,
            agent_id           BIGINT      NOT NULL,
            period_start       BIGINT      NOT NULL,
            period_end         BIGINT      NOT NULL,
            order_count        INT         NOT NULL DEFAULT 0,
            base_amount_fen    INT         NOT NULL DEFAULT 0,
            agent_amount_fen   INT         NOT NULL DEFAULT 0,
            platform_amount_fen INT        NOT NULL DEFAULT 0,
            subsidy_fen        INT         NOT NULL DEFAULT 0,
            status             TINYINT     NOT NULL DEFAULT 1 COMMENT '1 GENERATED / 2 CONFIRMED / 3 PAID',
            generated_by       VARCHAR(32) NULL,
            confirmed_by       VARCHAR(32) NULL,
            paid_by            VARCHAR(32) NULL,
            generated_time     BIGINT      NULL,
            confirmed_time     BIGINT      NULL,
            paid_time          BIGINT      NULL,
            remark             VARCHAR(255) NULL,
            create_time        BIGINT      NOT NULL,
            update_time        BIGINT      NOT NULL,
            UNIQUE KEY uk_stmt_no (statement_no),
            UNIQUE KEY uk_stmt_agent_period (agent_id, period_start, period_end),
            KEY idx_stmt_agent_status (agent_id, status)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s7_agent_settlement();
DROP PROCEDURE swap_migrate_s7_agent_settlement;

SELECT 'db/12 agent-settlement done' AS done;
