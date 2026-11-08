package com.quant.data.live;

import com.quant.data.mapper.KlineMapper;
import com.quant.data.market.BitgetRestClient;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiveMarketProperties.class)
public class LiveMarketConfiguration {
    @Bean
    @ConditionalOnProperty(name = "bitget.live.enabled", havingValue = "true")
    BitgetLiveMarketFeed liveMarketFeed(LiveMarketProperties properties, KlineMapper mapper,
            BitgetRestClient rest, StringRedisTemplate redis,
            @Value("${bitget.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${bitget.proxy.host:127.0.0.1}") String proxyHost,
            @Value("${bitget.proxy.port:7897}") int proxyPort) {
        properties.selections();
        OkHttpClient.Builder client = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS);
        if (proxyEnabled) client.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        return new BitgetLiveMarketFeed(properties, client.build(),
                new LiveMarketRecorder(mapper, rest, redis, properties.getSnapshotTtlMillis()));
    }
}
