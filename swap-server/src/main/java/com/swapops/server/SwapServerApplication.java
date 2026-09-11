package com.swapops.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.swapops.server.device.dao")
public class SwapServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwapServerApplication.class, args);
    }
}
