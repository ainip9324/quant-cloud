package com.quant.ai;

import com.quant.ai.config.AiProperties;
import com.quant.ai.core.CoreGatewayProperties;
import com.quant.ai.knowledge.KnowledgeProperties;
import com.quant.ai.report.AiReportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AiProperties.class, CoreGatewayProperties.class, AiReportProperties.class,
        KnowledgeProperties.class})
public class AiServiceApplication {

    /** 启动 AI 服务，注册问答、复盘和模型调用所需的配置。 */
    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
