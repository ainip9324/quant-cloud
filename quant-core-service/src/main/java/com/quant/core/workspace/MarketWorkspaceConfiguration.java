package com.quant.core.workspace;

import com.quant.core.config.AuthProperties;
import com.quant.core.feign.DataFeignClient;
import com.quant.core.monitor.MarketMonitorProperties;
import com.quant.core.monitor.RedisLiveMarketSource;
import com.quant.core.monitor.ZoneMonitoringService;
import com.quant.core.zone.MarketZoneService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "market-workspace.enabled", havingValue = "true")
public class MarketWorkspaceConfiguration {
    @Bean MarketWorkspaceService marketWorkspaceService(ObjectProvider<ZoneMonitoringService> monitor, ObjectProvider<MarketZoneService> zones,
            ObjectProvider<RedisLiveMarketSource> source, DataFeignClient history, ObjectProvider<MarketMonitorProperties> properties) {
        if (monitor.getIfAvailable() == null || zones.getIfAvailable() == null || source.getIfAvailable() == null || properties.getIfAvailable() == null) {
            throw new IllegalStateException("Market workspace requires enabled zones and monitoring");
        }
        return new MarketWorkspaceService(monitor.getObject(), zones.getObject(), source.getObject(), history, properties.getObject());
    }
    @Bean(initMethod = "start", destroyMethod = "close") MarketWorkspaceStreams marketWorkspaceStreams(MarketWorkspaceService workspace, AuthProperties auth) {
        return new MarketWorkspaceStreams(workspace, auth.verifier());
    }
}
