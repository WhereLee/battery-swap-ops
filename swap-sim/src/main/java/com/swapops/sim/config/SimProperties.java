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

    /** 每柜仓数 */
    private int cellsPerCabinet = 12;

    /** 前 N 个仓预置满电电池 */
    private int fullCells = 6;

    /** 联调端点开关 */
    private boolean devEnabled = false;

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
