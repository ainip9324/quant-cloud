package com.quant.core.controller;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.MarketZoneAccess;
import com.quant.core.security.CoreRequestAuthFilter;
import com.quant.core.zone.MarketZone;
import com.quant.core.zone.MarketZoneException;
import com.quant.core.zone.MarketZoneService;
import com.quant.core.zone.ZoneDefinition;
import com.quant.core.zone.ZoneLifecycle;
import com.quant.core.zone.ZoneVersion;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(MarketZoneAccess.ROOT)
public class MarketZoneController {
    private final ObjectProvider<MarketZoneService> service;

    public MarketZoneController(ObjectProvider<MarketZoneService> service) { this.service = service; }

    @GetMapping
    public List<MarketZone> list(@RequestParam(required = false) String marketType,
            @RequestParam(required = false) String symbol, @RequestParam(required = false) String interval,
            @RequestParam(required = false) String lifecycle, @RequestParam(defaultValue = "100") int limit) {
        return service().list(marketType, symbol, interval, lifecycle, limit);
    }

    @GetMapping("/{id:[1-9]\\d*}")
    public MarketZone get(@PathVariable long id) { return service().get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MarketZone create(@RequestBody ZoneDefinition command,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        return service().create(command, actor);
    }

    @PutMapping("/{id:[1-9]\\d*}")
    public MarketZone update(@PathVariable long id, @RequestBody ZoneDefinition command,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        return service().update(id, command, actor);
    }

    @PostMapping("/{id:[1-9]\\d*}/enable")
    public MarketZone enable(@PathVariable long id, @RequestBody ZoneVersion command,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        return service().changeLifecycle(id, command, ZoneLifecycle.ACTIVE, actor);
    }

    @PostMapping("/{id:[1-9]\\d*}/disable")
    public MarketZone disable(@PathVariable long id, @RequestBody ZoneVersion command,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        return service().changeLifecycle(id, command, ZoneLifecycle.DISABLED, actor);
    }

    @PostMapping("/{id:[1-9]\\d*}/archive")
    public MarketZone archive(@PathVariable long id, @RequestBody ZoneVersion command,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        return service().changeLifecycle(id, command, ZoneLifecycle.ARCHIVED, actor);
    }

    @GetMapping("/{id:[1-9]\\d*}/revisions")
    public List<MarketZone> revisions(@PathVariable long id, @RequestParam(defaultValue = "100") int limit,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        return service().revisions(id, limit, actor);
    }

    @GetMapping("/{id:[1-9]\\d*}/revisions/{revision:[1-9]\\d*}")
    public MarketZone revision(@PathVariable long id, @PathVariable long revision,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        return service().revision(id, revision, actor);
    }

    private MarketZoneService service() {
        MarketZoneService available = service.getIfAvailable();
        if (available == null) throw new MarketZoneException(503, "MARKET_ZONES_UNAVAILABLE", "Shared market zones are not enabled");
        return available;
    }
}
