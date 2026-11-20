package com.quant.core.monitor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Run SMTP independently of the existing backtest watchdog. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "zone-alerts.enabled", havingValue = "true")
class ZoneAlertScheduling {
    @Bean ThreadPoolTaskScheduler zoneAlertMailScheduler() { return scheduler("zone-alert-mail-"); }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
