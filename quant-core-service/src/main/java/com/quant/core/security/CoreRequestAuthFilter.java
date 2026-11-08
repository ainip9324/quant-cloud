package com.quant.core.security;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.JwtV2Verifier;
import com.quant.common.auth.MarketBriefingAccess;
import com.quant.common.auth.UserRole;
import com.quant.common.auth.MarketZoneAccess;
import com.quant.core.config.AuthProperties;
import com.alibaba.fastjson2.JSON;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CoreRequestAuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = "quant.authenticatedPrincipal";
    private final JwtV2Verifier verifier;
    @Autowired public CoreRequestAuthFilter(AuthProperties properties) { this(properties.verifier()); }
    CoreRequestAuthFilter(JwtV2Verifier verifier) { this.verifier = verifier; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (isPublic(method, path)) { chain.doFilter(request, response); return; }
        AuthenticatedPrincipal principal;
        try { principal = verifier.verify(request.getHeader("Q-Token")); }
        catch (IllegalArgumentException exception) { write(response, 401, "UNAUTHORIZED", "Authentication required or token expired"); return; }
        if (!isAllowed(method, path, principal.role())) { write(response, 403, "FORBIDDEN", "Access denied"); return; }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    static boolean isPublic(String method, String path) {
        return HttpMethod.POST.matches(method) && (path.equals("/api/core/auth/login") || path.equals("/api/core/auth/register"))
                || HttpMethod.GET.matches(method) && (path.equals("/api/core/health") || path.equals("/actuator/health"));
    }
    static boolean isAllowed(String method, String path, UserRole role) {
        if (HttpMethod.GET.matches(method) && path.equals("/api/core/auth/me")) return true;
        if (HttpMethod.POST.matches(method) && path.equals("/api/core/auth/password")) return true;
        if (MarketZoneAccess.isAllowed(method, path, role)
                || MarketBriefingAccess.isAllowed(method, path, role)) return true;
        if (role != UserRole.ADMIN) return false;
        return HttpMethod.GET.matches(method) && path.equals("/api/core/strategies")
                || path.equals("/api/core/backtest") && (HttpMethod.GET.matches(method) || HttpMethod.POST.matches(method))
                || HttpMethod.GET.matches(method) && path.matches("/api/core/backtest/\\d+(/replay)?");
    }
    private static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JSON.toJSONString(Map.of("code", code, "message", message)));
    }
}
