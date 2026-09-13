package com.swapops.sim.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟器配置（swap.sim.*）：本进程"安装"的柜清单 + 上报参数。
 * 密钥经环境变量注入（SWAP_DEV_SECRET）；未配置即启动失败（fail-fast）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.sim")
public class SimProperties {

    /** 平台基地址（含 context-path） */
    private String serverBaseUrl;

    /** 心跳间隔秒（需小于平台心跳超时） */
    private int heartbeatIntervalSeconds = 10;

    /** 开仓动作耗时毫秒（门开事件延迟） */
    private long openDelayMillis = 300;

    // ---------- 充电仿真（S4.3） ----------

    /** 充电 tick 间隔毫秒（仿真心跳） */
    private long chargeTickMillis = 1000;

    /** 未下发策略时的默认总充电功率（W） */
    private int defaultChargePowerW = 4000;

    /** 单颗电池充电功率上限（W） */
    private int maxChargePowerW = 400;

    /** 电池容量（Wh，48V24Ah≈1150Wh） */
    private int batteryCapacityWh = 1150;

    /** 充电效率（0~1） */
    private double chargeEfficiency = 0.9;

    /** 仿真倍速（联调放大时间；1=真实时间） */
    private int chargeSpeedFactor = 1;

    /** 每柜仓数 */
    private int cellsPerCabinet = 12;

    /** 前 N 个仓预置满电电池 */
    private int fullCells = 6;

    /** 联调端点开关 */
    private boolean devEnabled = false;

    /**
     * 事件通道路由（S3.5）：http（默认，降级形态）/ mq（终态）/ dual（对照期双写）。
     * 心跳恒 HTTP（判活不依赖 broker）。
     */
    private String eventChannel = "http";

    /** MQ 通道参数（仅事件通道用） */
    private Mq mq = new Mq();

    @Data
    public static class Mq {
        /** 通道开关（event-channel=mq/dual 时必须为 true，由路由 Bean 构造期 fail-fast 校验） */
        private boolean enabled = true;
        private String endpoint = "127.0.0.1:8081";
        private String topic = "swap-device-event";
        /** 有界缓冲（保序队列；满则丢最旧，QUERY_STATE 兜底） */
        private int bufferSize = 500;
        /** 单条发送超时毫秒（超时回收 producer 重建；防 send 阻塞队首） */
        private long sendTimeoutMillis = 3000;
    }

    /** 本进程安装的柜（编号须与平台台账一致） */
    private List<CabinetCfg> cabinets = new ArrayList<>();

    @Data
    public static class CabinetCfg {
        private String cabinetNo;
        private String secret;
    }

    public String secretOf(String cabinetNo) {
        for (CabinetCfg cfg : cabinets) {
            if (cfg.getCabinetNo().equals(cabinetNo)) {
                return cfg.getSecret();
            }
        }
        return null;
    }
}
