package com.swapops.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@MapperScan({"com.swapops.server.device.dao", "com.swapops.server.user.dao",
        "com.swapops.server.order.dao", "com.swapops.server.asset.dao", "com.swapops.server.alarm.dao",
        "com.swapops.server.outbox.dao", "com.swapops.server.workorder.dao",
        "com.swapops.server.transfer.dao", "com.swapops.server.charge.dao",
        "com.swapops.server.agent.dao", "com.swapops.server.admin.dao",
        "com.swapops.server.payrecon.dao"})
public class SwapServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwapServerApplication.class, args);
    }
}
