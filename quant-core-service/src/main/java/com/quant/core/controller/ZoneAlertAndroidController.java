package com.quant.core.controller;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.MarketZoneAccess;
import com.quant.core.androidtrial.ZoneAlertAndroidStreams;
import com.quant.core.security.CoreRequestAuthFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ZoneAlertAndroidController {
    private final ObjectProvider<ZoneAlertAndroidStreams> streams;

    public ZoneAlertAndroidController(ObjectProvider<ZoneAlertAndroidStreams> streams) {
        this.streams = streams;
    }

    @GetMapping(value = MarketZoneAccess.ANDROID_ALERT_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestHeader("Q-Token") String token,
            @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal account) {
        var hub = streams.getIfAvailable();
        if (hub == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .body(hub.open(token, account));
    }
}
