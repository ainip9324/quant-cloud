package com.quant.common.enums;

/**
 * 回测任务状态枚举
 * 状态流转：WAITING → RUNNING → SUCCESS / FAILED
 */
public enum TaskStatus {
    /** 等待中（已创建，未开始执行） */
    WAITING,
    /** 运行中 */
    RUNNING,
    /** 成功 */
    SUCCESS,
    /** 失败 */
    FAILED
}
