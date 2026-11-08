package com.quant.data.config;

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
    public void setJwtSecret(String value) { jwtSecret = value; }
    @PostConstruct void validate() {
        secretBytes();
    }
    byte[] secretBytes() { return SecretSafety.requireSecure("JWT_SECRET", jwtSecret); }
    public JwtV2Verifier verifier() { validate(); return new JwtV2Verifier(secretBytes(), Clock.systemUTC()); }
}
