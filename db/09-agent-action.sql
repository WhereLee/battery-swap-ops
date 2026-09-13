-- S4.6 增量迁移：Agent 动作建议（两段式：建议→人工确认→执行；幂等+审计）（幂等迁移）
DROP PROCEDURE IF EXISTS swap_migrate_s46_agent;
DELIMITER $$
CREATE PROCEDURE swap_migrate_s46_agent()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_action') THEN
        CREATE TABLE agent_action (
            id           BIGINT AUTO_INCREMENT PRIMARY KEY,
            action_no    VARCHAR(40)  NOT NULL,
            idem_key     VARCHAR(64)  NULL COMMENT '幂等键（可选；重复提交返回既有建议）',
            action_type  VARCHAR(48)  NOT NULL COMMENT '白名单动作类型',
            params_json  TEXT         NULL COMMENT '动作参数(JSON)',
            reason       VARCHAR(255) NULL COMMENT '建议理由（Agent/人填写）',
            status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1PROPOSED 2EXECUTED 3REJECTED 4FAILED 5EXECUTING',
            proposer     VARCHAR(64)  NULL COMMENT '提出方（agent-xxx / admin）',
            confirmer    VARCHAR(64)  NULL COMMENT '确认人（admin）',
            result_json  TEXT         NULL COMMENT '执行结果(JSON)',
            error_msg    VARCHAR(255) NULL,
            trace_id     VARCHAR(64)  NULL,
            create_time  BIGINT       NOT NULL,
            confirm_time BIGINT       NULL,
            update_time  BIGINT       NOT NULL,
            UNIQUE KEY uk_action_no (action_no),
            UNIQUE KEY uk_action_idem (idem_key),
            KEY idx_action_status_time (status, create_time)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL swap_migrate_s46_agent();
DROP PROCEDURE swap_migrate_s46_agent;
