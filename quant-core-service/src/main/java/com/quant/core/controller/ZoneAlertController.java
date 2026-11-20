package com.quant.core.controller;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.core.monitor.ZoneAlerts;
import com.quant.core.security.CoreRequestAuthFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/core/zone-alerts")
@ConditionalOnProperty(name = "zone-alerts.enabled", havingValue = "true")
public class ZoneAlertController {
    private final ZoneAlerts alerts;

    public ZoneAlertController(ZoneAlerts alerts) { this.alerts = alerts; }

    @GetMapping(params = "!noticePage")
    public ResponseEntity<ZoneAlerts.Inbox> inbox(
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(alerts.inbox(principal.userId()));
    }

    @GetMapping(params = "noticePage")
    public ResponseEntity<ZoneAlerts.NoticePage> notices(@RequestParam int noticePage,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        if (noticePage < 1) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(alerts.noticePage(principal.userId(), noticePage));
    }

    @PutMapping("/subscriptions/{zoneId}")
    public ResponseEntity<Void> subscribe(@PathVariable long zoneId,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        alerts.subscribe(principal.userId(), zoneId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/subscriptions/{zoneId}")
    public ResponseEntity<Void> unsubscribe(@PathVariable long zoneId,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal principal) {
        alerts.unsubscribe(principal.userId(), zoneId);
        return ResponseEntity.noContent().build();
    }
}
