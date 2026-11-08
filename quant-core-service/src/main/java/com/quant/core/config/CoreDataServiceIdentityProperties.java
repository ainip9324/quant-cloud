package com.quant.core.config;

import com.quant.common.auth.SecretSafety;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "core-data-service")
public class CoreDataServiceIdentityProperties {
    private String credential;
    public void setCredential(String value) { credential = value; }
    public String credential() { return new String(secretBytes(), StandardCharsets.UTF_8); }
    byte[] secretBytes() { return SecretSafety.requireSecure("CORE_DATA_SERVICE_SECRET", credential); }
    @PostConstruct void validate() {
        secretBytes();
    }
}
