package com.quant.ai.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

import java.util.concurrent.CancellationException;

/** Suppresses only the expected cancellation race caused by a timed-out SSE request. */
@Configuration
public class ReactorDroppedErrorConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ReactorDroppedErrorConfiguration.class);

    /** 记录未交付给订阅者的 Reactor 异常，跳过预期的取消异常。 */
    @PostConstruct
    void install() {
        Hooks.onErrorDropped(error -> {
            if (!isExpectedCancellation(error)) log.error("Unexpected dropped reactive error", error);
        });
    }

    /** 应用关闭时重置 Reactor 的丢弃异常钩子。 */
    @PreDestroy
    void remove() { Hooks.resetOnErrorDropped(); }

    /** 检查异常及其原因链中是否包含任务取消。 */
    public static boolean isExpectedCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException) return true;
            current = current.getCause();
        }
        return false;
    }
}
