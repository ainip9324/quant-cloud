package com.quant.data.config;

import com.quant.common.auth.SecretSafety;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "core-service-identity")
public class CoreServiceIdentityProperties {
    private String credential;
    public void setCredential(String value) { credential = value; }
    public byte[] credentialBytes() { validate(); return secretBytes(); }
    byte[] secretBytes() { return SecretSafety.requireSecure("CORE_DATA_SERVICE_SECRET", credential); }
    @PostConstruct void validate() {
        secretBytes();
    }
}
