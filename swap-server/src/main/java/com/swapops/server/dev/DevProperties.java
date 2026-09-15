package com.swapops.server.dev;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地联调配置（swap.dev.*）：默认关闭；开启时要求环境变量注入密钥（仓库零明文）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "swap.dev")
public class DevProperties {

    /** 联调开关（含 dev 端点与种子数据） */
    private boolean enabled = false;

    /** 设备密钥（32hex；经 SWAP_DEV_SECRET 环境变量注入） */
    private String secret;

    /** 种子柜数量 */
    private int cabinets = 2;

    /** 每柜仓数 */
    private int cellsPerCabinet = 12;

    /** 前 N 个仓放满电电池（其余空仓） */
    private int fullCells = 6;

    /** S7 WP-A: admin bootstrap password (env SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD; blank = skip seeding admin) */
    private String adminBootstrapPassword;
}
