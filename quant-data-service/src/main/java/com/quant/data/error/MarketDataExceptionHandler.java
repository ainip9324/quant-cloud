package com.quant.data.error;

import com.quant.common.market.InvalidMarketQueryException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class MarketDataExceptionHandler {
    @ExceptionHandler({InvalidMarketQueryException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        String message = e instanceof MissingServletRequestParameterException m && "marketType".equals(m.getParameterName())
                ? "marketType is required"
                : e instanceof MethodArgumentTypeMismatchException ? "limit is invalid" : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_MARKET_QUERY", "message", message));
    }
}
