package com.quant.data.live;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.quant.common.market.LiveCandle;
import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketSelection;
import com.quant.common.market.MarketType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Classic v2 wire format stays entirely behind this adapter. No private channels or login. */
final class BitgetWebSocketProtocol {
    private static final Map<String, String> CHANNELS = Map.of(
            "15min", "candle15m", "1h", "candle1H", "4h", "candle4H", "1day", "candle1D");

    sealed interface Update permits Ticker, Candle { String symbol(); long marketTime(); }
    record Ticker(String symbol, long marketTime, BigDecimal price) implements Update { }
    record Candle(String symbol, long marketTime, String interval, LiveCandle bar) implements Update { }

    String subscribe(List<MarketSelection> selections) {
        Map<String, Map<String, String>> args = new LinkedHashMap<>();
        for (MarketSelection selection : selections) {
            args.putIfAbsent(selection.symbol(), argument(selection.symbol(), "ticker"));
            args.put(selection.symbol() + ":" + selection.interval(),
                    argument(selection.symbol(), CHANNELS.get(selection.interval())));
        }
        return JSON.toJSONString(Map.of("op", "subscribe", "args", args.values()));
    }

    private Map<String, String> argument(String symbol, String channel) {
        return Map.of("instType", "USDT-FUTURES", "channel", channel, "instId", symbol);
    }

    List<Update> parse(String message) {
        JSONObject body = JSON.parseObject(message);
        if (body == null) throw new IllegalArgumentException("Invalid market frame");
        if (body.containsKey("event")) {
            if (!"subscribe".equals(body.getString("event"))
                    || (body.containsKey("code") && !"0".equals(body.getString("code")))) {
                throw new IllegalArgumentException("Market subscription rejected");
            }
            return List.of();
        }
        if (!List.of("snapshot", "update").contains(body.getString("action"))) {
            throw new IllegalArgumentException("Invalid market action");
        }
        JSONObject arg = body.getJSONObject("arg");
        if (arg == null || !"USDT-FUTURES".equals(arg.getString("instType"))) {
            throw new IllegalArgumentException("Invalid market identity");
        }
        String symbol = arg.getString("instId");
        if (!MarketCatalog.supportedSymbols(MarketType.USDT_FUTURES).contains(symbol)) {
            throw new IllegalArgumentException("Invalid live symbol");
        }
        JSONArray data = body.getJSONArray("data");
        if (data == null || data.isEmpty()) throw new IllegalArgumentException("Empty market frame");
        if ("ticker".equals(arg.getString("channel"))) {
            List<Update> updates = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JSONObject row = data.getJSONObject(i);
                if ((row.containsKey("symbol") && !symbol.equals(row.getString("symbol")))
                        || (row.containsKey("instId") && !symbol.equals(row.getString("instId")))) {
                    throw new IllegalArgumentException("Conflicting ticker identity");
                }
                BigDecimal price = new BigDecimal(row.getString("lastPr"));
                if (price.signum() <= 0) throw new IllegalArgumentException("Invalid ticker price");
                updates.add(new Ticker(symbol, timestamp(row.getString("ts")), price));
            }
            return List.copyOf(updates);
        }
        String interval = CHANNELS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(arg.getString("channel")))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("Invalid candle channel"));
        Map<Long, LiveCandle> bars = new java.util.TreeMap<>();
        for (int i = 0; i < data.size(); i++) {
            JSONArray row = data.getJSONArray(i);
            if (row == null || row.size() < 6) throw new IllegalArgumentException("Invalid candle row");
            LiveCandle bar = new LiveCandle(timestamp(row.getString(0)), new BigDecimal(row.getString(1)),
                    new BigDecimal(row.getString(2)), new BigDecimal(row.getString(3)),
                    new BigDecimal(row.getString(4)), new BigDecimal(row.getString(5)));
            LiveCandle previous = bars.putIfAbsent(bar.time(), bar);
            if (previous != null && !previous.equals(bar)) throw new IllegalArgumentException("Conflicting candle timestamp");
        }
        LiveCandle latest = bars.values().stream().reduce((left, right) -> right).orElseThrow();
        return List.of(new Candle(symbol, timestamp(body.getString("ts")), interval, latest));
    }

    private long timestamp(String value) {
        long result = Long.parseLong(value);
        if (result <= 0) throw new IllegalArgumentException("Invalid market timestamp");
        return result;
    }
}
