-- battery-swap-ops 基线表结构（S0.5 口径；幂等：IF NOT EXISTS）
-- 约定：毫秒时间戳 bigint；金额分 int；状态用小整数（码值见 swap-contract 枚举）。

CREATE TABLE IF NOT EXISTS station (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_no   VARCHAR(32)  NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    address      VARCHAR(128),
    status       TINYINT      NOT NULL DEFAULT 1,
    create_time  BIGINT       NOT NULL,
    update_time  BIGINT       NOT NULL,
    UNIQUE KEY uk_station_no (station_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cabinet (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    cabinet_no          VARCHAR(32) NOT NULL,
    station_id          BIGINT      NOT NULL,
    cell_count          INT         NOT NULL DEFAULT 0,
    status              TINYINT     NOT NULL DEFAULT 1,
    secret              VARCHAR(64) NOT NULL DEFAULT '',
    last_boot_id        VARCHAR(64),
    last_event_seq      BIGINT,
    last_heartbeat_time BIGINT,
    create_time         BIGINT      NOT NULL,
    update_time         BIGINT      NOT NULL,
    UNIQUE KEY uk_cabinet_no (cabinet_no),
    KEY idx_station (station_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cell (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    cabinet_id    BIGINT  NOT NULL,
    cell_no       INT     NOT NULL,
    status        TINYINT NOT NULL DEFAULT 1,
    battery_id    BIGINT,
    lock_order_id BIGINT,
    update_time   BIGINT  NOT NULL,
    UNIQUE KEY uk_cabinet_cell (cabinet_id, cell_no),
    KEY idx_battery (battery_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS battery (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    battery_no     VARCHAR(32) NOT NULL,
    model          VARCHAR(32),
    status         TINYINT     NOT NULL DEFAULT 1,
    soc            INT         NOT NULL DEFAULT 0,
    soh            INT         NOT NULL DEFAULT 100,
    cycle_count    INT         NOT NULL DEFAULT 0,
    cell_id        BIGINT,
    holder_user_id BIGINT,
    update_time    BIGINT      NOT NULL,
    UNIQUE KEY uk_battery_no (battery_no),
    UNIQUE KEY uk_battery_cell (cell_id),
    UNIQUE KEY uk_battery_holder (holder_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS swap_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone       VARCHAR(20) NOT NULL,
    name        VARCHAR(32),
    status      TINYINT     NOT NULL DEFAULT 1,
    deposit_fen INT         NOT NULL DEFAULT 0,
    create_time BIGINT      NOT NULL,
    update_time BIGINT      NOT NULL,
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS plan (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(64) NOT NULL,
    plan_type          VARCHAR(16) NOT NULL,
    price_fen          INT         NOT NULL,
    total_times        INT,
    duration_days      INT,
    daily_limit_times  INT,
    status             TINYINT     NOT NULL DEFAULT 1,
    create_time        BIGINT      NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS user_plan (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    plan_id         BIGINT      NOT NULL,
    start_time      BIGINT      NOT NULL,
    end_time        BIGINT      NOT NULL,
    remaining_times INT,
    status          TINYINT     NOT NULL DEFAULT 1,
    create_time     BIGINT      NOT NULL,
    KEY idx_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS wallet (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    balance_fen  INT    NOT NULL DEFAULT 0,
    deposit_fen  INT    NOT NULL DEFAULT 0,
    update_time  BIGINT NOT NULL,
    UNIQUE KEY uk_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS swap_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(40) NOT NULL,
    order_type      VARCHAR(16) NOT NULL,
    user_id         BIGINT      NOT NULL,
    station_id      BIGINT,
    cabinet_id      BIGINT,
    cell_id         BIGINT,
    take_battery_id BIGINT,
    return_battery_id BIGINT,
    user_plan_id    BIGINT,
    status          TINYINT     NOT NULL DEFAULT 1,
    fee_fen         INT         NOT NULL DEFAULT 0,
    pay_type        VARCHAR(16),
    open_command_seq BIGINT,
    create_time     BIGINT      NOT NULL,
    open_time       BIGINT,
    take_time       BIGINT,
    return_time     BIGINT,
    complete_time   BIGINT,
    update_time     BIGINT      NOT NULL,
    active_user_key BIGINT GENERATED ALWAYS AS (IF(status IN (1, 2, 3, 4), user_id, NULL)) STORED,
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_active_user (active_user_key),
    KEY idx_user_time (user_id, create_time),
    KEY idx_cabinet_time (cabinet_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS command_log (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    cabinet_no     VARCHAR(32) NOT NULL,
    command_action VARCHAR(24) NOT NULL,
    command_seq    BIGINT      NOT NULL,
    command_status TINYINT     NOT NULL DEFAULT 1,
    retry_count    INT         NOT NULL DEFAULT 0,
    trace_id       VARCHAR(64),
    create_time    BIGINT      NOT NULL,
    update_time    BIGINT      NOT NULL,
    UNIQUE KEY uk_cabinet_seq (cabinet_no, command_seq),
    KEY idx_status_time (command_status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS payment_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    order_id     BIGINT,
    payment_type VARCHAR(24) NOT NULL,
    amount_fen   INT         NOT NULL,
    channel      VARCHAR(16) NOT NULL DEFAULT 'MOCK',
    trade_no     VARCHAR(64) NOT NULL,
    status       TINYINT     NOT NULL DEFAULT 1,
    create_time  BIGINT      NOT NULL,
    UNIQUE KEY uk_trade_no (trade_no),
    UNIQUE KEY uk_order_type (order_id, payment_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS alarm (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_type  VARCHAR(16) NOT NULL,
    device_no    VARCHAR(32) NOT NULL,
    alarm_type   VARCHAR(32) NOT NULL,
    content      VARCHAR(512),
    handled      TINYINT     NOT NULL DEFAULT 0,
    handler      BIGINT,
    create_time  BIGINT      NOT NULL,
    handled_time BIGINT,
    KEY idx_device_type_time (device_no, alarm_type, handled, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
