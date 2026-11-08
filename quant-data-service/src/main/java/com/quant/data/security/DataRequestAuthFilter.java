package com.quant.data.security;

import com.alibaba.fastjson2.JSON;
import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.JwtV2Verifier;
import com.quant.common.auth.UserRole;
import com.quant.data.config.AuthProperties;
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
public class DataRequestAuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = "quant.authenticatedPrincipal";
    private final JwtV2Verifier verifier;
    @Autowired public DataRequestAuthFilter(AuthProperties properties) { this(properties.verifier()); }
    DataRequestAuthFilter(JwtV2Verifier verifier) { this.verifier = verifier; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return request.getRequestURI().startsWith("/internal/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI(); String method = request.getMethod();
        if (HttpMethod.GET.matches(method) && (path.equals("/api/data/health") || path.equals("/actuator/health"))) {
            chain.doFilter(request, response); return;
        }
        if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
        AuthenticatedPrincipal principal;
        try { principal = verifier.verify(request.getHeader("Q-Token")); }
        catch (IllegalArgumentException exception) { write(response, 401, "UNAUTHORIZED", "Authentication required or token expired"); return; }
        boolean allowed = principal.role() == UserRole.ADMIN && (
                HttpMethod.GET.matches(method) && (path.equals("/api/data/klines") || path.equals("/api/data/symbols"))
                        || HttpMethod.POST.matches(method) && path.equals("/api/data/sync"));
        if (!allowed) { write(response, 403, "FORBIDDEN", "Access denied"); return; }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal); chain.doFilter(request, response);
    }
    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JSON.toJSONString(Map.of("code", code, "message", message)));
    }
}
