package com.quant.ai.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.ai.context.AiRequestContext;
import com.quant.ai.core.dto.BacktestReplayDto;
import com.quant.ai.core.dto.BacktestTaskDto;
import com.quant.ai.core.dto.ReplayResponseDto;
import com.quant.ai.error.AiErrorCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;

/**
 * Fixed read-only Core client. It only addresses the Gateway and sends the
 * original Q-Token; the Gateway derives the trusted X-User-Id itself.
 */
@Component
public class CoreGatewayClient {

    private final RestClient client;
    private final ObjectMapper json;

    /** 初始化指向固定 Gateway 地址的只读客户端，并设置连接和读取超时。 */
    public CoreGatewayClient(CoreGatewayProperties properties, ObjectMapper json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeout());
        factory.setReadTimeout(properties.getTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
        this.json = json;
    }

    /** 按当前认证上下文读取指定回测任务，并核对返回的任务标识与市场。 */
    public BacktestTaskDto getBacktest(AiRequestContext context, long taskId) {
        String body = getBody(context, "/api/core/backtest/" + positiveTaskId(taskId), false);
        if (body == null || body.isBlank() || "null".equals(body.trim())) {
            throw new CoreGatewayException(AiErrorCode.BACKTEST_NOT_AVAILABLE, "backtest is unavailable");
        }
        try {
            BacktestTaskDto task = json.readValue(body, BacktestTaskDto.class);
            if (task == null || task.id() == null || task.id() != taskId || blank(task.strategyId()) || task.marketType() == null) {
                throw invalidResponse();
            }
            return task;
        } catch (CoreGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    /** 读取指定回测的回放，校验任务标识、市场和事件及 K 线集合。 */
    public ReplayResponseDto getReplay(AiRequestContext context, long taskId) {
        String body = getBody(context, "/api/core/backtest/" + positiveTaskId(taskId) + "/replay", true);
        // A replay endpoint returning 200/null is malformed, not an old-task result.
        if (body == null || body.isBlank() || "null".equals(body.trim())) {
            throw invalidResponse();
        }
        try {
            ReplayResponseDto response = json.readValue(body, ReplayResponseDto.class);
            BacktestReplayDto replay = response == null ? null : response.replay();
            if (response == null || response.taskId() == null || response.taskId() != taskId
                    || blank(response.strategyId()) || response.marketType() == null || replay == null
                    || replay.events() == null || replay.bars() == null) {
                throw invalidResponse();
            }
            return response;
        } catch (CoreGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    /**
     * create的准备阶段：用同一认证上下文分别读取任务与回放，再核对二者市场/策略/标的一致。
     * 后续事实提取、指纹和证据校验都基于这份快照，不重新回测或读取实时行情。
     */
    public BacktestSnapshot getSnapshot(AiRequestContext context, long taskId) {
        BacktestTaskDto task = getBacktest(context, taskId);
        ReplayResponseDto replay = getReplay(context, taskId);
        if (!same(task.strategyId(), replay.strategyId())
                || !same(task.symbol(), replay.symbol())
                || !same(task.interval(), replay.interval()) || task.marketType() != replay.marketType()) {
            throw invalidResponse();
        }
        return new BacktestSnapshot(task, replay);
    }

    /** 携带用户凭证向固定 Core 路径发起 GET 请求，并归类网络错误。 */
    private String getBody(AiRequestContext context, String path, boolean replayRequest) {
        // 权限仍由Gateway/core逐层验证；不接受模型提供的地址，也不转发可伪造的用户身份头。
        try {
            return client.get()
                    .uri(path)
                    .header("Q-Token", context.qToken())
                    // Never forward a potentially forged X-User-Id.
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw mapStatus(response.getStatusCode().value(), response.getBody().readAllBytes(), replayRequest);
                    })
                    .body(String.class);
        } catch (CoreGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            if (hasCause(exception, SocketTimeoutException.class)) {
                throw new CoreGatewayException(AiErrorCode.AI_CORE_TIMEOUT, "core gateway timed out");
            }
            if (exception instanceof RestClientException) {
                throw new CoreGatewayException(AiErrorCode.AI_CORE_UNAVAILABLE, "core gateway is unavailable");
            }
            throw invalidResponse();
        }
    }

    /** 将下游状态码转换为 AI 服务错误，回放 409 进一步按响应错误码区分。 */
    private CoreGatewayException mapStatus(int status, byte[] body, boolean replayRequest) {
        if (status == 401) {
            return new CoreGatewayException(AiErrorCode.AI_CORE_UNAUTHORIZED, "core gateway rejected credentials");
        }
        if (status == 404) {
            return new CoreGatewayException(AiErrorCode.BACKTEST_NOT_AVAILABLE, "backtest is unavailable");
        }
        if (status == 409 && replayRequest) {
            try {
                String code = json.readTree(body).path("code").asText();
                if ("REPLAY_NOT_READY".equals(code)) {
                    return new CoreGatewayException(AiErrorCode.REPLAY_NOT_READY, "replay is not ready");
                }
                if ("REPLAY_NOT_AVAILABLE".equals(code)) {
                    return new CoreGatewayException(AiErrorCode.REPLAY_NOT_AVAILABLE, "replay is unavailable");
                }
            } catch (Exception ignored) {
                // Mapped below as an invalid response.
            }
            return invalidResponse();
        }
        if (status >= 500) {
            return new CoreGatewayException(AiErrorCode.AI_CORE_UNAVAILABLE, "core gateway is unavailable");
        }
        return invalidResponse();
    }

    private CoreGatewayException invalidResponse() {
        return new CoreGatewayException(AiErrorCode.AI_CORE_INVALID_RESPONSE, "invalid core response");
    }

    /** 在拼接 Core 请求路径前拒绝非正数任务 ID。 */
    private long positiveTaskId(long taskId) {
        if (taskId < 1) {
            throw new CoreGatewayException(AiErrorCode.AI_TOOL_INVALID_ARGUMENT, "taskId must be positive");
        }
        return taskId;
    }

    private boolean same(String first, String second) {
        return first != null && first.equals(second);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 沿异常链检查指定类型，用于识别客户端封装后的网络超时。 */
    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
