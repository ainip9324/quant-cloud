package com.quant.ai.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 仅保存非敏感运行配置。真实 API Key 只由 DEEPSEEK_API_KEY 环境变量提供，
 * 不放进此对象、日志或任何持久化数据。
 */
@Validated
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    @NotNull
    private ModelProvider modelProvider = ModelProvider.FAKE;

    @NotNull
    private Duration modelTimeout = Duration.ofSeconds(30);
    private String modelName = "deepseek-v4-flash";

    private int modelExecutorCoreSize = 2;
    private int modelExecutorMaxSize = 4;
    private int modelExecutorQueueCapacity = 16;

    public ModelProvider getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(ModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    public Duration getModelTimeout() {
        return modelTimeout;
    }

    public void setModelTimeout(Duration modelTimeout) {
        this.modelTimeout = modelTimeout;
    }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public int getModelExecutorCoreSize() { return modelExecutorCoreSize; }
    public void setModelExecutorCoreSize(int value) { this.modelExecutorCoreSize = value; }
    public int getModelExecutorMaxSize() { return modelExecutorMaxSize; }
    public void setModelExecutorMaxSize(int value) { this.modelExecutorMaxSize = value; }
    public int getModelExecutorQueueCapacity() { return modelExecutorQueueCapacity; }
    public void setModelExecutorQueueCapacity(int value) { this.modelExecutorQueueCapacity = value; }

    public enum ModelProvider {
        FAKE,
        DEEPSEEK
    }
}
