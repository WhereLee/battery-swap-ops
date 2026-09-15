-- =====================================================================
-- db/14-user-service.sql（S7 WP-D）：报障/欠费/优惠券/站内信（幂等，可重复执行）
--   1) work_order 加列：source / reporter_user_id / description（用户报障来源）
--   2) arrears_record  欠费单（超时费不足额落单；补缴/减免结清）
--   3) coupon_template 券模板 + user_coupon 用户券（UNUSED/LOCKED/USED/EXPIRED 状态机）
--   4) user_message    站内信（券发放/欠费/退款到账/报障工单关闭）
--   5) swap_order 加列：coupon_id / discount_fen（券抵扣留痕，分账基数=实收+抵扣）
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_s7_user_service;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s7_user_service()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order'
                     AND COLUMN_NAME = 'source') THEN
        ALTER TABLE work_order
            ADD COLUMN source          VARCHAR(16)  NOT NULL DEFAULT 'ALARM' COMMENT 'ALARM/USER_REPORT',
            ADD COLUMN reporter_user_id BIGINT      NULL,
            ADD COLUMN description     VARCHAR(255) NULL,
            ADD KEY idx_wo_reporter (reporter_user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'arrears_record') THEN
        CREATE TABLE arrears_record (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id     BIGINT      NOT NULL,
            order_id    BIGINT      NOT NULL,
            order_no    VARCHAR(40) NOT NULL,
            amount_fen  INT         NOT NULL COMMENT '欠费应收',
            settled_fen INT         NOT NULL DEFAULT 0 COMMENT '已结清（补缴+减免）',
            status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1 OPEN / 2 SETTLED',
            create_time BIGINT      NOT NULL,
            settle_time BIGINT      NULL,
            UNIQUE KEY uk_arrears_order (order_id),
            KEY idx_arrears_user_status (user_id, status)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_template') THEN
        CREATE TABLE coupon_template (
            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
            name            VARCHAR(64) NOT NULL,
            type            VARCHAR(16) NOT NULL DEFAULT 'FIXED' COMMENT 'FIXED=立减（分）',
            value_fen       INT         NOT NULL,
            min_amount_fen  INT         NOT NULL DEFAULT 0 COMMENT '门槛（订单基础费）',
            total_quantity  INT         NOT NULL COMMENT '发行总量',
            issued_count    INT         NOT NULL DEFAULT 0,
            per_user_limit  INT         NOT NULL DEFAULT 1,
            valid_from      BIGINT      NOT NULL,
            valid_to        BIGINT      NOT NULL,
            status          TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 / 2 停用',
            create_time     BIGINT      NOT NULL,
            UNIQUE KEY uk_coupon_name (name)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_coupon') THEN
        CREATE TABLE user_coupon (
            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id         BIGINT  NOT NULL,
            template_id     BIGINT  NOT NULL,
            status          TINYINT NOT NULL DEFAULT 1 COMMENT '1 UNUSED / 2 LOCKED / 3 USED / 4 EXPIRED',
            locked_order_id BIGINT  NULL,
            used_order_id   BIGINT  NULL,
            value_fen       INT     NOT NULL DEFAULT 0 COMMENT '面额快照（发放时从模板复制）',
            min_amount_fen  INT     NOT NULL DEFAULT 0 COMMENT '门槛快照',
            received_time   BIGINT  NOT NULL,
            locked_time     BIGINT  NULL,
            used_time       BIGINT  NULL,
            expire_time     BIGINT  NOT NULL,
            KEY idx_ucoupon_user_status (user_id, status),
            KEY idx_ucoupon_locked_order (locked_order_id)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    -- 快照列补丁（对已建表的幂等补充：模板改价不影响已发券）
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_coupon'
                     AND COLUMN_NAME = 'value_fen') THEN
        ALTER TABLE user_coupon
            ADD COLUMN value_fen      INT NOT NULL DEFAULT 0 COMMENT '面额快照（发放时从模板复制）',
            ADD COLUMN min_amount_fen INT NOT NULL DEFAULT 0 COMMENT '门槛快照';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_message') THEN
        CREATE TABLE user_message (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id     BIGINT       NOT NULL,
            type        VARCHAR(16)  NOT NULL COMMENT 'REWARD/ARREARS/REFUND/WORK_ORDER',
            title       VARCHAR(128) NOT NULL,
            content     VARCHAR(512) NULL,
            read_flag   TINYINT      NOT NULL DEFAULT 0,
            create_time BIGINT       NOT NULL,
            KEY idx_msg_user_read (user_id, read_flag, id)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'swap_order'
                     AND COLUMN_NAME = 'coupon_id') THEN
        ALTER TABLE swap_order
            ADD COLUMN coupon_id    BIGINT NULL COMMENT '用户券（下单锁定）',
            ADD COLUMN discount_fen INT    NOT NULL DEFAULT 0 COMMENT '券抵扣（分）';
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s7_user_service();
DROP PROCEDURE swap_migrate_s7_user_service;

SELECT 'db/14 user-service done' AS done;
