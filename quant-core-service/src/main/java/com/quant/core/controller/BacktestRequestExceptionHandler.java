package com.quant.core.controller;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.quant.common.market.MarketQueryLimits;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps Slice 2 request-shape errors stable without changing unrelated controllers. */
@RestControllerAdvice(assignableTypes = BacktestController.class)
public class BacktestRequestExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException exception) throws HttpMessageNotReadableException {
        if (!isStrictLimitFailure(exception)) {
            throw exception;
        }
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_MARKET_QUERY",
                "message", MarketQueryLimits.INVALID_LIMIT_MESSAGE));
    }

    private boolean isStrictLimitFailure(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof JsonMappingException mapping && mapping.getPath().stream()
                    .anyMatch(reference -> "limit".equals(reference.getFieldName()))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
