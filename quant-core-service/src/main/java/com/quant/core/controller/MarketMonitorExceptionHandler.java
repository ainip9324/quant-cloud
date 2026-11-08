package com.quant.core.controller;

import com.quant.core.monitor.MarketMonitorException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MarketMonitorController.class)
public class MarketMonitorExceptionHandler {
    @ExceptionHandler(MarketMonitorException.class)
    public ResponseEntity<Map<String, String>> monitor(MarketMonitorException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> malformed(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_MARKET_MONITOR_QUERY", "message", "Invalid market monitor query"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storage(Exception exception) {
        return ResponseEntity.status(503).body(Map.of("code", "MARKET_MONITOR_STORAGE_UNAVAILABLE", "message", "Market monitor storage is unavailable"));
    }
}
