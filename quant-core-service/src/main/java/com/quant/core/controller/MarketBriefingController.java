package com.quant.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.MarketBriefingAccess;
import com.quant.core.briefing.MarketBriefing;
import com.quant.core.briefing.MarketBriefingService;
import com.quant.core.security.CoreRequestAuthFilter;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(MarketBriefingAccess.PATH)
public class MarketBriefingController {
    private final MarketBriefingService service;

    public MarketBriefingController(MarketBriefingService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<MarketBriefing> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get());
    }

    @PutMapping
    public MarketBriefing publish(@RequestBody JsonNode body,
                                 @RequestAttribute(CoreRequestAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedPrincipal actor) {
        JsonNode revision = body.path("expectedRevision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong()) {
            throw new IllegalArgumentException("Invalid expected briefing revision");
        }
        return service.publish(new MarketBriefingService.Update(
                revision.longValue(), body.path("outlook").textValue(),
                body.path("events").textValue(), body.path("risks").textValue()), actor);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MARKET_BRIEFING", "Invalid market briefing request");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return error(HttpStatus.CONFLICT, "MARKET_BRIEFING_CONFLICT", "Market briefing version changed");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_BRIEFING_UNAVAILABLE", "Market briefing is unavailable");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
