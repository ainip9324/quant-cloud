package com.quant.core.exception;

import lombok.Getter;

/**
 * 回测服务业务异常（携带 HTTP 状态与稳定业务 code，前端只依赖 code 判断）
 *
 * <p>回放接口语义统一：
 * <ul>
 *   <li>任务不存在或不属于当前用户：HTTP 404（不区分两种，避免泄漏任务存在性）</li>
 *   <li>WAITING/RUNNING：HTTP 409，code=REPLAY_NOT_READY，携带 taskStatus</li>
 *   <li>FAILED：HTTP 409，code=REPLAY_NOT_AVAILABLE，携带 taskStatus</li>
 *   <li>旧 SUCCESS 任务 replay_data 为空：HTTP 409，code=REPLAY_NOT_AVAILABLE</li>
 * </ul>
 */
@Getter
public class BacktestServiceException extends RuntimeException {

    /** HTTP 状态码 */
    private final int status;

    /** 稳定业务错误码（前端判断逻辑依赖它，不解析中文 message） */
    private final String code;

    /** 任务状态（REPLAY_NOT_READY / REPLAY_NOT_AVAILABLE 时携带，可为空） */
    private final String taskStatus;

    public BacktestServiceException(int status, String code, String taskStatus, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.taskStatus = taskStatus;
    }
}
