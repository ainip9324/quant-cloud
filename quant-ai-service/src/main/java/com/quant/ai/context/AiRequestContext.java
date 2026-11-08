package com.quant.ai.context;

/**
 * Hidden execution context created after Gateway authentication. It is deliberately not a tool
 * argument, DTO, log value, or model-visible schema. Callers must pass it explicitly.
 */
public final class AiRequestContext {
    private final Long userId;
    private final String qToken;

    private AiRequestContext(Long userId, String qToken) {
        if (userId == null || qToken == null || qToken.isBlank()) {
            throw new IllegalArgumentException("authenticated AI request context is required");
        }
        this.userId = userId;
        this.qToken = qToken;
    }

    /** 封装已认证的用户 ID 与原始凭证，供服务端读取本人回测。 */
    public static AiRequestContext authenticated(Long userId, String qToken) {
        return new AiRequestContext(userId, qToken);
    }

    public Long userId() { return userId; }

    /** 提供服务端向 Gateway 发起授权读取时使用的原始凭证。 */
    public String qToken() { return qToken; }

    /** 返回脱敏标记，避免日志输出用户 ID 或凭证。 */
    @Override public String toString() { return "AiRequestContext[redacted]"; }
}
