-- S4.4 增量迁移：工单与工单日志（幂等：information_schema 检查；可重复运行）
-- 语义：告警→工单闭环（分诊/派单/处置/验收）+ SLA 截止时间；一个告警最多一张工单（唯一键幂等）

DROP PROCEDURE IF EXISTS swap_migrate_s44_workorder;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s44_workorder()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order') THEN
        CREATE TABLE work_order (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            wo_no         VARCHAR(40)  NOT NULL,
            alarm_id      BIGINT       NULL COMMENT '来源告警（唯一：一个告警一张工单）',
            device_type   VARCHAR(16)  NULL,
            device_no     VARCHAR(32)  NULL,
            title         VARCHAR(128) NOT NULL,
            severity      VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'HIGH/MEDIUM/LOW',
            status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1OPEN 2TRIAGED 3ASSIGNED 4HANDLING 5VERIFIED 6CLOSED',
            handler_id    BIGINT       NULL,
            sla_deadline  BIGINT       NOT NULL COMMENT 'SLA 截止(ms)',
            sla_breached  TINYINT      NOT NULL DEFAULT 0,
            verify_time   BIGINT       NULL,
            close_time    BIGINT       NULL,
            remark        VARCHAR(255) NULL,
            create_time   BIGINT       NOT NULL,
            update_time   BIGINT       NOT NULL,
            UNIQUE KEY uk_wo_no (wo_no),
            UNIQUE KEY uk_wo_alarm (alarm_id),
            KEY idx_wo_status_time (status, create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order_log') THEN
        CREATE TABLE work_order_log (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            wo_no       VARCHAR(40)  NOT NULL,
            action      VARCHAR(24)  NOT NULL COMMENT 'CREATE/TRIAGE/ASSIGN/START/VERIFY/CLOSE/SLA_BREACH',
            from_status TINYINT      NULL,
            to_status   TINYINT      NULL,
            operator    VARCHAR(32)  NULL,
            remark      VARCHAR(255) NULL,
            create_time BIGINT       NOT NULL,
            KEY idx_wolog_wo (wo_no, id)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s44_workorder();
DROP PROCEDURE swap_migrate_s44_workorder;
