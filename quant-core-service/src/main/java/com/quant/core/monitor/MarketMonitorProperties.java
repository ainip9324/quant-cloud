package com.quant.core.monitor;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("market-monitor")
public class MarketMonitorProperties {
    private boolean enabled;
    private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
    private List<String> intervals = List.of("15min", "1h", "4h", "1day");
    private long pollMillis = 1_000;
    private long staleMillis = 10_000;
    private long maxGapMillis = 10_000;

    public List<MonitorScope> scopes() {
        if (pollMillis < 100 || staleMillis < pollMillis || staleMillis > 60_000 || maxGapMillis <= 0
                || symbols == null || symbols.isEmpty() || intervals == null || intervals.isEmpty()
                || symbols.stream().distinct().count() != symbols.size() || intervals.stream().distinct().count() != intervals.size()) {
            throw new IllegalArgumentException("Invalid market monitor configuration");
        }
        List<MonitorScope> scopes = new ArrayList<>();
        for (String symbol : symbols) for (String interval : intervals) scopes.add(MonitorScope.of("USDT_FUTURES", symbol, interval));
        return List.copyOf(scopes);
    }
}
