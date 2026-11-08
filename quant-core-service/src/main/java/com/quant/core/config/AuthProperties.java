package com.quant.core.config;

import com.quant.common.auth.JwtV2Issuer;
import com.quant.common.auth.JwtV2Verifier;
import com.quant.common.auth.SecretSafety;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {
    private String jwtSecret;
    private long tokenExpireHours = 2;
    public void setJwtSecret(String value) { jwtSecret = value; }
    public void setTokenExpireHours(long value) { tokenExpireHours = value; }
    public long tokenExpireSeconds() { return tokenExpireHours * 3600; }
    @PostConstruct void validate() {
        SecretSafety.requireSecure("JWT_SECRET", jwtSecret);
    }
    public JwtV2Verifier verifier() { validate(); return new JwtV2Verifier(bytes(), Clock.systemUTC()); }
    public JwtV2Issuer issuer() { validate(); return new JwtV2Issuer(bytes(), Clock.systemUTC()); }
    byte[] secretBytes() { return SecretSafety.requireSecure("JWT_SECRET", jwtSecret); }
    private byte[] bytes() { return secretBytes(); }
}
