package com.pauleternity.marketdaily;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 市场工作台后端入口，启动行情定时采集。 */
@SpringBootApplication
@EnableScheduling
public class MarketDailyApplication {

    /** 启动 Spring Boot 应用。 */
    public static void main(String[] args) {
        SpringApplication.run(MarketDailyApplication.class, args);
    }
}
