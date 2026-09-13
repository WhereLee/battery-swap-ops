package com.swapops.sim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SwapSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwapSimApplication.class, args);
    }
}
