package com.quant.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded MVC-side adapter for synchronous model calls. It is managed and shut down by Spring. */
@Configuration
public class AiExecutionConfig {

    /** Applies connection and response limits before the executor deadline is needed as a backstop. */
    @Bean
    public RestClientCustomizer aiModelHttpTimeoutCustomizer(AiProperties properties) {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(properties.getModelTimeout());
            factory.setReadTimeout(properties.getModelTimeout());
            builder.requestFactory(factory);
        };
    }

    /** 创建问答与复盘共用的有界线程池，队列满时拒绝新任务。 */
    @Bean(destroyMethod = "shutdownNow")
    public ThreadPoolExecutor aiModelCallExecutor(AiProperties properties) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ai-model-call");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                properties.getModelExecutorCoreSize(),
                properties.getModelExecutorMaxSize(),
                30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getModelExecutorQueueCapacity()),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
