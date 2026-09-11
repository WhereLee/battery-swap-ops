package com.swapops.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"com.swapops.server.device.dao", "com.swapops.server.user.dao",
        "com.swapops.server.order.dao", "com.swapops.server.asset.dao"})
public class SwapServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwapServerApplication.class, args);
    }
}
