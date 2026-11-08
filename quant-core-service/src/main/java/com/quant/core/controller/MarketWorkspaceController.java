package com.quant.core.controller;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.core.security.CoreRequestAuthFilter;
import com.quant.core.workspace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/core/market-workspace")
public class MarketWorkspaceController {
    private final ObjectProvider<MarketWorkspaceService> workspace;
    private final ObjectProvider<MarketWorkspaceStreams> streams;
    public MarketWorkspaceController(ObjectProvider<MarketWorkspaceService> workspace, ObjectProvider<MarketWorkspaceStreams> streams) {
        this.workspace = workspace; this.streams = streams;
    }
    @GetMapping
    public ResponseEntity<MarketWorkspaceSnapshot> get(@RequestParam(required = false) String marketType,
            @RequestParam(required = false) String symbol, @RequestParam(required = false) String interval) {
        MarketWorkspaceService service = service();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.get(service.scope(marketType, symbol, interval)));
    }
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String marketType,
            @RequestParam(required = false) String symbol, @RequestParam(required = false) String interval,
            @RequestHeader("Q-Token") String token,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        MarketWorkspaceService service = service();
        MarketWorkspaceStreams hub = streams.getIfAvailable();
        if (hub == null) throw unavailable();
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .body(hub.open(service.scope(marketType, symbol, interval), token, principal));
    }
    private MarketWorkspaceService service() {
        MarketWorkspaceService service = workspace.getIfAvailable();
        if (service == null) throw unavailable();
        return service;
    }
    private MarketWorkspaceException unavailable() { return new MarketWorkspaceException(503, "MARKET_WORKSPACE_UNAVAILABLE", "Market workspace is not enabled"); }
}
