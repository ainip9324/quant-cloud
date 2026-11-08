package com.quant.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.ai.config.AuthProperties;
import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.JwtV2Verifier;
import com.quant.common.auth.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AiRequestAuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = "quant.authenticatedPrincipal";
    public static final String KNOWLEDGE_ASK_PATH = "/api/ai/knowledge/ask";
    private static final Pattern REPORT_DETAIL = Pattern.compile("/api/ai/reports/[1-9][0-9]*");
    private static final Pattern REPORT_REGENERATE =
            Pattern.compile("/api/ai/reports/[1-9][0-9]*/regenerate");

    private final JwtV2Verifier verifier;
    private final ObjectMapper mapper;

    @Autowired
    public AiRequestAuthFilter(AuthProperties properties, ObjectMapper mapper) {
        this(properties.verifier(), mapper);
    }

    AiRequestAuthFilter(JwtV2Verifier verifier, ObjectMapper mapper) {
        this.verifier = verifier;
        this.mapper = mapper;
    }

    /** 按方法和路径执行 AI 鉴权，为登录请求写入已验证的主体。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (HttpMethod.GET.matches(request.getMethod()) && path.equals("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/api/") && !isAllowedApi(request.getMethod(), path)) {
            write(response, 403, "FORBIDDEN", "Access denied");
            return;
        }
        if (!path.startsWith("/api/ai/")) {
            chain.doFilter(request, response);
            return;
        }
        boolean knowledgeAsk = path.equals(KNOWLEDGE_ASK_PATH) && HttpMethod.POST.matches(request.getMethod());
        String token = request.getHeader("Q-Token");
        // 只有没带令牌的问答请求才走访客分支。已带令牌但校验失败时返回 401。
        if (knowledgeAsk && token == null) {
            chain.doFilter(request, response);
            return;
        }
        AuthenticatedPrincipal principal;
        try {
            principal = verifier.verify(token);
        } catch (IllegalArgumentException exception) {
            write(response, 401, "UNAUTHORIZED", "Authentication required or token expired");
            return;
        }
        if (!knowledgeAsk && principal.role() != UserRole.ADMIN) {
            write(response, 403, "FORBIDDEN", "Access denied");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    /** 检查请求是否落在明确支持的问答或报告接口上。 */
    private boolean isAllowedApi(String method, String path) {
        if (path.equals(KNOWLEDGE_ASK_PATH)) {
            return HttpMethod.POST.matches(method);
        }
        if (path.equals("/api/ai/reports")) {
            return HttpMethod.GET.matches(method) || HttpMethod.POST.matches(method);
        }
        if (REPORT_DETAIL.matcher(path).matches()) {
            return HttpMethod.GET.matches(method);
        }
        return REPORT_REGENERATE.matcher(path).matches() && HttpMethod.POST.matches(method);
    }

    /** 写入不含底层异常信息的鉴权错误响应。 */
    private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), Map.of("code", code, "message", message));
    }
}
