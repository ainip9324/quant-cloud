package com.quant.data.market;
import com.quant.common.market.MarketType;
import com.quant.common.model.Kline;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class BitgetUsdtFuturesMarketDataAdapter implements MarketDataAdapter {
    private static final Map<String, String> GRANULARITY = Map.of(
            "15min", "15m", "1h", "1H", "4h", "4H", "1day", "1D");
    private static final int HISTORY_PAGE_LIMIT = 200;
    private static final int MAX_HISTORY_PAGES = 16;
    private final BitgetRestClient client;

    public BitgetUsdtFuturesMarketDataAdapter(BitgetRestClient client) { this.client = client; }
    @Override public MarketType marketType() { return MarketType.USDT_FUTURES; }

    @Override public List<Kline> fetch(MarketDataQuery query) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        TreeMap<Long, Kline> candles = new TreeMap<>();
        for (Kline bar : client.fetchBefore("/api/v2/mix/market/candles", Map.of(
                "symbol", query.selection().symbol(), "productType", "USDT-FUTURES",
                "granularity", GRANULARITY.get(query.selection().interval()), "limit", String.valueOf(query.limit())), deadline)) {
            validate(bar);
            candles.put(bar.getTime(), bar);
        }
        if (candles.isEmpty()) return List.of();

        int pages = 0;
        while (candles.size() < query.limit()) {
            if (pages++ >= MAX_HISTORY_PAGES) throw new IllegalStateException("Bitget history pagination exceeded its bound");
            long cursor = candles.firstKey();
            // Reserve one row for a repeated boundary candle; short pages can still have older history.
            int pageSize = Math.min(HISTORY_PAGE_LIMIT, query.limit() - candles.size() + 1);
            List<Kline> page = client.fetchBefore("/api/v2/mix/market/history-candles", Map.of(
                    "symbol", query.selection().symbol(), "productType", "USDT-FUTURES",
                    "granularity", GRANULARITY.get(query.selection().interval()),
                    "endTime", String.valueOf(cursor), "limit", String.valueOf(pageSize)), deadline);
            if (page.isEmpty()) break;
            for (Kline bar : page) {
                validate(bar);
                if (bar.getTime() < cursor) candles.putIfAbsent(bar.getTime(), bar);
            }
            if (candles.firstKey() >= cursor) throw new IllegalStateException("Bitget history cursor did not advance");
        }
        return candles.values().stream().skip(Math.max(0, candles.size() - query.limit())).toList();
    }

    private static void validate(Kline bar) {
        if (bar == null || bar.getTime() == null || bar.getTime() <= 0
                || !Double.isFinite(bar.getOpen()) || !Double.isFinite(bar.getHigh()) || !Double.isFinite(bar.getLow())
                || !Double.isFinite(bar.getClose()) || !Double.isFinite(bar.getVolume())
                || bar.getLow() <= 0 || bar.getLow() > bar.getOpen() || bar.getLow() > bar.getClose()
                || bar.getHigh() < bar.getOpen() || bar.getHigh() < bar.getClose() || bar.getVolume() < 0) {
            throw new IllegalStateException("Invalid Bitget futures candle");
        }
    }
}
