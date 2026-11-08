package com.quant.core.config;

import com.quant.core.exception.BacktestServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import com.quant.common.market.InvalidMarketQueryException;

/**
 * 全局异常处理：参数校验异常统一返回 400（默认 IllegalArgumentException 会返回 500）。
 *
 * 适用：BacktestController 列表参数校验、BacktestService 提交参数校验等业务入参错误。
 * BacktestServiceException（回放接口 404/409 语义）也在此统一处理，返回稳定业务 code。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InvalidMarketQueryException.class)
    public ResponseEntity<Map<String, Object>> handleMarket(InvalidMarketQueryException e) {
        return ResponseEntity.badRequest().body(Map.of("code", InvalidMarketQueryException.CODE, "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 回放/查询业务异常：返回约定 HTTP 状态 + 稳定业务 code（前端只依赖 code 判断）。
     */
    @ExceptionHandler(BacktestServiceException.class)
    public ResponseEntity<Map<String, Object>> handleService(BacktestServiceException e) {
        log.warn("回测服务业务异常: status={}, code={}, message={}", e.getStatus(), e.getCode(), e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("status", e.getStatus());
        body.put("error", e.getStatus() == HttpStatus.NOT_FOUND.value() ? "Not Found" : "Conflict");
        body.put("message", e.getMessage());
        body.put("code", e.getCode());
        if (e.getTaskStatus() != null) {
            body.put("taskStatus", e.getTaskStatus());
        }
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
