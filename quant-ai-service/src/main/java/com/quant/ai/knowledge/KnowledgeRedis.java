package com.quant.ai.knowledge;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 问答共用的 Redis 连接，额度和公开行情各自限定可访问的键。 */
@Component
class KnowledgeRedis {
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final KnowledgeProperties properties;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    KnowledgeRedis(KnowledgeProperties properties) {
        this.properties = properties;
    }

    /** 首次访问时建连。断线期间拒绝命令，避免恢复后执行已放弃的扣额。 */
    synchronized StatefulRedisConnection<String, String> connection() {
        if (client == null) {
            var uri = RedisURI.Builder.redis(properties.redisHost(), properties.redisPort())
                    .withTimeout(TIMEOUT).build();
            client = RedisClient.create(uri);
            client.setOptions(ClientOptions.builder()
                    .autoReconnect(false)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(SocketOptions.builder().connectTimeout(TIMEOUT).build())
                    .build());
        }
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection;
    }

    /** 读取一个由调用方确定的缓存键。 */
    String read(String key) {
        return connection().sync().get(key);
    }

    /** 随应用关闭连接和客户端。 */
    @PreDestroy
    synchronized void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
