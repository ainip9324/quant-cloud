package com.quant.gateway.error;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class GatewayAuthErrorWriter {
    private GatewayAuthErrorWriter() {}
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status) {
        String code = status == HttpStatus.UNAUTHORIZED ? "UNAUTHORIZED" : "FORBIDDEN";
        String message = status == HttpStatus.UNAUTHORIZED
                ? "Authentication required or token expired" : "Access denied";
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
