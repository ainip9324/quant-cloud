package com.quant.core.controller;

import com.quant.core.monitor.MarketMonitorException;
import com.quant.core.monitor.MarketMonitorView;
import com.quant.core.monitor.ZoneEventPage;
import com.quant.core.monitor.ZoneMonitoringService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/core")
public class MarketMonitorController {
    private final ObjectProvider<ZoneMonitoringService> service;
    public MarketMonitorController(ObjectProvider<ZoneMonitoringService> service) { this.service = service; }

    @GetMapping("/market-zone-observations")
    public MarketMonitorView observations(@RequestParam(required = false) String marketType,
            @RequestParam(required = false) String symbol, @RequestParam(required = false) String interval) {
        return service().observations(marketType, symbol, interval);
    }
    @GetMapping("/market-zone-events")
    public ZoneEventPage events(@RequestParam(required = false) String marketType,
            @RequestParam(required = false) String symbol, @RequestParam(required = false) String interval,
            @RequestParam(required = false) Long zoneId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service().events(marketType, symbol, interval, zoneId, page, size);
    }
    private ZoneMonitoringService service() {
        ZoneMonitoringService available = service.getIfAvailable();
        if (available == null) throw new MarketMonitorException(503, "MARKET_MONITOR_UNAVAILABLE", "Market monitor is not enabled");
        return available;
    }
}
