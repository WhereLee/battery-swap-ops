-- =====================================================================
-- db/11-admin-rbac.sql（S7 WP-A）：管理端身份/权限/审计（幂等，可重复执行）
--   1) admin_user       管理员账号（BCrypt 密码；角色 SUPER/OPS/FINANCE/SUPPORT）
--   2) admin_op_log     管理操作审计（写操作留痕：谁/何时/何动作/结果）
--   3) refund_record    加列 operator_id/operator_name（人工退款操作人，WP-0 审查发现）
-- =====================================================================

DROP PROCEDURE IF EXISTS swap_migrate_s7_admin_rbac;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s7_admin_rbac()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_user') THEN
        CREATE TABLE admin_user (
            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
            username        VARCHAR(32)  NOT NULL,
            password_hash   VARCHAR(80)  NOT NULL COMMENT 'BCrypt',
            real_name       VARCHAR(32)  NULL,
            role            VARCHAR(16)  NOT NULL COMMENT 'SUPER/OPS/FINANCE/SUPPORT',
            status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 2 停用',
            last_login_time BIGINT       NULL,
            create_time     BIGINT       NOT NULL,
            update_time     BIGINT       NOT NULL,
            UNIQUE KEY uk_admin_username (username)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_op_log') THEN
        CREATE TABLE admin_op_log (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            admin_id    BIGINT       NULL COMMENT '会话管理员；bootstrap 静态 token 为 NULL',
            username    VARCHAR(32)  NOT NULL,
            action      VARCHAR(48)  NOT NULL,
            method      VARCHAR(8)   NOT NULL,
            uri         VARCHAR(255) NOT NULL,
            params_json VARCHAR(1000) NULL COMMENT '入参（脱敏截断）',
            result_code TINYINT      NOT NULL DEFAULT 0 COMMENT '0 成功 / 1 失败',
            error       VARCHAR(255) NULL,
            duration_ms BIGINT       NOT NULL,
            ip          VARCHAR(64)  NULL,
            create_time BIGINT       NOT NULL,
            KEY idx_oplog_admin_time (admin_id, create_time),
            KEY idx_oplog_time (create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_record'
                     AND COLUMN_NAME = 'operator_id') THEN
        ALTER TABLE refund_record
            ADD COLUMN operator_id   BIGINT      NULL COMMENT '人工退款操作人（admin_user.id）',
            ADD COLUMN operator_name VARCHAR(32) NULL COMMENT '操作人用户名快照';
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s7_admin_rbac();
DROP PROCEDURE swap_migrate_s7_admin_rbac;

SELECT 'db/11 admin-rbac done' AS done;
