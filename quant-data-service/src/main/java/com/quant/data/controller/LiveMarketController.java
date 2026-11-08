package com.quant.data.controller;

import com.quant.common.market.InvalidMarketQueryException;
import com.quant.common.market.MarketCatalog;
import com.quant.common.market.MarketSelection;
import com.quant.common.market.MarketType;
import com.quant.data.live.LiveMarketFeed;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LiveMarketController {
    private final ObjectProvider<LiveMarketFeed> feed;

    public LiveMarketController(ObjectProvider<LiveMarketFeed> feed) { this.feed = feed; }

    @GetMapping("/internal/data/live-snapshot")
    public ResponseEntity<?> snapshot(@RequestParam(required = false) String marketType,
            @RequestParam(required = false) String symbol, @RequestParam(required = false) String interval) {
        MarketSelection selection = MarketCatalog.requireSelection(marketType, symbol, interval);
        if (selection.marketType() != MarketType.USDT_FUTURES) throw new InvalidMarketQueryException("Live market requires USDT_FUTURES");
        LiveMarketFeed current = feed.getIfAvailable();
        if (current != null) {
            var snapshot = current.currentSnapshot(marketType, symbol, interval);
            if (snapshot.isPresent()) return ResponseEntity.ok(snapshot.get());
        }
        return ResponseEntity.status(503).body(Map.of("code", "LIVE_MARKET_UNAVAILABLE", "message", "Live market is not enabled or configured"));
    }
}
