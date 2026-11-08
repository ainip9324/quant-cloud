package com.quant.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 核心服务启动类
 * 负责：策略引擎、回测引擎、回测任务管理
 *
 * Week 3：RocketMQ 事件驱动回测 + Watchdog 定时恢复。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.quant.core.feign")
@MapperScan("com.quant.core.mapper")
@EnableScheduling
public class CoreServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreServiceApplication.class, args);
    }
}
