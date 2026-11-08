package com.quant.core.controller;

import com.quant.core.zone.MarketZoneException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Zone errors remain local: existing auth, backtest and replay contracts are unchanged. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MarketZoneController.class)
public class MarketZoneExceptionHandler {
    @ExceptionHandler(MarketZoneException.class)
    public ResponseEntity<Map<String, String>> zone(MarketZoneException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> malformed(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_MARKET_ZONE", "message", "Invalid market zone request"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storage(DataAccessException exception) {
        return ResponseEntity.status(503).body(Map.of("code", "MARKET_ZONE_STORAGE_UNAVAILABLE", "message", "Market zone storage is unavailable"));
    }
}
