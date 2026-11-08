package com.quant.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import com.quant.common.market.MarketType;

/**
 * 回测任务表实体（对应 backtest_task 表）
 * 体现异步任务状态流转：WAITING → RUNNING → SUCCESS/FAILED
 *
 * 注意：interval 是 MySQL 保留字，必须用 @TableField 显式指定带反引号的列名，
 * 否则 MyBatis-Plus 自动生成的 SQL 会因保留字冲突报语法错误。
 */
@Data
@TableName("backtest_task")
public class BacktestTaskDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 策略标识，如 turtle */
    private String strategyId;
    private MarketType marketType;

    /** 交易对 */
    private String symbol;

    /** K线周期（interval 是 MySQL 保留字，列名需加反引号） */
    @TableField("`interval`")
    private String interval;

    /** 回测起始时间（毫秒） */
    private Long startTs;

    /** 回测结束时间（毫秒） */
    private Long endTs;

    /** 策略参数（JSON 字符串） */
    private String params;

    /** 任务状态：WAITING/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 结果摘要（JSON 字符串，存 BacktestResult 序列化） */
    private String resultSummary;

    /** 失败原因 */
    private String errorMsg;

    /**
     * 回放快照（JSON，Week 7：BacktestReplay 序列化：schemaVersion/bars/events）
     *
     * <p>select = false：普通查询（selectById / selectPage / Processor）默认不加载该列，
     * 避免历史列表和任务详情读取最多 1000 根 K 线 JSON。只有回放专用查询才加载。
     */
    @TableField(select = false)
    private String replayData;

    /** 回测数量 */
    private Integer barLimit;

    /** 执行令牌（每次 claimTask 生成 UUID，防止旧执行者覆盖新执行者结果） */
    private String executionToken;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
