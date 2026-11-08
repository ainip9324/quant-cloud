package com.quant.common.model;

import com.quant.common.enums.PositionSide;
import lombok.Data;

import java.util.Map;

/**
 * 策略决策回放事件
 *
 * <p>不依赖日志反推，不要求前端解析中文。前端判断逻辑必须依赖 {@link #code}。
 * 事件由策略（DECISION）与引擎（EXECUTION）共同产生，
 * 统一收集到每次回测独立的 StrategyContext 事件收集器。
 *
 * <p>去重策略：不在收集器中做全局去重。保证每个状态变化分支只记录一次事件、
 * 每个引擎执行分支只发送一次事件，用 {@link #sequence} 保留真实产生顺序。
 * 测试断言"没有意外重复"而不是依赖收集器删重。
 *
 * <p>同根 K 线内按实际产生顺序记录事件。
 */
@Data
public class StrategyEvent {

    /** 事件对应 K 线时间（毫秒时间戳） */
    private Long time;

    /** 本次回测内单调递增的序号（StrategyContext.recordEvent 自动分配），用于同根 K 线内稳定排序 */
    private long sequence;

    /** 事件大类：DECISION / EXECUTION（未来可扩展 REGIME / INDICATOR） */
    private String category;

    /** 稳定机器码，如 ENTRY_EXECUTED、ENTRY_REJECTED（前端依赖 code，不解析 message） */
    private String code;

    /** 来源标识（信号来源，可为空） */
    private String sourceId;

    /** 事件时点持仓方向：NONE / LONG / SHORT */
    private PositionSide positionSide;

    /** 可选：事件对应价格 */
    private Double price;

    /** 面向用户的中文解释 */
    private String message;

    /** 可选结构化明细：如拒绝原因 */
    private Map<String, Object> details;

    /** 事件大类常量 */
    public static final String CATEGORY_DECISION = "DECISION";
    public static final String CATEGORY_EXECUTION = "EXECUTION";
}
