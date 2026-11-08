package com.quant.gateway.config;

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
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    @PostConstruct void validate() {
        SecretSafety.requireSecure("JWT_SECRET", jwtSecret);
    }
    public JwtV2Verifier verifier() {
        validate();
        return new JwtV2Verifier(SecretSafety.requireSecure("JWT_SECRET", jwtSecret), Clock.systemUTC());
    }
}
