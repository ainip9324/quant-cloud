package com.quant.ai.report;

import com.quant.ai.error.AiErrorCode;
import com.quant.ai.error.AiModelException;
import com.quant.ai.core.CoreGatewayException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiReportExceptionHandler {
    /** 把模型或报告错误转换为固定的 HTTP 状态和业务错误码。 */
    @ExceptionHandler(AiModelException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> model(AiModelException error) {
        return org.springframework.http.ResponseEntity.status(status(error.getCode()))
                .body(Map.of("code", error.getCode().name(), "message", "AI report request failed"));
    }
    /** 把读取 Core 时的异常转换为固定的 HTTP 状态和业务错误码。 */
    @ExceptionHandler(CoreGatewayException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> core(CoreGatewayException error) {
        return org.springframework.http.ResponseEntity.status(status(error.getCode()))
                .body(Map.of("code", error.getCode().name(), "message", "AI report request failed"));
    }
    /** 统一返回请求体或参数校验错误。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public org.springframework.http.ResponseEntity<Map<String, Object>> invalid(Exception ignored) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code", "AI_TOOL_INVALID_ARGUMENT", "message", "Invalid request"));
    }
    /** 根据 AI 业务错误码选择客户端可处理的 HTTP 状态。 */
    private HttpStatus status(AiErrorCode code) {
        return switch (code) {
            case AI_REPORT_NOT_FOUND, BACKTEST_NOT_AVAILABLE -> HttpStatus.NOT_FOUND;
            case AI_REPORT_GENERATING, REPLAY_NOT_READY, REPLAY_NOT_AVAILABLE -> HttpStatus.CONFLICT;
            case AI_CORE_UNAUTHORIZED, AI_MODEL_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case AI_MODEL_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case AI_MODEL_TIMEOUT, AI_CORE_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AI_MODEL_PROVIDER_ERROR, AI_CORE_UNAVAILABLE, AI_CORE_INVALID_RESPONSE, AI_REPORT_OUTPUT_INVALID,
                    AI_REPORT_EVIDENCE_INVALID, AI_REPORT_TOOL_BUDGET_EXCEEDED -> HttpStatus.BAD_GATEWAY;
            case AI_MODEL_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_TOOL_INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
