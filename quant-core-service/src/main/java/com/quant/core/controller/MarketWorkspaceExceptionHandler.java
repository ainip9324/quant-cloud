package com.quant.core.controller;

import com.quant.core.workspace.MarketWorkspaceException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MarketWorkspaceController.class)
public class MarketWorkspaceExceptionHandler {
    @ExceptionHandler(MarketWorkspaceException.class)
    public ResponseEntity<Map<String, String>> workspace(MarketWorkspaceException exception) {
        return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalid(Exception exception) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "INVALID_MARKET_WORKSPACE_QUERY", "message", "Invalid market workspace query"));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> storage(Exception exception) {
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "MARKET_WORKSPACE_STORAGE_UNAVAILABLE", "message", "Market workspace storage is unavailable"));
    }
}
