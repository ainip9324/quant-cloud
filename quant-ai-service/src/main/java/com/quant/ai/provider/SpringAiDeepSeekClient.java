package com.quant.ai.provider;

import com.quant.ai.config.AiProperties;
import com.quant.ai.error.AiErrorCode;
import com.quant.ai.error.AiModelException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import reactor.core.publisher.Flux;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * The DeepSeek adapter uses only the documented OpenAI-compatible endpoint.
 * It deliberately does not expose reasoning_content and never calls /beta.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "model-provider", havingValue = "deepseek")
public class SpringAiDeepSeekClient implements ModelClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int JSON_MAX_ATTEMPTS = 2;
    private final ChatClient chatClient;
    private final String apiKey;
    private final String model;
    private final AiProperties properties;
    private final ThreadPoolExecutor modelCallExecutor;

    public SpringAiDeepSeekClient(ChatClient.Builder chatClientBuilder,
                                  @Value("${DEEPSEEK_API_KEY:}") String apiKey,
                                  @Value("${DEEPSEEK_MODEL:deepseek-v4-flash}") String model,
                                  AiProperties properties,
                                  ThreadPoolExecutor aiModelCallExecutor) {
        this.chatClient = chatClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.properties = properties;
        this.modelCallExecutor = aiModelCallExecutor;
    }

    /** 在单次调用期限内向 DeepSeek 请求纯文本。 */
    @Override
    public String complete(String prompt) {
        ensureConfigured();
        return callWithinDeadline(() -> chatClient.prompt().user(prompt).call().content(), deadlineNanos());
    }

    /** 在同一调用期限内请求并检查 JSON 对象，按错误类型决定是否重试。 */
    @Override
    public String completeJson(String prompt) {
        ensureConfigured();
        AiModelException lastFailure = null;
        long deadlineNanos = deadlineNanos();
        for (int attempt = 0; attempt < JSON_MAX_ATTEMPTS; attempt++) {
            try {
                String content = callWithinDeadline(() -> chatClient.prompt()
                        .options(OpenAiChatOptions.builder()
                                .model(model)
                                .responseFormat(ResponseFormat.builder()
                                        .type(ResponseFormat.Type.JSON_OBJECT)
                                        .build())
                                .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                                .build())
                        .user(prompt)
                        .call()
                        .content(), deadlineNanos);
                if (isJsonObject(content)) {
                    return content;
                }
                lastFailure = new AiModelException(AiErrorCode.AI_MODEL_PROVIDER_ERROR,
                        "AI model returned an empty or invalid JSON object");
            } catch (RuntimeException exception) {
                lastFailure = mapFailure(exception);
                if (lastFailure.getCode() == AiErrorCode.AI_MODEL_UNAUTHORIZED
                        || lastFailure.getCode() == AiErrorCode.AI_MODEL_RATE_LIMITED) {
                    throw lastFailure;
                }
            }
        }
        throw lastFailure == null
                ? new AiModelException(AiErrorCode.AI_MODEL_PROVIDER_ERROR, "AI JSON response failed")
                : lastFailure;
    }

    /** 返回 DeepSeek 的正文流，并为整个流设置总截止时间。 */
    @Override
    public Flux<String> stream(String prompt) {
        ensureConfigured();
        // Contract: modelTimeout is the total generation deadline, not the idle gap between chunks.
        return totalDeadline(chatClient.prompt().user(prompt).stream().content(), properties.getModelTimeout())
                .onErrorMap(this::mapFailure);
    }

    /** 使用默认单次超时调用结构化复盘。 */
    @Override
    public ModelCallResult completeReviewJson(String prompt, List<ToolCallback> tools, ToolContext toolContext) {
        return completeReviewJson("", prompt, tools, toolContext, properties.getModelTimeout());
    }

    /**
     * create链路的实际提供方调用：要求JSON输出，并把受控工具和隐藏上下文交给Spring AI。
     * 这里只返回候选文本及响应元数据，Schema/证据验证和唯一一次修复由AiReportService负责。
     */
    @Override
    public ModelCallResult completeReviewJson(String system, String user, List<ToolCallback> tools,
                                              ToolContext toolContext, Duration allowedDuration) {
        ensureConfigured();
        if (allowedDuration == null || allowedDuration.isZero() || allowedDuration.isNegative()) {
            throw new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "AI model request timed out");
        }
        long started = System.nanoTime();

        // 一次结构化生成可能包含多次工具回调；等待上限取模型单次配置与报告剩余预算的较小值。
        ChatResponse response = callWithinDeadline(() -> chatClient.prompt()
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .responseFormat(ResponseFormat.builder()
                                .type(ResponseFormat.Type.JSON_OBJECT)
                                .build())
                        .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                        .build())
                .toolCallbacks(tools)
                // 身份和预算不属于模型的工具输入参数，由服务端ToolContext承载。
                .toolContext(toolContext.getContext())
                .system(system)
                .user(user)
                .call()
                .chatResponse(),
                System.nanoTime() + Math.min(properties.getModelTimeout().toNanos(), allowedDuration.toNanos())
        );

        // 缺失的用量保留null；最终报告会累计首次生成/修复用量，并以共享预算补齐工具调用数。
        var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String actualModel = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? model : response.getMetadata().getModel();
        String finishReason = response.getResult() == null || response.getResult().getMetadata() == null
                ? null : response.getResult().getMetadata().getFinishReason();
        String content = response.getResult() == null ? null : response.getResult().getOutput().getText();
        return new ModelCallResult(content, "deepseek", actualModel,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(), finishReason,
                (System.nanoTime() - started) / 1_000_000, 0
        );
    }

    /** 发请求前确认真实模型凭证已配置。 */
    private void ensureConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiModelException(AiErrorCode.AI_MODEL_NOT_CONFIGURED,
                    "AI model is not configured");
        }
    }

    /** 沿异常原因链识别鉴权、限流和超时，并转为稳定业务错误。 */
    private AiModelException mapFailure(Throwable error) {
        if (error instanceof AiModelException modelException) {
            return modelException;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpStatusCodeException httpError) {
                if (httpError.getStatusCode().value() == 401) {
                    return new AiModelException(AiErrorCode.AI_MODEL_UNAUTHORIZED, "AI model authentication failed");
                }
                if (httpError.getStatusCode().value() == 429) {
                    return new AiModelException(AiErrorCode.AI_MODEL_RATE_LIMITED, "AI model request was rate limited");
                }
            }
            String message = current.getMessage();
            if (message != null && message.contains("HTTP 401")) {
                return new AiModelException(AiErrorCode.AI_MODEL_UNAUTHORIZED, "AI model authentication failed");
            }
            if (message != null && message.contains("HTTP 429")) {
                return new AiModelException(AiErrorCode.AI_MODEL_RATE_LIMITED, "AI model request was rate limited");
            }
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
                return new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "AI model request timed out");
            }
            current = current.getCause();
        }
        return new AiModelException(AiErrorCode.AI_MODEL_PROVIDER_ERROR, "AI model request failed");
    }

    /** 在有界线程池执行同步模型请求，超时时取消任务。 */
    private <T> T callWithinDeadline(Callable<T> operation, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "AI model request timed out");
        }
        final Future<T> future;
        try {
            // 模型调用交给有界线程池；队列满时直接返回提供方错误，不无限堆积请求。
            future = modelCallExecutor.submit(operation);
        } catch (RejectedExecutionException exception) {
            throw new AiModelException(AiErrorCode.AI_MODEL_PROVIDER_ERROR,
                    "AI model executor is saturated");
        }
        try {
            //等待
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            // 向执行线程发出取消/中断请求，当前报告不再继续等待该Future。
            future.cancel(true);
            throw new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "AI model request timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "AI model request interrupted");
        } catch (ExecutionException exception) {
            throw mapFailure(exception.getCause());
        }
    }

    /** 以单调时钟计算本次同步调用的截止点。 */
    private long deadlineNanos() {
        return System.nanoTime() + properties.getModelTimeout().toNanos();
    }

    /** 为响应流设置从开始计时的总截止时间，并处理取消与终止之间的竞态。 */
    private <T> Flux<T> totalDeadline(Flux<T> source, java.time.Duration duration) {
        // Do not use takeUntilOther here: cancelling Spring AI's HTTP Flux can race with its
        // decoder and produce a global onErrorDropped. This bridge owns both subscriptions and
        // consumes late cancellation errors after it has emitted the stable timeout.
        return Flux.create(sink -> {
            java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> upstream = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> timer = new java.util.concurrent.atomic.AtomicReference<>();
            upstream.set(source.onErrorResume(error -> isCancellation(error) ? Flux.empty() : Flux.error(error)).subscribe(sink::next, error -> {
                if (terminal.compareAndSet(false, true)) sink.error(error);
                // A cancellation-race error is intentionally consumed, never dropped globally.
            }, () -> { if (terminal.compareAndSet(false, true)) sink.complete(); }));
            timer.set(reactor.core.scheduler.Schedulers.parallel().schedule(() -> {
                if (terminal.compareAndSet(false, true)) {
                    reactor.core.Disposable call = upstream.get(); if (call != null) call.dispose();
                    sink.error(new AiModelException(AiErrorCode.AI_MODEL_TIMEOUT, "AI model request timed out"));
                }
            }, duration.toNanos(), TimeUnit.NANOSECONDS));
            sink.onDispose(() -> { reactor.core.Disposable call=upstream.get(); if(call!=null)call.dispose(); reactor.core.Disposable cut=timer.get(); if(cut!=null)cut.dispose(); });
        });
    }

    /** 沿异常原因链判断是否由任务取消引起。 */
    private boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** 判断模型正文是否为可解析的非空 JSON 对象。 */
    private boolean isJsonObject(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            JsonNode node = JSON.readTree(content);
            return node != null && node.isObject() && !node.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }
}
