package com.quant.core.controller;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.core.monitor.MarketMonitorException;
import com.quant.core.monitor.ZoneAlertPreferences;
import com.quant.core.security.CoreRequestAuthFilter;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/core/zone-alerts/settings")
@ConditionalOnProperty(name = "zone-alerts.enabled", havingValue = "true")
public class ZoneAlertPreferencesController {
    private final ZoneAlertPreferences preferences;

    public ZoneAlertPreferencesController(ZoneAlertPreferences preferences) { this.preferences = preferences; }

    @GetMapping
    public ResponseEntity<ZoneAlertPreferences.Settings> get(
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal account) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.read(account));
    }

    @PutMapping
    public ResponseEntity<ZoneAlertPreferences.Settings> save(@RequestBody ZoneAlertPreferences.Update update,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal account) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.save(account, update));
    }

    @ExceptionHandler(MarketMonitorException.class)
    public ResponseEntity<Map<String, String>> invalid(MarketMonitorException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
