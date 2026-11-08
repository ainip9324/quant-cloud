package com.quant.ai.core;

import com.quant.ai.error.AiErrorCode;

public class CoreGatewayException extends RuntimeException {
    private final AiErrorCode code;
    public CoreGatewayException(AiErrorCode code, String message) { super(message); this.code = code; }
    public AiErrorCode getCode() { return code; }
}
