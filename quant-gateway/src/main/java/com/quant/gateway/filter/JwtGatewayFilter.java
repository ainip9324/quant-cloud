package com.quant.gateway.filter;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.JwtV2Verifier;
import com.quant.common.auth.MarketBriefingAccess;
import com.quant.common.auth.UserRole;
import com.quant.common.auth.MarketZoneAccess;
import com.quant.gateway.config.AuthProperties;
import com.quant.gateway.error.GatewayAuthErrorWriter;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class JwtGatewayFilter implements WebFilter, Ordered {
    public static final String TOKEN_HEADER = "Q-Token";
    public static final String USER_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";
    public static final String SERVICE_NAME_HEADER = "X-Internal-Service-Name";
    public static final String SERVICE_CREDENTIAL_HEADER = "X-Internal-Service-Credential";
    private static final Set<String> PUBLIC_POST = Set.of("/api/core/auth/login", "/api/core/auth/register",
            "/api/ai/knowledge/ask");
    private static final Set<String> PUBLIC_GET = Set.of("/api/data/health", "/api/core/health", "/actuator/health");
    private final JwtV2Verifier verifier;

    @Autowired public JwtGatewayFilter(AuthProperties properties) { this(properties.verifier()); }
    JwtGatewayFilter(JwtV2Verifier verifier) { this.verifier = verifier; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();
        if (isPublic(method, path)) return chain.filter(sanitize(exchange, null));
        if (path.startsWith("/internal/")) return GatewayAuthErrorWriter.write(exchange, HttpStatus.FORBIDDEN);
        AuthenticatedPrincipal principal;
        try {
            principal = verifier.verify(exchange.getRequest().getHeaders().getFirst(TOKEN_HEADER));
        } catch (IllegalArgumentException exception) {
            return GatewayAuthErrorWriter.write(exchange, HttpStatus.UNAUTHORIZED);
        }
        if (!isAllowed(method, path, principal.role())) {
            return GatewayAuthErrorWriter.write(exchange, HttpStatus.FORBIDDEN);
        }
        return chain.filter(sanitize(exchange, principal));
    }

    private ServerWebExchange sanitize(ServerWebExchange exchange, AuthenticatedPrincipal principal) {
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(USER_HEADER); headers.remove(ROLE_HEADER);
            headers.remove(SERVICE_NAME_HEADER); headers.remove(SERVICE_CREDENTIAL_HEADER);
            if (principal != null) {
                headers.set(USER_HEADER, Long.toString(principal.userId()));
                headers.set(ROLE_HEADER, principal.role().name());
            }
        }).build();
        return exchange.mutate().request(request).build();
    }

    static boolean isPublic(HttpMethod method, String path) {
        return method == HttpMethod.POST && PUBLIC_POST.contains(path)
                || method == HttpMethod.GET && PUBLIC_GET.contains(path);
    }

    static boolean isAllowed(HttpMethod method, String path, UserRole role) {
        if (method == HttpMethod.GET && path.equals("/api/core/auth/me")) return true;
        if (method == HttpMethod.POST && path.equals("/api/core/auth/password")) return true;
        String methodName = method == null ? null : method.name();
        if (MarketZoneAccess.isAllowed(methodName, path, role)
                || MarketBriefingAccess.isAllowed(methodName, path, role)) return true;
        if (role != UserRole.ADMIN) return false;
        return path.equals("/api/data/klines") && method == HttpMethod.GET
                || path.equals("/api/data/symbols") && method == HttpMethod.GET
                || path.equals("/api/data/sync") && method == HttpMethod.POST
                || path.equals("/api/core/strategies") && method == HttpMethod.GET
                || path.equals("/api/core/backtest") && (method == HttpMethod.GET || method == HttpMethod.POST)
                || path.matches("/api/core/backtest/\\d+(/replay)?") && method == HttpMethod.GET
                || path.equals("/api/ai/reports") && (method == HttpMethod.GET || method == HttpMethod.POST)
                || path.matches("/api/ai/reports/[1-9]\\d*") && method == HttpMethod.GET
                || path.matches("/api/ai/reports/[1-9]\\d*/regenerate") && method == HttpMethod.POST;
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
