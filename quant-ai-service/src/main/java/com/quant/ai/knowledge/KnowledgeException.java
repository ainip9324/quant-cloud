package com.quant.ai.knowledge;

import java.time.Instant;
import org.springframework.http.HttpStatus;

/** 问答接口可返回给客户端的稳定错误码和用户提示。 */
final class KnowledgeException extends RuntimeException {
    final HttpStatus status;
    final String code;
    final Instant resetAt;

    KnowledgeException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    KnowledgeException(HttpStatus status, String code, String message, Instant resetAt) {
        super(message);
        this.status = status;
        this.code = code;
        this.resetAt = resetAt;
    }

    /** 请求体或问题文本校验失败时构造 400 错误。 */
    static KnowledgeException invalid() {
        return new KnowledgeException(
                HttpStatus.BAD_REQUEST, "KNOWLEDGE_INVALID_QUESTION", "请输入 1 至 300 字的有效问题。");
    }

    /** 问答无法接单或所需依赖不可用时构造 503 错误。 */
    static KnowledgeException unavailable() {
        return new KnowledgeException(
                HttpStatus.SERVICE_UNAVAILABLE, "KNOWLEDGE_UNAVAILABLE", "自由提问暂时不可用，请稍后再试。");
    }

    /** 模型调用失败或返回内容不符合约定时构造 502 错误。 */
    static KnowledgeException modelFailed() {
        return new KnowledgeException(
                HttpStatus.BAD_GATEWAY, "KNOWLEDGE_MODEL_FAILED", "暂时无法生成有效回答，请稍后再试。");
    }
}
