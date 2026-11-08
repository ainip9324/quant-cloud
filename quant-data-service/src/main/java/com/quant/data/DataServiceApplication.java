package com.quant.data;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 数据服务启动类
 * 负责：Bitget 行情拉取、MySQL 入库、Redis 缓存
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.quant.data.mapper")
public class DataServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataServiceApplication.class, args);
    }
}
