package com.quant.core.feign;

import com.quant.core.config.CoreDataServiceIdentityProperties;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

public class CoreDataFeignConfiguration {
    @Bean
    RequestInterceptor coreDataServiceIdentityInterceptor(CoreDataServiceIdentityProperties properties) {
        return new CoreDataServiceIdentityInterceptor(properties);
    }
}
