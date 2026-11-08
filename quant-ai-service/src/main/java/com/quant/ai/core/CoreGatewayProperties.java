package com.quant.ai.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai.core-gateway")
public class CoreGatewayProperties {
    private String baseUrl = "http://quant-gateway:8080";
    private Duration timeout = Duration.ofSeconds(5);
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
}
