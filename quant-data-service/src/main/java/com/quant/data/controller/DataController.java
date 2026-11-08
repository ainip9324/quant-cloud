package com.quant.data.controller;

import com.quant.common.model.Kline;
import com.quant.data.service.KlineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.quant.common.market.MarketCatalog;
import com.quant.data.market.MarketDataQuery;

/**
 * 数据服务 REST 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/data")
public class DataController {

    @Autowired
    private KlineService klineService;

    /**
     * 获取K线（三级查询：Redis → MySQL → Bitget）
     */
    @GetMapping("/klines")
    public List<Kline> getKlines(@RequestParam(required=false) String marketType, @RequestParam(required=false) String symbol,
                                  @RequestParam(required=false) String interval,
                                  @RequestParam(defaultValue = "200") int limit) {
        return klineService.getKlines(MarketDataQuery.of(marketType, symbol, interval, limit));
    }

    /**
     * 支持的交易对列表
     */
    @GetMapping("/symbols")
    public List<String> getSymbols(@RequestParam(required=false) String marketType) {
        return MarketCatalog.supportedSymbols(MarketCatalog.requireMarketType(marketType));
    }

    /**
     * 手动触发K线同步
     */
    @PostMapping("/sync")
    public Map<String, Object> syncKlines(@RequestParam(required=false) String marketType, @RequestParam(required=false) String symbol,
                                           @RequestParam(required=false) String interval,
                                           @RequestParam(defaultValue = "1000") int limit) {
        log.info("手动同步K线: symbol={}, interval={}, limit={}", symbol, interval, limit);
        int synced = klineService.syncKlines(MarketDataQuery.of(marketType, symbol, interval, limit));
        Map<String, Object> result = new HashMap<>();
        result.put("synced", synced);
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("marketType", marketType);
        return result;
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "quant-data-service");
        return result;
    }
}
