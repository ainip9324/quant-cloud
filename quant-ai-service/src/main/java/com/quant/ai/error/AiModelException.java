package com.quant.ai.error;

/**
 * 禁止将供应商响应、请求头或 API Key 原样透出；调用方只依赖稳定错误码。
 */
public class AiModelException extends RuntimeException {

    private final AiErrorCode code;

    public AiModelException(AiErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AiErrorCode getCode() {
        return code;
    }
}
