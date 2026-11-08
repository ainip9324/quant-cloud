package com.quant.ai.config;

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
    /** 在启动时校验 JWT 密钥的强度。 */
    @PostConstruct void validate() {
        SecretSafety.requireSecure("JWT_SECRET", jwtSecret);
    }
    /** 使用已校验的密钥创建 JWT 验证器。 */
    public JwtV2Verifier verifier() {
        validate();
        return new JwtV2Verifier(SecretSafety.requireSecure("JWT_SECRET", jwtSecret), Clock.systemUTC());
    }
}
