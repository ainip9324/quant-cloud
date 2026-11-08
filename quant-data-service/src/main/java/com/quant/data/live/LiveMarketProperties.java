package com.quant.data.live;

import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketSelection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("bitget.live")
public class LiveMarketProperties {
    private boolean enabled;
    private String websocketUrl = "wss://ws.bitget.com/v2/ws/public";
    private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
    private List<String> intervals = List.of("15min", "1h", "4h", "1day");
    private long heartbeatMillis = 30_000;
    private long pongTimeoutMillis = 10_000;
    private long staleMillis = 10_000;
    private long reconnectMinMillis = 1_000;
    private long reconnectMaxMillis = 30_000;
    private long recoveryRetryMillis = 5_000;
    private long snapshotTtlMillis = 120_000;

    public List<MarketSelection> selections() {
        URI uri = URI.create(websocketUrl);
        if (!("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().endsWith("/public")) {
            throw new IllegalArgumentException("Invalid public WebSocket URL");
        }
        if (heartbeatMillis <= 0 || pongTimeoutMillis <= 0 || staleMillis <= 0
                || reconnectMinMillis <= 0 || reconnectMaxMillis < reconnectMinMillis
                || recoveryRetryMillis <= 0 || snapshotTtlMillis <= staleMillis
                || symbols == null || symbols.isEmpty() || intervals == null || intervals.isEmpty()
                || symbols.stream().distinct().count() != symbols.size()
                || intervals.stream().distinct().count() != intervals.size()) {
            throw new IllegalArgumentException("Invalid live market configuration");
        }
        List<MarketSelection> result = new ArrayList<>();
        for (String symbol : symbols) {
            for (String interval : intervals) {
                result.add(MarketCatalog.requireSelection("USDT_FUTURES", symbol, interval));
            }
        }
        return List.copyOf(result);
    }

    static long periodMillis(String interval) {
        return switch (interval) {
            case "15min" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            case "1day" -> 86_400_000L;
            default -> throw new IllegalArgumentException("Unsupported live interval");
        };
    }
}
