package com.quant.core.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.core.mapper.MarketMonitorMapper;
import com.quant.core.mapper.MarketZoneMapper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "market-monitor.enabled", havingValue = "true")
@EnableConfigurationProperties(MarketMonitorProperties.class)
public class MarketMonitorConfiguration {
    @Bean ZoneMonitoringService zoneMonitoringService(MarketZoneMapper zones, MarketMonitorMapper mapper,
            PlatformTransactionManager transactions, MarketMonitorProperties properties,
            @Value("${market-zones.enabled:false}") boolean zonesEnabled) {
        if (!zonesEnabled) throw new IllegalStateException("Market monitoring requires enabled market zones");
        return new ZoneMonitoringService(zones, mapper, transactions, properties);
    }
    @Bean RedisLiveMarketSource redisLiveMarketSource(StringRedisTemplate redis, ObjectMapper json) {
        return new RedisLiveMarketSource(redis, json);
    }
    @Bean ZoneMonitorRuntime zoneMonitorRuntime(RedisLiveMarketSource source, ZoneMonitoringService service, MarketMonitorProperties properties) {
        return new ZoneMonitorRuntime(source, service, properties);
    }
    @Bean(destroyMethod = "shutdown") ExecutorService marketMonitorNoticeExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "zone-monitor-notice"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }
    @Bean RedisMessageListenerContainer marketMonitorListener(RedisConnectionFactory connections, ZoneMonitorRuntime runtime,
            @Qualifier("marketMonitorNoticeExecutor") ExecutorService notices) {
        // Initial subscription failures must not fail core startup. The worker owns retry/stop.
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override public boolean isAutoStartup() { return false; }
        };
        container.setConnectionFactory(connections); container.setTaskExecutor(notices); container.setRecoveryInterval(5_000);
        container.setErrorHandler(error -> runtime.subscriptionError());
        container.addMessageListener(runtime, new ChannelTopic("market:updates:v1"));
        runtime.manageSubscription(container);
        return container;
    }
}
