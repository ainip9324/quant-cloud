package com.quant.data.security;

import com.quant.data.config.CoreServiceIdentityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CoreServiceIdentityFilter extends OncePerRequestFilter {
    private final byte[] credential;
    @Autowired public CoreServiceIdentityFilter(CoreServiceIdentityProperties properties) { this(properties.credentialBytes()); }
    CoreServiceIdentityFilter(byte[] credential) { this.credential = credential.clone(); }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/internal/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!HttpMethod.GET.matches(request.getMethod())
                || !(path.equals("/internal/data/klines") || path.equals("/internal/data/live-snapshot"))) {
            DataRequestAuthFilter.write(response, 403, "FORBIDDEN", "Access denied"); return;
        }
        String service = request.getHeader("X-Internal-Service-Name");
        String supplied = request.getHeader("X-Internal-Service-Credential");
        byte[] suppliedBytes = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!"quant-core-service".equals(service) || !MessageDigest.isEqual(credential, suppliedBytes)) {
            DataRequestAuthFilter.write(response, 401, "UNAUTHORIZED", "Authentication required or token expired"); return;
        }
        chain.doFilter(request, response);
    }
}
