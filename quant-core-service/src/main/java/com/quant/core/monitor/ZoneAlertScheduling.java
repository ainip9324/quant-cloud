package com.quant.core.monitor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolate confirmation and SMTP from each other and the existing backtest watchdog. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "zone-alerts.enabled", havingValue = "true")
class ZoneAlertScheduling {
    @Bean ThreadPoolTaskScheduler zoneAlertScheduler() { return scheduler("zone-alert-confirm-"); }

    @Bean ThreadPoolTaskScheduler zoneAlertMailScheduler() { return scheduler("zone-alert-mail-"); }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
