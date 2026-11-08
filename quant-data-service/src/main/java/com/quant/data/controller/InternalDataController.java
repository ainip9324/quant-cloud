package com.quant.data.controller;

import com.quant.common.model.Kline;
import com.quant.data.service.KlineService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.quant.data.market.MarketDataQuery;

@RestController
@RequestMapping("/internal/data")
@RequiredArgsConstructor
public class InternalDataController {
    private final KlineService klineService;
    @GetMapping("/klines")
    public List<Kline> getKlines(@RequestParam(required=false) String marketType, @RequestParam(required=false) String symbol, @RequestParam(required=false) String interval,
                                 @RequestParam(defaultValue = "200") int limit) {
        return klineService.getKlines(MarketDataQuery.of(marketType, symbol, interval, limit));
    }
}
