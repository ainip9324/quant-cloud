package com.quant.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * RocketMQ 健康指示器：真实探测 NameServer 连通性。
 *
 * <p>背景：仅依赖 "RocketMQTemplate 已注入 / producer 已注册" 无法反映 MQ 运行时状态
 * （例如 NameServer 宕机后，已初始化的 producer 仍存在）。本指示器在每次请求时
 * TCP 探测 name-server（9876，remoting 协议，非 HTTP），实时反映 MQ 可达性。
 *
 * <p>注入方式：Spring Actuator 自动收集所有 HealthIndicator，
 * /actuator/health 会包含 "mq" 组件；业务接口 /api/core/health 也复用此指示器。
 */
@Slf4j
@Component
public class RocketMQHealthIndicator implements HealthIndicator {

    /** NameServer 探测超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 2000;

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;

    @Override
    public Health health() {
        String host;
        int port;
        try {
            String[] parts = nameServer.split(":");
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return Health.down()
                    .withDetail("nameServer", nameServer)
                    .withDetail("detail", "name-server 配置非法: " + nameServer)
                    .build();
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return Health.up()
                    .withDetail("nameServer", nameServer)
                    .withDetail("detail", "NameServer 连通正常")
                    .build();
        } catch (Exception e) {
            log.warn("RocketMQ NameServer 不可达: {}:{}", host, port, e);
            return Health.down()
                    .withDetail("nameServer", nameServer)
                    .withDetail("detail", "NameServer 不可达: " + e.getMessage())
                    .build();
        }
    }
}
