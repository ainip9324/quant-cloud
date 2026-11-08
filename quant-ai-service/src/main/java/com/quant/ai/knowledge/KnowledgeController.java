package com.quant.ai.knowledge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.ai.security.AiRequestAuthFilter;
import com.quant.common.auth.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** 处理问答的 HTTP 边界，包括请求体、访客 Cookie 和错误响应。 */
@RestController
class KnowledgeController {
    private static final int MAX_BODY_BYTES = 4096;
    private final KnowledgeService service;
    private final KnowledgeVisitor visitors;
    private final KnowledgeProperties properties;
    private final ObjectMapper json;

    KnowledgeController(
            KnowledgeService service,
            KnowledgeVisitor visitors,
            KnowledgeProperties properties,
            ObjectMapper json) {
        this.service = service;
        this.visitors = visitors;
        this.properties = properties;
        this.json = json;
    }

    /** 校验单轮问答请求，再按已验证的账号或签名访客身份提交问答。 */
    @PostMapping(path = AiRequestAuthFilter.KNOWLEDGE_ASK_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    KnowledgeService.Reply ask(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestAttribute(name = AiRequestAuthFilter.PRINCIPAL_ATTRIBUTE, required = false)
            AuthenticatedPrincipal principal,
            @CookieValue(name = KnowledgeVisitor.COOKIE, required = false) String cookie) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String question = readQuestion(request);

        // 登录身份来自鉴权过滤器。未登录时才读取访客 Cookie，并使用单独的额度键。
        if (principal != null) {
            return service.ask(question, "account:" + principal.userId(), false);
        }
        var visitor = visitors.resolve(cookie);
        if (visitor.newCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                    ResponseCookie.from(KnowledgeVisitor.COOKIE, visitor.newCookie())
                            .path(AiRequestAuthFilter.KNOWLEDGE_ASK_PATH)
                            .httpOnly(true)
                            .sameSite("Lax")
                            .secure(properties.secureCookie() || request.isSecure())
                            .maxAge(Duration.ofDays(7))
                            .build()
                            .toString());
        }
        return service.ask(question, "visitor:" + visitor.id(), true);
    }

    /** 限制原始请求体大小，解析单一 JSON 对象并校验问题字段。 */
    private String readQuestion(HttpServletRequest request) {
        try {
            // 多读一个字节就能识别超限请求，无需把完整请求体交给 JSON 解析器。
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw KnowledgeException.invalid();
            }
            return KnowledgeService.questionFrom(json.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(body));
        } catch (IOException exception) {
            throw KnowledgeException.invalid();
        }
    }

    /** 将问答异常转换为稳定的 HTTP 响应，超额时附上额度刷新时间。 */
    @ExceptionHandler(KnowledgeException.class)
    ResponseEntity<Map<String, Object>> failure(KnowledgeException exception) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", exception.code);
        body.put("message", exception.getMessage());
        if (exception.resetAt != null) {
            body.put("resetAt", exception.resetAt);
        }
        return ResponseEntity.status(exception.status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
