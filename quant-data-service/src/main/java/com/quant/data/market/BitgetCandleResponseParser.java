package com.quant.data.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.quant.common.model.Kline;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Parses public Bitget candle fixtures into deterministic timestamp order. */
final class BitgetCandleResponseParser {
    List<Kline> parse(String json) {
        JSONObject body = JSON.parseObject(json);
        if (!"00000".equals(body.getString("code"))) throw new IllegalStateException("Bitget market request failed");
        Object rawRows = body.get("data");
        if (!(rawRows instanceof JSONArray)) throw new IllegalStateException("invalid candle response");
        JSONArray rows = (JSONArray) rawRows;
        if (rows.isEmpty()) return List.of();
        Map<Long, Kline> result = new TreeMap<>();
        for (Object value : rows) {
            if (!(value instanceof JSONArray row)) throw new IllegalStateException("invalid candle response");
            if (row.size() < 6) throw new IllegalStateException("invalid candle response");
            Kline bar = new Kline();
            try {
                bar.setTime(Long.parseLong(String.valueOf(row.get(0))));
                bar.setOpen(Double.parseDouble(String.valueOf(row.get(1))));
                bar.setHigh(Double.parseDouble(String.valueOf(row.get(2))));
                bar.setLow(Double.parseDouble(String.valueOf(row.get(3))));
                bar.setClose(Double.parseDouble(String.valueOf(row.get(4))));
                bar.setVolume(Double.parseDouble(String.valueOf(row.get(5))));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("invalid candle response", exception);
            }
            Kline existing = result.putIfAbsent(bar.getTime(), bar);
            if (existing != null && !sameCandle(existing, bar)) throw new IllegalStateException("conflicting candle timestamp");
        }
        return new ArrayList<>(result.values());
    }

    private boolean sameCandle(Kline left, Kline right) {
        return Double.compare(left.getOpen(), right.getOpen()) == 0
                && Double.compare(left.getHigh(), right.getHigh()) == 0
                && Double.compare(left.getLow(), right.getLow()) == 0
                && Double.compare(left.getClose(), right.getClose()) == 0
                && Double.compare(left.getVolume(), right.getVolume()) == 0;
    }
}
