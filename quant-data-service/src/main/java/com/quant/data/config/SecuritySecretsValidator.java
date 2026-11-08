package com.quant.data.config;

import com.quant.common.auth.SecretSafety;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class SecuritySecretsValidator {
    private final AuthProperties authProperties;
    private final CoreServiceIdentityProperties serviceIdentityProperties;

    public SecuritySecretsValidator(
            AuthProperties authProperties,
            CoreServiceIdentityProperties serviceIdentityProperties) {
        this.authProperties = authProperties;
        this.serviceIdentityProperties = serviceIdentityProperties;
    }

    @PostConstruct
    void validate() {
        SecretSafety.requireDistinct(
                "JWT_SECRET",
                authProperties.secretBytes(),
                "CORE_DATA_SERVICE_SECRET",
                serviceIdentityProperties.secretBytes());
    }
}
