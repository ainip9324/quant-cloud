package com.quant.core.feign;

import com.quant.core.config.CoreDataServiceIdentityProperties;
import feign.RequestInterceptor;
import feign.RequestTemplate;

public final class CoreDataServiceIdentityInterceptor implements RequestInterceptor {
    static final String SERVICE_NAME = "quant-core-service";
    private final CoreDataServiceIdentityProperties properties;
    public CoreDataServiceIdentityInterceptor(CoreDataServiceIdentityProperties properties) { this.properties = properties; }
    @Override public void apply(RequestTemplate template) {
        template.removeHeader("Q-Token");
        template.header("X-Internal-Service-Name", SERVICE_NAME);
        template.header("X-Internal-Service-Credential", properties.credential());
    }
}
