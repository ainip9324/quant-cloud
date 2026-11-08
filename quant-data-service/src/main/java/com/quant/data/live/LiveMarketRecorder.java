package com.quant.data.live;

import com.alibaba.fastjson2.JSON;
import com.quant.common.market.LiveCandle;
import com.quant.common.market.LiveMarketSnapshot;
import com.quant.common.market.MarketSelection;
import com.quant.common.model.Kline;
import com.quant.data.entity.KlineDO;
import com.quant.data.mapper.KlineMapper;
import com.quant.data.market.BitgetRestClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Historical recovery and live output; all methods are called by the single feed worker. */
public class LiveMarketRecorder {
    static final String CHANNEL = "market:updates:v1";
    private static final Map<String, String> GRANULARITY = Map.of(
            "15min", "15m", "1h", "1H", "4h", "4H", "1day", "1D");
    private static final DefaultRedisScript<Long> SAVE_AND_PUBLISH = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]); "
                    + "redis.call('PUBLISH', ARGV[3], ARGV[1]); return 1", Long.class);
    private final KlineMapper mapper;
    private final BitgetRestClient rest;
    private final StringRedisTemplate redis;
    private final long ttlMillis;

    public LiveMarketRecorder(KlineMapper mapper, BitgetRestClient rest, StringRedisTemplate redis, long ttlMillis) {
        this.mapper = mapper;
        this.rest = rest;
        this.redis = redis;
        this.ttlMillis = ttlMillis;
    }

    long recover(MarketSelection selection, long currentStart, Long checkpoint) {
        long period = LiveMarketProperties.periodMillis(selection.interval());
        long lastClosed = currentStart - period;
        if (checkpoint == null) {
            checkpoint = mapper.selectBySymbolInterval(selection.marketType(), selection.symbol(), selection.interval(), 2)
                    .stream().map(KlineDO::getTs).filter(time -> time != null && time <= lastClosed)
                    .max(Long::compareTo).orElse(null);
        }
        if (checkpoint != null && checkpoint > lastClosed) throw new IllegalStateException("Candle checkpoint is ahead");
        long first = checkpoint == null ? lastClosed : Math.min(checkpoint + period, lastClosed);
        if (first <= 0 || (lastClosed - first) % period != 0 || (lastClosed - first) / period + 1 > 1000) {
            throw new IllegalStateException("Candle recovery exceeds its bounded range");
        }
        List<Kline> response = rest.fetch("/api/v2/mix/market/candles", Map.of(
                "symbol", selection.symbol(), "productType", "USDT-FUTURES",
                "granularity", GRANULARITY.get(selection.interval()), "limit", "1000",
                "startTime", String.valueOf(first - 1), "endTime", String.valueOf(currentStart - 1)));
        Map<Long, Kline> closed = new TreeMap<>();
        for (Kline bar : response) {
            if (bar.getTime() != null && bar.getTime() >= first && bar.getTime() <= lastClosed) closed.put(bar.getTime(), bar);
        }
        List<KlineDO> rows = new ArrayList<>();
        for (long time = first; time <= lastClosed; time += period) {
            Kline bar = closed.get(time);
            if (bar == null) throw new IllegalStateException("Candle recovery has a gap");
            // Validate the REST values too; in particular reject NaN/infinity before storage.
            new LiveCandle(time, BigDecimal.valueOf(bar.getOpen()), BigDecimal.valueOf(bar.getHigh()),
                    BigDecimal.valueOf(bar.getLow()), BigDecimal.valueOf(bar.getClose()), BigDecimal.valueOf(bar.getVolume()));
            KlineDO row = new KlineDO();
            row.setMarketType(selection.marketType());
            row.setSymbol(selection.symbol());
            row.setInterval(selection.interval());
            row.setTs(time);
            row.setOpen(bar.getOpen()); row.setHigh(bar.getHigh()); row.setLow(bar.getLow());
            row.setClose(bar.getClose()); row.setVolume(bar.getVolume());
            rows.add(row);
        }
        mapper.batchInsertOnDuplicate(rows);
        evictHistoricalCache(selection);
        return lastClosed;
    }

    private void evictHistoricalCache(MarketSelection selection) {
        String prefix = "kline:v2:" + selection.marketType() + ":" + selection.symbol() + ":" + selection.interval() + ":";
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
            cursor.forEachRemaining(key -> { if (key.startsWith(prefix)) keys.add(key); });
        }
        if (!keys.isEmpty()) redis.delete(keys);
    }

    void publish(LiveMarketSnapshot snapshot) {
        String key = "market:live:v1:" + snapshot.marketType() + ":" + snapshot.symbol() + ":" + snapshot.interval();
        Long result = redis.execute(SAVE_AND_PUBLISH, List.of(key), JSON.toJSONString(snapshot),
                String.valueOf(ttlMillis), CHANNEL);
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("Live snapshot output failed");
    }
}
